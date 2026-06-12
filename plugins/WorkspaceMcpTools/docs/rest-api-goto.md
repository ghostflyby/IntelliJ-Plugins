基于 Patch 的 IntelliJ 导航与引用查询协议设计

---

路由

```text
POST /api/v1/projects/{projectKey}/navigation
Content-Type: text/x-patch
```

复用 `/files/` PATCH 解析器，扩展三种新操作类型。`@@` hunk 上下文匹配逻辑与 PATCH
完全相同。

---

协议

```
*** Goto: <relativePath>
@@ hunk

*** Usages: <relativePath>
@@ hunk

*** Documentation: <relativePath>
@@ hunk
```

一个请求可包含多个块，各块独立解析和执行。

操作名 `Goto` / `Usages` / `Documentation` 继 `Update` / `Add` / `Delete` 之后，
作为新的动词型操作加入 Codex patch 格式。

### Selection via Diff

`-` 行为文件中已存在的完整文本行，`+` 行在目标标识符处用等长 `X` 替换。
变化区间即为 Selection 区间。

```
*** Goto: src/main/kotlin/App.kt
@@
-        userRepo.findById(request.id)
+        userRepo.XXXXXXXX(request.id)
```

### 操作说明

| 操作 | IDE 概念 | 核心 API |
|------|---------|---------|
| `Goto` | Goto Declaration | `GotoDeclarationHandler` + `PsiReference.resolve()` + `SymbolNavigationService` |
| `Usages` | Find Usages | `FindUsagesHandler.processElementUsages()` |
| `Documentation` | Quick Documentation | `LanguageDocumentation.INSTANCE.forLanguage().generateDoc()` / `generateHoverDoc()` |

**Goto：** Selection 区间 → PsiElement 后，并行查询三条路径，结果合并去重：
1. 全部已注册 `GotoDeclarationHandler`，传入 `ImaginaryEditor`，catch `NotImplemented` 降级。
2. `PsiReference.resolve()`。
3. `SymbolNavigationService.getNavigationTargets()`。

**Usages：** 通过 `FindUsagesHandlerFactory.EP_NAME` 获取对应语言的 handler，
调用 `processElementUsages(element, processor, options)`（`options` 取默认）。
得到的 `UsageInfo` 转为 `NavigationResult`。走 handler 而非裸调搜索 API 是因为
各语言的 Find Usages 行为通过 handler 扩展——仅用搜索 API 会漏掉额外引用逻辑。

**Documentation：** 先 resolve reference 得到跳转目标，通过
`LanguageDocumentation.INSTANCE.forLanguage(targetElement.language)` 获取
语言特定的文档提供器，调用 `generateDoc(targetElement, originalElement)`，
fallback 到 `generateHoverDoc()`。结果包含元素名称和文档文本。

---

示例

```
*** Goto: src/main/kotlin/App.kt
@@
-        userRepo.findById(request.id)
+        userRepo.XXXXXXXX(request.id)

*** Usages: src/main/kotlin/UserService.kt
@@
-    fun handleDelete(userId: String)
+    fun XXXXXXXXXXXX(userId: String)
```

---

响应

```json
{
  "applied": [
    {
      "operation": "goto",
      "path": "src/main/kotlin/App.kt",
      "result": {"filePath": "src/main/kotlin/repository/UserRepository.kt", "lineNumber": 25, "column": 9}
    },
    {
      "operation": "usages",
      "path": "src/main/kotlin/UserService.kt",
      "results": [
        {"filePath": "src/main/kotlin/api/UserController.kt", "lineNumber": 42, "column": 5}
      ],
      "truncated": false
    }
  ],
  "failed": []
}
```

`lineNumber` / `column` 均为 1-based。

---

实现管线

```
Patch body
  → CodexPatchParser（复用，扩展 Goto / Usages / Documentation）
  → 每个块: HunkLocator → TextRange
  → readAction: TextRange → PsiElement → IntelliJ API
  → PatchResponse { applied, failed }
```
