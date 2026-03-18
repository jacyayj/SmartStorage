package com.smart.storage

import android.util.LruCache
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.lang.reflect.Type
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * SmartStorageFlow: 增强型响应式流接口
 *
 * 为什么自定义接口而不直接使用 StateFlow？
 * 1. 提供了类似 MutableStateFlow 的 update 语法，使 MVI 架构下的状态更新更优雅。
 * 2. 封装了底层的 DataStore 事务逻辑，使开发者无需关心协程切换和原子性问题。
 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
interface SmartStorageFlow<T> : StateFlow<T> {
    /**
     * 原子更新：在 DataStore 写入事务中执行变换。
     * 相比于“先读后写”，此方法在多线程或多进程并发场景下能确保数据的一致性。
     */
    fun update(transform: (T) -> T)

    /**
     * 重置当前配置项：显式向磁盘写入初始默认值。
     */
    fun reset()

    /**
     * 物理删除：从磁盘和内存中彻底移除该 Key。
     * 优点：相比重置，物理删除能进一步减小磁盘文件体积并释放内存占用的 Preferences 条目。
     */
    fun remove()
}

/**
 * SmartStorageFlow 的内部实现类
 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
private class SmartStorageFlowImpl<T>(
    private val core: SmartStorageCore<T>
) : SmartStorageFlow<T>, StateFlow<T> by core.stateFlow {

    override fun update(transform: (T) -> T) {
        core.updateAtomic(transform)
    }

    override fun reset() {
        core.reset()
    }

    override fun remove() {
        core.remove()
    }
}

/**
 * SmartStorageCore: 存储项的核心处理器
 * 负责数据的编解码、磁盘交互逻辑以及内存热流的维护。
 */
class SmartStorageCore<T> @PublishedApi internal constructor(
    val keyName: String,
    val defaultValue: T,
    val type: Type
) {
    private val scope = SmartStorage.storageScope
    private val prefsKey: Preferences.Key<*> = getPrefsKeyInternal()
    private val isPrimitive: Boolean = isPrimitiveInternal()

    // 缓存序列化器，避免每次读写时通过反射重新查找，提升性能
    @Suppress("UNCHECKED_CAST")
    private val cachedSerializer: KSerializer<T>? by lazy {
        if (isPrimitive) null else SmartStorage.json.serializersModule.serializer(type) as KSerializer<T>
    }

    /**
     * 核心 StateFlow 驱动链
     *
     * 关键优化点：
     * 1. distinctUntilChanged(): DataStore 是单文件存储，修改 Key A 会导致 Key B 的流也发射。
     *    此操作符能拦截无效发射，只有当本 Key 的原始值改变时才进行后续昂贵的 JSON 反序列化。
     * 2. WhileSubscribed(5000): 功耗优化。当没有 UI 订阅时 5 秒自动挂起磁盘监听，
     *    当有新订阅者时立即激活并返回最新缓存。
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
     * 同步读取内存缓存，O(1) 复杂度
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
     * 核心原子更新逻辑
     * 利用 DataStore.edit 的事务特性，确保在高并发读取/写入时数据不会被相互覆盖。
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

    fun reset() {
        set(defaultValue)
    }

    fun remove() {
        scope.launch(Dispatchers.IO) {
            SmartStorage.dataStore.edit { prefs ->
                prefs.remove(prefsKey)
            }
        }
    }

    /**
     * 内部解码逻辑
     */
    @Suppress("UNCHECKED_CAST")
    private fun decode(rawValue: Any?): T {
        if (rawValue == null) return defaultValue
        return if (isPrimitive) rawValue as T
        else {
            try {
                SmartStorage.json.decodeFromString(cachedSerializer!!, rawValue as String)
            } catch (_: Exception) {
                defaultValue
            }
        }
    }

    /**
     * 内部编码逻辑
     */
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
 * SmartStorageFactory: 实例工厂与内存管理中心
 */
object SmartStorageFactory {
    /**
     * 内存防线：使用 LruCache 替换 HashMap。
     * 如果开发者误用了动态生成的 Key（如包含毫秒值或 UserId），
     * LruCache 会自动清理旧实例，防止内存无限膨胀导致 OOM。
     */
    private val cache = LruCache<String, SmartStorageCore<*>>(100)

    /**
     * 获取或创建核心实例。
     * 使用 synchronized 确保单例创建的线程安全性，维护 SSOT（单一事实来源）。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCreateCore(key: String, defaultValue: T, type: Type): SmartStorageCore<T> {
        synchronized(cache) {
            val existing = cache.get(key)
            if (existing != null) {
                return existing as SmartStorageCore<T>
            }
            val newCore = SmartStorageCore(key, defaultValue, type)
            cache.put(key, newCore)
            return newCore
        }
    }
}

/**
 * --------------------------------------------------------------------------
 * 属性委托与全局入口
 * --------------------------------------------------------------------------
 */

class SmartStorageDelegate<T>(private val core: SmartStorageCore<T>) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = core.get()
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = core.set(value)
}

class SmartStorageFlowDelegate<T>(private val core: SmartStorageCore<T>) : ReadOnlyProperty<Any?, SmartStorageFlow<T>> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): SmartStorageFlow<T> = core.smartFlow
}

/**
 * 基础属性委托同步读写
 */
inline fun <reified T> smartStorage(key: String, defaultValue: T): SmartStorageDelegate<T> {
    return SmartStorageDelegate(SmartStorageFactory.getOrCreateCore(key, defaultValue, T::class.java))
}

/**
 * 响应式 Flow 委托
 */
inline fun <reified T> smartStorageStateFlow(key: String, defaultValue: T): SmartStorageFlowDelegate<T> {
    return SmartStorageFlowDelegate(SmartStorageFactory.getOrCreateCore(key, defaultValue, T::class.java))
}
