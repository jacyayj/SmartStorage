# SmartStorage

SmartStorage 是一个专为 Android 打造的高性能、响应式且多进程安全的 Key-Value 存储库。它深度封装了 **Jetpack DataStore**，并通过 Kotlin **属性委托**与 **Flow** 提供了极致的开发体验。

## 🌟 核心特性

- **⚡ 极致性能**：
    - **全局实例复用**：相同的 Key 全局共享同一个核心实例，避免重复创建对象和内存浪费。
    - **按需解码**：引入 `distinctUntilChanged` 机制，只有目标 Key 发生变化时才触发 JSON 反序列化，大幅降低 CPU 负载。
- **动态响应式 (MVI 友好)**：
    - 支持 `StateFlow` 委托，数据变化秒级推送到 UI。
    - 独创 `SmartStorageFlow`，支持类似 `MutableStateFlow` 的 `.update { ... }` 原子更新语法。
- **🛡️ 坚如磐石**：
    - **原子写入**：所有更新操作均下沉至 DataStore 事务中，解决多线程/多进程并发冲突。
    - **异常容错**：内置 `CorruptionHandler`，自动处理磁盘文件损坏，防止应用崩溃。
- **🌐 多进程 & 灵活配置**：
    - 可选开启多进程支持。
    - 支持自定义 `Json` 序列化配置（基于 Kotlinx Serialization）。

## 📥 安装

在你的 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
}
```

## 🚀 快速上手

### 1. 初始化
在 `Application` 中进行配置：

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SmartStorage.init(
            context = this,
            name = "app_prefs",      // 可选，默认 smart_storage
            multiProcess = false,    // 可选，默认 false
            jsonConfig = null        // 可选，可自定义 Json 配置
        )
    }
}
```

### 2. 定义与使用

#### A. 基础同步模式 (简单读写)
像操作普通变量一样操作磁盘数据：

```kotlin
// 定义
var count by smartStorage("click_count", 0)

// 使用
count += 1 // 自动异步写入磁盘，读取时直接访问内存缓存
```

#### B. 响应式模式 (推荐用于 UI)
配合 `StateFlow` 实现 MVI 架构：

```kotlin
// 定义
val userProfileFlow by smartStorageStateFlow("user", UserProfile())

// 在 ViewModel 中聚合状态
val uiState = userProfileFlow.map { 
    MainUiState(userName = it.nickname) 
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainUiState())

// 原子更新 (推荐！)
fun updateName(newName: String) {
    userProfileFlow.update { it.copy(nickname = newName) }
}
```

## 🏗️ 架构设计

1. **SSOT (单一事实来源)**：通过 `SmartStorageFactory` 确保应用内同一个 Key 只有唯一的数据源。
2. **Lifecycle Aware**：利用 `WhileSubscribed(5000)`，当没有任何 UI 订阅数据时，库会自动停止磁盘监听，极度省电。
3. **Atomic Operations**：`.update()` 方法内部使用 DataStore 的事务闭包，确保在复杂并发场景下数据的一致性。

## 📝 注意事项

- **混淆**：若存储自定义 Data Class，请在混淆文件中保留该类（或使用 `@Serializable`）。
- **性能建议**：尽量避免在单个文件中存储超过 1MB 的数据，建议按业务拆分不同的存储文件名。

## 📜 许可证

```
Copyright 2025 SmartStorage Authors
Licensed under the Apache License, Version 2.0
```
