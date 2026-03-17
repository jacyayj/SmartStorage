package com.smart.storage

import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel

// --- 为常用组件提供扩展（语法糖） ---
// 注意：核心逻辑由 SmartStorageDelegate.kt 中的全局函数承载，统一使用库全局作用域以实现单例复用。

inline fun <reified T> ViewModel.smartStorage(key: String, defaultValue: T) =
    com.smart.storage.smartStorage(key, defaultValue)

inline fun <reified T> ViewModel.smartStorageStateFlow(key: String, defaultValue: T) =
    com.smart.storage.smartStorageStateFlow(key, defaultValue)

inline fun <reified T> ComponentActivity.smartStorage(key: String, defaultValue: T) =
    com.smart.storage.smartStorage(key, defaultValue)

inline fun <reified T> ComponentActivity.smartStorageStateFlow(key: String, defaultValue: T) =
    com.smart.storage.smartStorageStateFlow(key, defaultValue)

inline fun <reified T> Fragment.smartStorage(key: String, defaultValue: T) =
    com.smart.storage.smartStorage(key, defaultValue)

inline fun <reified T> Fragment.smartStorageStateFlow(key: String, defaultValue: T) =
    com.smart.storage.smartStorageStateFlow(key, defaultValue)

inline fun <reified T> LifecycleOwner.smartStorage(key: String, defaultValue: T) =
    com.smart.storage.smartStorage(key, defaultValue)

inline fun <reified T> LifecycleOwner.smartStorageStateFlow(key: String, defaultValue: T) =
    com.smart.storage.smartStorageStateFlow(key, defaultValue)
