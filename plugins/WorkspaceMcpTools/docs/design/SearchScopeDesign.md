# SearchScope MCP 设计稿 (v2)

> 状态：已实现（核心目标）
> 最后核对：2026-02-26

## 目标
实现可复用的 `SearchScope` 工具体系，作为后续“scope 内文本查找 / 符号查找”的统一基础。

必须覆盖：
- IDE 内置 scope（project/module 等）。
- 任意外部输入 pattern 生成的 scope。
- 预定义与用户 `NamedScope`。
- 布尔组合：`AND` / `OR` / `NOT`。
- 可指称 IDE 查找界面（Find/Scope chooser）中的 scope 集合。

## 已确认的实现事实（基于 IntelliJ 源码）
- `SearchScope` 组合运算依赖 `intersectWith()` / `union()`。
- `NOT` 由 `GlobalSearchScope.notScope(...)` 提供（仅全局 scope）。
- pattern 语法由 `PackageSetFactory.compile(...)` 解析，支持 `&&`、`||`、`!`、`$NamedScope`。
- Find 里的 scope 来源是 `PredefinedSearchScopeProvider` + `SearchScopeProvider` + scope chooser provider。
- 语言无关序列化 ID 已存在：`ScopeIdMapper`（如 `Project Files`、`All Places`）。

## 核心约束
- MCP 边界全部使用稳定 `@Serializable` DTO。
- 不使用递归 AST（避免反射 schema 递归图问题）。
- 不使用多态基类返回值（避免序列化器推导失败）。
- 优先使用 locale 无关 ID，而非显示名。
- 工具设计保持**无状态**：不引入 registry/handle，不依赖任何内部缓存。

## 边界序列化模型（非递归）
使用“原子 + RPN 程序”的线性表达，不直接传递树结构。

```kotlin
@Serializable
enum class ScopeAtomKind {
  STANDARD,          // ScopeIdMapper 标准 scope
  MODULE,            // module 相关 scope
  NAMED_SCOPE,       // NamedScope (含预定义/用户定义)
  PATTERN,           // PackageSet pattern
  DIRECTORY,         // 单目录（VFS URL）
  FILES,             // 多文件（VFS URL 列表）
  PROVIDER_SCOPE,    // 来自 SearchScopeProvider / chooser provider
}

@Serializable
enum class ScopeAtomFailureMode {
  FAIL,
  EMPTY_SCOPE,
  SKIP,
}

@Serializable
enum class ModuleScopeFlavor {
  MODULE,
  MODULE_WITH_DEPENDENCIES,
  MODULE_WITH_LIBRARIES,
  MODULE_WITH_DEPENDENCIES_AND_LIBRARIES,
}

@Serializable
data class ScopeAtomDto(
  val atomId: String,
  val kind: ScopeAtomKind,
  val scopeRefId: String? = null,
  val standardScopeId: String? = null,
  val moduleName: String? = null,
  val moduleFlavor: ModuleScopeFlavor? = null,
  val namedScopeName: String? = null,
  val namedScopeHolderId: String? = null,
  val patternText: String? = null,
  val directoryUrl: String? = null,
  val fileUrls: List<String> = emptyList(),
  val providerScopeId: String? = null,
  val onResolveFailure: ScopeAtomFailureMode? = null,
)

@Serializable
enum class ScopeProgramOp { PUSH_ATOM, AND, OR, NOT }

@Serializable
data class ScopeProgramTokenDto(
  val op: ScopeProgramOp,
  val atomId: String? = null, // 仅 PUSH_ATOM 使用
)

@Serializable
data class ScopeResolveRequestDto(
  val atoms: List<ScopeAtomDto>,
  val tokens: List<ScopeProgramTokenDto>,
  val strict: Boolean = true,
  val allowUiInteractiveScopes: Boolean = false,
  val nonStrictDefaultFailureMode: ScopeAtomFailureMode = ScopeAtomFailureMode.EMPTY_SCOPE,
)
```

### Catalog 返回模型
```kotlin
@Serializable
enum class ScopeShape { GLOBAL, LOCAL, MIXED }

@Serializable
data class ScopeCatalogItemDto(
  val scopeRefId: String,
  val displayName: String,
  val kind: ScopeAtomKind,
  val scopeShape: ScopeShape,
  val serializationId: String? = null,  // ScopeIdMapper ID（若可用）
  val requiresUserInput: Boolean = false,
  val unstable: Boolean = false,        // 如“Current File”等上下文依赖项
)

@Serializable
data class ScopeCatalogResultDto(
  val items: List<ScopeCatalogItemDto>,
  val diagnostics: List<String> = emptyList(),
)

@Serializable
data class ScopeResolveResultDto(
  val descriptor: ScopeProgramDescriptorDto,
)

@Serializable
data class ScopeProgramDescriptorDto(
  val version: Int = 1,
  val atoms: List<ScopeAtomDto>,
  val tokens: List<ScopeProgramTokenDto>,
  val displayName: String,
  val scopeShape: ScopeShape,
  val diagnostics: List<String> = emptyList(),
)
```

## Scope 引用 ID 规范（稳定可比较）
- `standard:<scopeIdMapperId>`，例如 `standard:Project Files`
- `module:<moduleName>:<flavor>`
- `named:<holderId>:<scopeName>`
- `provider:<providerKey>:<stableHash>`
- `pattern:<normalizedPatternHash>`
- `directory:<directoryUrl>`
- `files:<sortedUrlsHash>`

注：providerKey 建议包含扩展点实现类名，避免同名冲突。

## 布尔求值语义
RPN 栈机求值：
- `PUSH_ATOM`：解析并压栈 `SearchScope`。
- `AND`：弹出 2 个 scope，执行 `left.intersectWith(right)`。
- `OR`：弹出 2 个 scope，执行 `left.union(right)`。
- `NOT`：弹出 1 个 scope，仅允许 `GlobalSearchScope`，否则报错。

错误策略：
- 栈下溢、末尾栈深度不为 1、未知 atom、形态不匹配都返回明确诊断。
- `strict=true`：任一原子失败即失败。
- `strict=false`：按失败策略降级并返回 warning：
  - `ScopeAtomDto.onResolveFailure` 优先。
  - 未指定时使用 `nonStrictDefaultFailureMode`。
  - 支持 `FAIL` / `EMPTY_SCOPE` / `SKIP`（`SKIP` 后若程序无法形成有效表达式则失败）。

## Find 界面对齐策略
Catalog 构建顺序按 IDE 行为：
1. `PredefinedSearchScopeProvider`（All Places / Project Files / Open Files / Current File...）
2. `SearchScopeProvider` 扩展
3. `NamedScopesHolder` 中可用 NamedScope
4. module scope 变体
5. VFS 原子（directory/files）由调用方显式传入

对 `Current File`、`Selection`、`Usage View` 这类上下文 scope 标记：
- `requiresUserInput=true` 或 `unstable=true`
- 默认不自动解析（除非 `allowUiInteractiveScopes=true`）

## 工具分期
Phase 1（本次实现目标）
- `scope_list_catalog`
- `scope_validate_pattern`
- `scope_resolve_program`
- `scope_describe_program`

Phase 2（消费层）
- `scope_search_text`
- `scope_search_symbols`

## 对应代码结构建议
- `dev.ghostflyby.mcp.scope.SearchScopeMcpTools`
- `dev.ghostflyby.mcp.scope.ScopeCatalogService`
- `dev.ghostflyby.mcp.scope.ScopeResolverService`
- `dev.ghostflyby.mcp.scope.ScopeDtos.kt`

## 参考来源
- 本地 IntelliJ 源码（通过 VFS/jar）：
  - `com.intellij.psi.search.*`
  - `com.intellij.psi.search.scope.packageSet.*`
  - `com.intellij.ide.util.scopeChooser.*`
- JetBrains 文档：
  - https://www.jetbrains.com/help/idea/scope-language-syntax-reference.html
  - https://www.jetbrains.com/help/idea/configuring-scopes-and-file-colors.html
