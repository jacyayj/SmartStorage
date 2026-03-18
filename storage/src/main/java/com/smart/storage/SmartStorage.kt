package com.smart.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * SmartStorage 是一个基于 Jetpack DataStore 的高性能存储库。
 */
object SmartStorage {
    private lateinit var appContext: Context
    private var datastoreName: String = "smart_storage"
    private var isMultiProcess: Boolean = false
    
    internal var json: Json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
        coerceInputValues = true
    }

    @PublishedApi
    internal val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val dataStore: DataStore<Preferences> by lazy {
        if (!::appContext.isInitialized) {
            throw IllegalStateException("SmartStorage must be initialized with context before use.")
        }

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
     * @param multiProcess 是否开启多进程支持。
     * @param autoPreload 是否自动预加载（建议开启）。开启后会在初始化时立即触发磁盘 IO，减少首次读取延迟。
     * @param jsonConfig 自定义 Json 配置。
     */
    fun init(
        context: Context, 
        name: String = "smart_storage", 
        multiProcess: Boolean = false,
        autoPreload: Boolean = true,
        jsonConfig: Json? = null
    ) {
        synchronized(this) {
            if (!::appContext.isInitialized) {
                appContext = context.applicationContext
                datastoreName = name
                isMultiProcess = multiProcess
                jsonConfig?.let { json = it }
                
                // 启动时预加载
                if (autoPreload) {
                    storageScope.launch {
                        dataStore.data.firstOrNull()
                    }
                }
            }
        }
    }

    /**
     * 清空所有数据
     */
    fun clearAll() {
        storageScope.launch {
            dataStore.edit { it.clear() }
        }
    }
}
