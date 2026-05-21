---
name: intellij-disposable
description: IntelliJ plugin Disposable/CoroutineScope lifecycle patterns. Use when registering services, listeners, or UserData that must clean up on plugin unload.
---

# IntelliJ Disposable 与 CoroutineScope 生命周期

动态插件必须在卸载时正确清理所有注册。每个注册点都应传递 `Disposable` 或 `CoroutineScope`。

## CoroutineScope 注入 Service

在 Service 中通过主构造函数声明 `CoroutineScope`，由平台管理生命周期：

```kotlin
@Service
class MyService(private val scope: CoroutineScope) {
    // scope 在插件卸载时自动 cancel
}
```

## 动态插件注册

所有注册 API（listener、executor、scheduler 等）都必须传入 `Disposable` 或 `CoroutineScope`：

```kotlin
// 传入 Disposable — 插件卸载时自动取消注册
connection.subscribe(MY_TOPIC, disposable, myHandler)

// 传入 CoroutineScope — scope cancel 时清理
scope.launch {
    // 异步工作
}
```

## UserDataHolder 清理

`UserDataHolder.putUserData()` 存储的数据必须配合 `Disposable` 清理，防止内存泄漏。

推荐使用 `AutoCleanKey`（详见 [intellij-shared-AutoCleanKey](../intellij-shared-AutoCleanKey/SKILL.md)）：

```kotlin
val key = Key.create<String>("my.key")
val cleaner = AutoCleanKey(disposable, key)

// Kotlin 委托属性，disposable 释放时自动 removeUserData
var myValue: String? by cleaner
```

## PluginDisposable 样板

每个插件应提供一个全局级 Disposable，用于插件级注册：

```kotlin
@Service
private class PluginDisposable : Disposable.Default

@Suppress("LocalVariableName")
internal val PluginDisposable
    get() = service<PluginDisposable>()
```

使用示例：

```kotlin
connection.subscribe(MY_TOPIC, PluginDisposable, myHandler)
```
