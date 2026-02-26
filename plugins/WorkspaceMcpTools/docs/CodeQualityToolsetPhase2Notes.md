# CodeQuality Toolset Phase 2 Notes

## 本阶段新增能力
- `quality_fix_file_quick`
- `quality_fix_scope_quick`
- `quality_get_scope_problems_by_severity`
- `quality_list_inspection_profiles`
- `quality_code_cleanup_file`
- `quality_code_cleanup_scope_files`

## 研究依据
### 本地源码（主）
- `com/intellij/mcpserver/toolsets/general/AnalysisToolset.kt`
- `com/intellij/mcpserver/toolsets/general/FormattingToolset.kt`
- `com/intellij/mcpserver/toolsets/general/CodeInsightToolset.kt`
- `com/intellij/codeInspection/ex/GlobalInspectionContextBase.java`
- `com/intellij/codeInspection/ex/GlobalInspectionContextImpl.java`
- `com/intellij/profile/codeInspection/ProjectInspectionProfileManager.kt`

### Web 参考（辅）
- JetBrains Plugin SDK: Code Inspections  
  https://plugins.jetbrains.com/docs/intellij/code-inspections.html
- JetBrains Plugin SDK: Syntax and Error Highlighting  
  https://plugins.jetbrains.com/docs/intellij/syntax-highlighting-and-error-highlighting.html

## 调用习惯与常见模式（增量）
- 质量修复黄金链路：
  `quality_fix_file_quick -> quality_get_file_problems`
- 批量清理黄金链路：
  `quality_list_inspection_profiles -> quality_code_cleanup_scope_files`
- 严重级别分析链路：
  `quality_get_scope_problems_by_severity(minSeverity=ERROR|WARNING)`

## 观察到的问题与改进点
- `cleanupElements` 基于 IDE 任务调度，调用侧超时时任务可能继续执行；因此结果需要稳定诊断字段，避免 agent 重复触发。
- scope 级组合操作在大仓库内容易被 `maxFileCount` 截断；需要明确 `probablyHasMoreMatchingFiles` 语义并作为续跑信号。

## 后续性能优化建议
- 对 scope 组合修复增加“单次 scope 解析复用 + 文件列表快照缓存”，避免同轮重复 `scope.contains(file)` 遍历。
- 为 cleanup 批处理增加可选分片参数（chunkSize），在大规模文件集下缩短单批失败回滚范围。
- 在 `quality_get_scope_problems_by_severity` 增加可选 `omitLineContent=true`，降低结果体积和序列化开销。

## 可新增快捷入口建议
- `quality_cleanup_file_quick`
  - 默认使用 current profile，内置合理 timeout。
- `quality_cleanup_scope_quick`
  - 默认 `maxFileCount` + `continueOnError=true`，输出续跑建议。
- `quality_get_scope_problems_summary`
  - 仅返回计数聚合（按文件/severity），减少大结果传输。
