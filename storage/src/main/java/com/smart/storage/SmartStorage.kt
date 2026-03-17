package com.smart.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * SmartStorage 是一个基于 Jetpack DataStore 的高性能存储库。
 */
object SmartStorage {
    private lateinit var appContext: Context
    private var datastoreName: String = "smart_storage"
    private var isMultiProcess: Boolean = false
    
    /**
     * 全局默认 Json 配置
     */
    internal var json: Json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
        coerceInputValues = true
    }

    /**
     * 全局协程作用域。
     * 使用 SupervisorJob 确保单个任务失败不会影响整个作用域。
     * 托管所有的磁盘写入和 StateFlow 维护。
     */
    @PublishedApi
    internal val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * DataStore 实例。
     * 懒加载模式，并在创建时配置了异常处理机制。
     */
    internal val dataStore: DataStore<Preferences> by lazy {
        if (!::appContext.isInitialized) {
            throw IllegalStateException("SmartStorage must be initialized with context before use.")
        }

        // 优化项：增加 CorruptionHandler 损坏处理器
        // 当磁盘文件因为断电、硬件故障等原因导致反序列化失败时，
        // 自动重置为 emptyPreferences，防止 App 持续崩溃。
        val corruptionHandler = ReplaceFileCorruptionHandler {
            emptyPreferences()
        }

        if (isMultiProcess) {
            MultiProcessDataStoreFactory.create(
                serializer = PreferencesFileSerializer,
                produceFile = { appContext.preferencesDataStoreFile(datastoreName) },
                corruptionHandler = corruptionHandler,
                scope = storageScope
            )
        } else {
            PreferenceDataStoreFactory.create(
                produceFile = { appContext.preferencesDataStoreFile(datastoreName) },
                corruptionHandler = corruptionHandler,
                scope = storageScope
            )
        }
    }

    /**
     * 初始化 SmartStorage。
     * @param context 上下文。
     * @param name 存储文件名。
     * @param multiProcess 是否开启多进程支持。注意：多进程依赖文件锁，高频写入会有功耗和性能影响。
     * @param jsonConfig 自定义 Json 配置。
     */
    fun init(
        context: Context, 
        name: String = "smart_storage", 
        multiProcess: Boolean = false,
        jsonConfig: Json? = null
    ) {
        synchronized(this) {
            if (!::appContext.isInitialized) {
                appContext = context.applicationContext
                datastoreName = name
                isMultiProcess = multiProcess
                jsonConfig?.let { json = it }
            }
        }
    }
}
