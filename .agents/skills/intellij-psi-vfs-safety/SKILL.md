---
name: intellij-psi-vfs-safety
description: PSI/VFS/Document thread-safety rules for IntelliJ plugin development. Use when reading/writing PSI trees, Documents, or VFS files.
---

# PSI / VFS / Document 线程安全

## 1. PSI 读取必须在 readAction 内

所有 PSI 读取必须运行在 `readAction`/`runReadAction` 内，除非特定 PSI API 显式文档说明不同的安全访问合约。

```kotlin
// 正确
val text = readAction { psiFile.text }

// 错误 — 可能不在读锁中
val text = psiFile.text
```

## 2. Document 读取需要 committed document + read access

需要与 PSI、committed offset、navigation/symbol resolution 保持一致的 Document 读取，必须使用 committed document 并在 read access 下进行。

```kotlin
val doc = PsiDocumentManager.getInstance(project).getLastCommittedDocument(psiFile)
val offset = readAction { doc?.getLineStartOffset(line) }
```

## 3. VFS 读写遵循 API 锁合同

VFS 读写遵循具体 API 的锁注解（`@RequiresReadLock`、`@RequiresWriteLock`）和文档合同。当需要与 PSI/Document 状态保持一致性快照时，获取对应的 read/write access。

## 4. 仅在 public API 边界手写线程断言

只有有效 public API 才直接调用 `ThreadingAssertions`。有效可见性需要同时考虑声明及其外层类型；`internal` / `private` 类型中的
public member 或 override 不属于 public API。

public API 同时保留锁注解，并通过 `generateAssertion = false` 避免 DevKit instrumentation 重复注入断言：

```kotlin
@RequiresReadLock(generateAssertion = false)
public fun readPsi(psiFile: PsiFile): String {
    ThreadingAssertions.assertReadAccess()
    return psiFile.text
}
```

`internal` / `private` 声明只使用对应的线程合同注解，不手写 `ThreadingAssertions`，也不关闭默认 instrumentation：

```kotlin
@RequiresReadLock
internal fun readPsi(psiFile: PsiFile): String = psiFile.text
```

同一规则适用于 `@RequiresWriteLock`、`@RequiresEdt`、`@RequiresBackgroundThread` 等注解与对应断言。

## 5. 修改 Document 后必须 commit

在 write action 中修改编辑器 `Document` 后，必须在返回前调用：

```kotlin
writeAction {
    document.insertString(offset, text)
    PsiDocumentManager.getInstance(project)
        .doPostponedOperationsAndUnblockDocument(document)
    PsiDocumentManager.getInstance(project).commitDocument(document)
}
```

## 6. 基于 offset 的导航/符号工具使用 committed document

```kotlin
val committedDoc = PsiDocumentManager.getInstance(project)
    .getLastCommittedDocument(psiFile)
// 使用 committedDoc 进行 offset 计算和符号解析
```

## 7. committed document 不可用时显式报错

如果 `getLastCommittedDocument` 返回 null，返回清晰的 retriable 错误消息，让调用方 commit/retry，而不是静默使用可能过期的未提交状态。
