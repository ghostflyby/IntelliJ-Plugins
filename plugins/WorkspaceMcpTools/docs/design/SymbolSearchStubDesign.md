# Symbol Search 设计稿（Stub/Index First）

> 状态：已实现（主链路）
> 最后核对：2026-02-26

## 背景
`SearchScope` Phase 1 已完成 scope 描述与解析能力。下一步是实现 scope 内符号搜索，要求：
- 尽量对齐 IDE 的 `Go to Symbol / Search Everywhere` 行为。
- 优先走索引与缓存路径（而不是全文件暴力扫描）。
- 输入使用现有 `ScopeProgramDescriptorDto`，避免新增状态句柄。
- 不依赖 UI 交互，不影响用户界面。

## 目标
- 提供一个可被 agent 直接消费的符号搜索工具族。
- 在 `GLOBAL` scope 下优先使用 IDE 原生索引搜索链路。
- 在 `LOCAL/MIXED` scope 下保证语义正确（允许性能降级，但不允许错误结果）。
- 支持进度、取消、超时、上限控制和清晰 diagnostics。

## 非目标
- 不做语言专属的深度语义排序（先复用 IDE 现有排序/权重）。
- 不直接暴露底层 `StubIndexKey` 给 MCP 边界。
- 不引入服务端 registry/handle（保持无状态）。

## 已确认的 IDE 实现事实
- `ChooseByNameContributorEx` / `ChooseByNameContributorEx2` 是 `Goto Symbol` 的核心扩展接口。
- `FindSymbolParameters` 以 `GlobalSearchScope` 为主输入，支持 pattern/local pattern。
- `GotoSymbolModel2` + `ChooseByNameModelEx.getItemProvider(...)` 是 Search Everywhere 符号搜索的核心执行入口（非 UI popup 本体）。
- `DefaultSymbolNavigationContributor` 最终走 `PsiShortNamesCache`（类/字段/方法）并依赖索引。
- `ContributorsBasedGotoByModel` 负责统一调用 contributor，带取消与异常隔离。
- `GlobalSearchScopeUtil.toGlobalSearchScope(...)` 可把 `LocalSearchScope` 转成文件级 global scope（用于召回，再做二次过滤）。

## 总体方案
实现新工具集：`ScopeSymbolSearchMcpTools`。

核心策略：
1. 解析输入 scope（复用 `ScopeResolverService`）。
2. 构造“可索引召回”的 `GlobalSearchScope`。
3. 走 `GotoSymbolModel2 + ChooseByNameItemProvider + FindSymbolParameters` 做候选召回。
4. 对 `LOCAL/MIXED` 做二次精过滤（元素级或引用级判断）。
5. 输出稳定 DTO（包含定位信息、权重、diagnostics）。

## Scope 处理策略
- `GLOBAL`:
  - 直接使用 resolved `GlobalSearchScope`。
  - 这是主优化路径，索引可用性最好。
- `LOCAL`:
  - 先用 `GlobalSearchScopeUtil.toGlobalSearchScope(local, project)` 构造召回 scope。
  - 再用 `PsiSearchScopeUtil.isInScope(local, element)` 做二次过滤。
- `MIXED`:
  - 同 `LOCAL` 处理，必须二次过滤。

说明：`FindSymbolParameters` 只接受 `GlobalSearchScope`，因此 `LOCAL/MIXED` 无法只靠一次查询保证精确性。

## 索引优先策略
- 默认通过 contributor 链路进入 `PsiShortNamesCache / FileBasedIndex / StubIndex`。
- 不直接调用 `StubIndex` 作为主路径，原因：
  - 语言差异大，key 不统一。
  - 会绕过 contributor 生态，兼容性与完整性下降。
  - 后续可在特定语言/场景加“补充 fast-path”，但不取代主链路。

## 建议工具接口（Phase 1）

### `scope_search_symbols`
输入（建议）：
- `query: String`
- `scope: ScopeProgramDescriptorDto`
- `allowUiInteractiveScopes: Boolean = false`
- `maxResultCount: Int = 200`
- `timeoutMillis: Int = 30000`
- `includeNonProjectItems: Boolean = true`
- `requirePhysicalLocation: Boolean = true`

输出（建议）：
- `scopeDisplayName: String`
- `scopeShape: ScopeShape`
- `items: List<ScopeSymbolSearchItemDto>`
- `probablyHasMoreMatchingEntries: Boolean`
- `timedOut: Boolean`
- `canceled: Boolean`
- `diagnostics: List<String>`

`ScopeSymbolSearchItemDto`（建议字段）：
- `name`
- `qualifiedName`（可空）
- `fileUrl`
- `filePath`（相对项目路径，无法相对则绝对路径）
- `line`
- `column`
- `kind`（class/method/field/symbol/unknown）
- `language`（可空）
- `score`（可空，映射自 `FoundItemDescriptor`）

## 执行流程（建议实现）
1. `resolveDescriptor(...)` 得到 resolved scope。
2. 计算 `effectiveGlobalScope + needsPostFilter`。
3. 创建 `GotoSymbolModel2(project, parentDisposable)`。
4. 获取 provider：
   - 优先 `ChooseByNameInScopeItemProvider`（支持 `FindSymbolParameters`）。
   - 退化到普通 provider 时记录 diagnostic。
5. 创建 `FindSymbolParameters.wrap(query, effectiveGlobalScope)`。
6. 在 `withBackgroundProgress` + 可取消上下文中执行搜索。
7. 逐项转换为 `PsiElement/NavigationItem` 定位，过滤无物理位置项。
8. 若 `needsPostFilter=true`，做 `LOCAL/MIXED` 二次过滤。
9. 去重、排序、截断、组装 DTO 返回。

## 二次过滤与准确性
- 对 `LOCAL/MIXED` 必须二次过滤，否则会出现 false positive。
- 过滤建议：
  - 优先 `PsiSearchScopeUtil.isInScope(resolvedScope, psiElement)`。
  - 无 `PsiElement` 退化为 `resolvedScope.contains(virtualFile)`（保守）。
- 对无法判断的项：
  - 默认剔除并记录 diagnostic（避免返回不可信结果）。

## 错误处理与诊断
- `IndexNotReadyException`：返回可读错误或 partial result + diagnostic（依策略）。
- 无定位信息（无 `VirtualFile` / 无文档位置）：按配置剔除并记录。
- provider 不支持预期能力：降级并记录。
- 超时/取消：显式返回 `timedOut/canceled`。

## 性能与可中断性
- 强制 `maxResultCount`，避免大项目结果爆炸。
- 强制 `timeoutMillis`，超时后返回 partial + `probablyHasMore...`。
- 每批处理检查 cancellation（与现有 scope/text 工具一致）。
- 对 `GLOBAL + index path` 使用并发/批处理友好调用。

## 与 `directoryUrl` 的关系（后续优化点）
- 若未来符号搜索也支持 `directoryUrl`：
  - 当 `directoryUrl` 是项目内目录，且 scope 为 global，可与 `directoryScope` 做交集，仍走索引。
  - 当 `directoryUrl` 是 `jar://`（Gradle cache 等），通常需要 fallback 到可遍历策略或文件列表驱动策略。

## 工具描述文案建议
- 明确声明：
  - “优先走 IDE 索引能力”。
  - “`LOCAL/MIXED` 可能需要二次过滤，性能可能低于 `GLOBAL`。”
  - “建议先用 scope/file 工具缩小范围，再做符号搜索。”
  - “大多数情况下优先 MCP 工具，不需要命令行扫描依赖 JAR。”

## 验证建议
- 基础：
  - 项目内类/方法/字段名称可检索并可定位。
- 依赖库：
  - `All Places` 或 `MODULE_WITH_LIBRARIES` 可返回 Gradle cache JAR 中符号。
- 作用域：
  - 同一 query 在 `Project Files` 与 `All Places` 数量差异合理。
- 可靠性：
  - `LOCAL/MIXED` 下无越界结果。
  - 超时/取消返回结构正确。

## 参考（实现调研来源）
- IntelliJ 源码（本地 sources JAR）：
  - `com.intellij.navigation.ChooseByNameContributorEx`
  - `com.intellij.util.indexing.FindSymbolParameters`
  - `com.intellij.ide.util.gotoByName.GotoSymbolModel2`
  - `com.intellij.ide.util.gotoByName.ContributorsBasedGotoByModel`
  - `com.intellij.ide.util.gotoByName.DefaultSymbolNavigationContributor`
  - `com.intellij.psi.search.PsiShortNamesCache`
  - `com.intellij.psi.search.GlobalSearchScopeUtil`
- JetBrains Plugin SDK:
  - Go to Class / Go to Symbol
  - File-based indexes / Stub indexes
