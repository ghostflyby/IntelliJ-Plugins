# Agent First-Call Shortcut Deltas

## 记录规则
- 本文档按阶段追加，目标是提升「agent 无先验知识」场景下的首次调用有效概率，并减少回归次数。
- 每个阶段只记录“新增发现”和“新增建议”，不重复历史内容。
- 每个阶段建议至少包含：
  - 本阶段新增失败模式（或高成本调用链）。
  - 可新增的快捷工具入口（一个入口对应一个被压缩的高频调用链）。
  - 降回归的实现建议（默认值、容错、诊断、测试点）。

## Phase: SymbolSearch-P1 (2026-02-26)

### 新增发现
- `scope_list_catalog` 在大工程下返回体积极大，首次调用常出现“结果过长/截断”风险，导致 agent 拿不到可用 scopeRefId。
- 首次做库 API 调研时，常见链路是：`scope_list_catalog -> scope_resolve_program -> scope_find_files_by_name_keyword -> vfs_read_file_by_line_range`，回合数偏多。
- 部分 provider scope 仍有反射访问失败诊断（`PluginDescriptorDomFileSearchScopeProvider`），会降低“目录发现”阶段的稳定性。
- agent 第一次调用时通常不知道应选 `Project Files` 还是 `All Places`，容易选错导致召回不足或噪声过大。

### 新增快捷入口建议
- `scope_get_default_descriptor`
  - 目的：一跳返回可用 `ScopeProgramDescriptorDto`，避免先跑 catalog。
  - 输入：`preset`（如 `PROJECT_FILES`/`ALL_PLACES`/`OPEN_FILES`），`allowUiInteractiveScopes=false`。
  - 输出：`descriptor` + `diagnostics`。
  - 价值：把“首次可用 scope”从 2~3 次调用压缩到 1 次。

- `scope_catalog_find_by_intent`
  - 目的：替代“全量 catalog 拉取后客户端筛选”。
  - 输入：`intent`（如 `project_only`/`with_libraries`/`changed_files`）、`maxResults`。
  - 输出：精简候选 `scopeRefId` 列表（含稳定排序与推荐项）。
  - 价值：避免 catalog 超大响应带来的首次失败。

- `scope_resolve_standard_descriptor`
  - 目的：直接从 `standardScopeId` 生成 descriptor。
  - 输入：`standardScopeId`（如 `All Places`）、`allowUiInteractiveScopes=false`。
  - 输出：`descriptor`。
  - 价值：减少 agent 手写 atoms/tokens 的出错率。

- `scope_find_source_file_by_class_name`
  - 目的：把“在 project + jar 中按类名定位源码文件”的通用步骤做成单入口。
  - 输入：`className`、`scope`（可空，默认 `All Places`）、`preferSources=true`。
  - 输出：候选 `fileUrl`（按 sources > class 产物排序）+ `diagnostics`。
  - 价值：降低首次库 API 调研链路回归。

- `vfs_read_api_signature`
  - 目的：读取类/接口签名窗口（首段+目标方法附近），替代手工 line range 探测。
  - 输入：`fileUrl`、`symbolName`（可空）、`maxLines`。
  - 输出：结构化片段（imports、type declaration、matched members）。
  - 价值：减少 `vfs_read_file_by_line_range` 多次试探。

- `scope_search_symbols_quick`
  - 目的：面向首次调用的低参数入口。
  - 输入：`query`、`scopePreset`（默认 `PROJECT_FILES`）、`maxResultCount=50`。
  - 输出：与 `scope_search_symbols` 同结构。
  - 价值：提升首次命中率，减少参数理解成本。

### 降回归实现建议
- 默认值策略：
  - 对“首次调用”型快捷入口统一默认 `allowUiInteractiveScopes=false`。
  - scope preset 默认 `PROJECT_FILES`，并可显式切到 `ALL_PLACES`。
- 诊断策略：
  - 统一返回 `recommendedNextCalls`（例如建议切 scope、建议改用 source lookup）。
  - 对 provider 反射失败加稳定错误码，便于 agent 分支处理。
- 测试策略：
  - 增加“无先验 agent 黄金链路”集成用例：仅给 query，验证 1~2 步能拿到有效结果。
  - 增加“大 catalog 截断防回归”测试：确保可通过 intent 查询拿到候选而不依赖全量返回。

## Phase: SymbolSearch-Review-P1 (2026-02-26)

### 新增发现
- `scope_search_symbols` 原实现的进度 activity 主要覆盖“召回候选”阶段，候选转换阶段缺少同维度进度反馈，长结果场景下可观测性不足。
- 缺少完成态 activity，agent 很难从活动流中快速判断是否超时/是否有截断。

### 新增快捷入口建议
- `scope_search_symbols_with_stage_progress`
  - 目的：在标准结果之外返回分阶段统计，避免 agent 依赖日志推断执行状态。
  - 输入：与 `scope_search_symbols` 一致。
  - 输出新增：`stageStats`（recallObserved、convertedProcessed、returnedItems、timedOut）。
  - 价值：减少“是否重试/是否扩大 scope”判断回归。

- `scope_search_symbols_healthcheck`
  - 目的：在正式搜索前快速检测 provider 能力与索引可用性。
  - 输入：`scope`、`allowUiInteractiveScopes=false`。
  - 输出：`providerMode`（inScope/fallback）、`indexReady`、`diagnostics`。
  - 价值：提升首次调用策略选择正确率（直接搜索 vs 先降级）。

### 降回归实现建议
- 统一三段活动语义：`start` / `progress` / `finish`，并在 `finish` 携带 `timedOut`、`probablyHasMore`、`diagnosticsCount`。
- 对长链路工具增加“阶段计数器”字段，避免仅通过日志文本解析状态。

## Phase: CodeInsightByVfsUrl-P1 (2026-02-26)

### 新增发现
- 官方 `get_symbol_info` 以 `project-relative path + line/column` 为入口；在 jar:// 或跨根路径场景下，agent 首次常缺少稳定的相对路径上下文。
- 现有导航工具统一以 `VFS URL` 为主，调用链里如果先把 URL 反解为相对路径再做文档查询，会增加一次不必要转换并提升失败概率。
- 仅有单点查询时，agent 在批处理编排上容易过早回退到多轮串行调用，造成回归成本增加。

### 新增快捷入口建议
- `navigation_get_symbol_info_by_offset`
  - 目的：直接消费 `offset`（0-based）并返回与 `navigation_get_symbol_info` 同结构，减少 line/column 计算链路。
  - 价值：压缩“定位 + 文档查询”两步为一步，降低边界校验错误。

- `navigation_get_symbol_info_auto_position`
  - 目的：支持 `row/column` 与 `offset` 二选一输入，统一返回标准位置归一化结果。
  - 价值：提升 agent 在无先验参数规范下的首次调用有效概率。

- `navigation_get_symbol_info_quick`
  - 目的：低参数入口，仅需 `uri + row + column`，内置合理默认并在失败时返回 `recommendedNextCalls`。
  - 价值：减少首次失败后的策略抖动，降低回归轮次。

### 降回归实现建议
- 统一 symbol-info 返回结构中的位置归一化字段（`row`/`column`/`offset`），便于后续工具直接复用。
- 为 batch 接口补充 `partialSuccess=true/false` 语义字段，减少 agent 通过计数器推断状态的歧义。
- 增加 jar://、普通 file://、空文档和列越界的最小回归样例，覆盖最易触发的首次调用失败模式。
