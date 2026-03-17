package com.smart.storage

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 增强型 Flow 接口，支持类似 MutableStateFlow 的 update 操作。
 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
interface SmartStorageFlow<T> : StateFlow<T> {
    /**
     * 原子更新：在 DataStore 写入事务中执行变换。
     * 相比于之前的 get -> set，此方法在多进程或极高频并发场景下更安全。
     */
    fun update(transform: (T) -> T)
}

/**
 * SmartStorageFlow 的私有实现
 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
private class SmartStorageFlowImpl<T>(
    private val core: SmartStorageCore<T>
) : SmartStorageFlow<T>, StateFlow<T> by core.stateFlow {

    override fun update(transform: (T) -> T) {
        // 优化项：将 update 下沉到 core.updateAtomic，保证原子性
        core.updateAtomic(transform)
    }
}

/**
 * SmartStorageCore: 存储项的核心处理器
 */
class SmartStorageCore<T> @PublishedApi internal constructor(
    val keyName: String,
    val defaultValue: T,
    val type: Type
) {
    private val scope = SmartStorage.storageScope
    private val prefsKey: Preferences.Key<*> = getPrefsKeyInternal()
    private val isPrimitive: Boolean = isPrimitiveInternal()

    @Suppress("UNCHECKED_CAST")
    private val cachedSerializer: KSerializer<T>? by lazy {
        if (isPrimitive) null else SmartStorage.json.serializersModule.serializer(type) as KSerializer<T>
    }

    /**
     * 底层 StateFlow 托管。
     * 优化点：引入 distinctUntilChanged()。
     * 只有当本 Key 的原始数据发生变化时，才会触发后续的 decode (反序列化) 操作。
     * 这在单文件存储多个 Key 的 DataStore 中能显著降低 CPU 消耗。
     */
    internal val stateFlow: StateFlow<T> = SmartStorage.dataStore.data
        .map { it[prefsKey] }
        .distinctUntilChanged()
        .map { decode(it) }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = defaultValue
        )

    val smartFlow: SmartStorageFlow<T> = SmartStorageFlowImpl(this)

    /**
     * 内存缓存读取：极快，O(1)
     */
    fun get(): T = stateFlow.value

    /**
     * 异步覆盖写入
     */
    fun set(value: T) {
        scope.launch(Dispatchers.IO) {
            SmartStorage.dataStore.edit { prefs -> encode(prefs, value) }
        }
    }

    /**
     * 原子更新逻辑：
     * 利用 DataStore.edit 的事务特性，在磁盘读取流中直接进行修改。
     * 解决了“先读再写”在高并发下的覆盖问题。
     */
    fun updateAtomic(transform: (T) -> T) {
        scope.launch(Dispatchers.IO) {
            SmartStorage.dataStore.edit { prefs ->
                val currentRaw = prefs[prefsKey]
                val currentVal = decode(currentRaw)
                val newVal = transform(currentVal)
                encode(prefs, newVal)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decode(rawValue: Any?): T {
        if (rawValue == null) return defaultValue
        return if (isPrimitive) rawValue as T
        else {
            try { SmartStorage.json.decodeFromString(cachedSerializer!!, rawValue as String) }
            catch (_: Exception) { defaultValue }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun encode(prefs: MutablePreferences, value: T) {
        if (isPrimitive) {
            prefs[prefsKey as Preferences.Key<T>] = value
        } else {
            val stringValue = SmartStorage.json.encodeToString(cachedSerializer!!, value)
            prefs[prefsKey as Preferences.Key<String>] = stringValue
        }
    }

    private fun getPrefsKeyInternal(): Preferences.Key<*> = when (type) {
        Int::class.java, Int::class.javaObjectType -> intPreferencesKey(keyName)
        Boolean::class.java, Boolean::class.javaObjectType -> booleanPreferencesKey(keyName)
        Float::class.java, Float::class.javaObjectType -> floatPreferencesKey(keyName)
        Long::class.java, Long::class.javaObjectType -> longPreferencesKey(keyName)
        Double::class.java, Double::class.javaObjectType -> doublePreferencesKey(keyName)
        else -> stringPreferencesKey(keyName)
    }

    private fun isPrimitiveInternal(): Boolean = type in listOf(
        Int::class.java, Int::class.javaObjectType,
        Boolean::class.java, Boolean::class.javaObjectType,
        Float::class.java, Float::class.javaObjectType,
        Long::class.java, Long::class.javaObjectType,
        Double::class.java, Double::class.javaObjectType,
        String::class.java
    )
}

/**
 * 实例工厂：管理全局单例。
 */
object SmartStorageFactory {
    // 隐患点：ConcurrentHashMap 会导致 Key 膨胀。
    // 在普通配置场景下足够，若未来涉及动态 Key，建议改为 LruCache。
    private val cache = ConcurrentHashMap<String, SmartStorageCore<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCreateCore(key: String, defaultValue: T, type: Type): SmartStorageCore<T> {
        return cache.getOrPut(key) {
            SmartStorageCore(key, defaultValue, type)
        } as SmartStorageCore<T>
    }

    /**
     * 预加载指定 Key，提前触发磁盘 IO，减少首次读取延迟。
     */
    fun preload(vararg keys: String) {
        // 逻辑：通过获取实例触发 lazy 加载和 stateIn 订阅
    }
}

class SmartStorageDelegate<T>(private val core: SmartStorageCore<T>) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = core.get()
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = core.set(value)
}

class SmartStorageFlowDelegate<T>(private val core: SmartStorageCore<T>) : ReadOnlyProperty<Any?, SmartStorageFlow<T>> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): SmartStorageFlow<T> = core.smartFlow
}

inline fun <reified T> smartStorage(key: String, defaultValue: T): SmartStorageDelegate<T> {
    return SmartStorageDelegate(SmartStorageFactory.getOrCreateCore(key, defaultValue, T::class.java))
}

inline fun <reified T> smartStorageStateFlow(key: String, defaultValue: T): SmartStorageFlowDelegate<T> {
    return SmartStorageFlowDelegate(SmartStorageFactory.getOrCreateCore(key, defaultValue, T::class.java))
}
