---
name: intellij-shared-autocleankey
description: AutoCleanKey usage guide for UserData lifecycle management in IntelliJ plugins. Use when working with UserDataHolder cleanup tied to Disposable or CoroutineScope.
---

# intellij-shared 公用模块

`modules/intellij-shared` 提供跨插件共享的工具类。当前包含 `AutoCleanKey`。

## 模块依赖

在插件的 `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(project(":modules:intellij-shared"))
}
```

## AutoCleanKey

`AutoCleanKey` 封装 `Key<T>`，在关联的 `Disposable` 或 `CoroutineScope` 结束时自动清理所有使用该 key 的 `UserDataHolder`。避免手动管理 `removeUserData`。

### 构造方式

```kotlin
val key = Key.create<String>("my.key")

// 1. 直接绑定 Disposable
val cleaner = AutoCleanKey(disposable, key)

// 2. 延迟解析 Disposable（仅在首次 track 时调用 provider）
val cleaner = AutoCleanKey({ getDisposable() }, key)

// 3. 绑定 CoroutineScope（内部创建 Disposable，scope 结束时自动 dispose）
val cleaner = AutoCleanKey(scope, key)
```

### toAutoCleanKey 扩展

从已有 `Key` 创建 `AutoCleanKey`：

```kotlin
val cleaner = myKey.toAutoCleanKey { disposable }
val cleaner = myKey.toAutoCleanKey(scope)
```

### Kotlin 委托属性

```kotlin
val holder = object : UserDataHolderBase() {
    var value: String? by cleaner  // getValue/setValue 自动 track holder
}
```

支持的 Key 类型：
- `Key<T>` — 可空值
- `KeyWithDefaultValue<T>` — 带默认值，未设置时返回 `defaultValue`
- `NotNullLazyKey<T, H>` — 惰性计算，首次访问调用 factory

### 清理行为

当 `Disposable` 被 dispose 或 `CoroutineScope` 完成时，所有通过委托属性访问过该 key 的 `UserDataHolder` 上的该 key 都会被自动 `removeUserData`。一个 `AutoCleanKey` 可被多个 holder 复用。
