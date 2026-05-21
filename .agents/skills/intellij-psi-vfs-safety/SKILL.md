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

## 4. 内部辅助函数不假设调用者上下文

对于读取 PSI/Document 或需要 VFS access 约束的内部辅助函数，不假设调用者已持有正确的锁。在深层辅助函数中添加显式守卫：

```kotlin
fun readPsi(psiFile: PsiFile): String {
    ApplicationManager.getApplication().assertReadAccessAllowed()
    return psiFile.text
}
```

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
