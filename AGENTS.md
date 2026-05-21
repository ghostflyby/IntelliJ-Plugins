# IntelliJ Plugins

## Coding

- 使用 **Kotlin** 开发插件。
- 异步操作（PSI/VFS 读写、进度报告、后台任务、可取消注册等）使用 **Kotlin 协程**，避免阻塞 UI 线程。
- 不要在 UI 线程上运行阻塞操作。
- `Dispatchers.EDT` = UI 线程 + writeAction。不需要写权限时使用 `Dispatchers.UI`。

```kotlin
import com.intellij.openapi.application.backgroundWirteAction
import com.intellij.openapi.application.readAction
// 读 PSI — 不需要 writeAction
val text = readAction { psiFile.text }

// 写 Document — 需要 writeAction
backgroundWirteAction { document.setText("new") }
```

- 使用显式可见性修饰符，默认使用 `internal`。
- **禁止**使用 `@ApiStatus.Internal` API（Marketplace 会上会被拦截），使用前务必检查。
- 谨慎使用 `@ApiStatus.Experimental` API（可能无废弃期变更）。若必须使用，`@Suppress("UnstableApiUsage")` 并在注释中说明原因，方便后续跟踪 IntelliJ 版本变更。

Disposable 生命周期与清理模式参见 [intellij-disposable](.agents/skills/intellij-disposable/SKILL.md)。

## Project Structure

本仓库为 Gradle monorepo：`settings.gradle.kts` 自动将 `plugins/` 下每个目录注册为 `:plugins:<pluginName>`，`modules/` 下注册为 `:modules:<moduleName>`。

```
IntelliJ-Plugins/
├── plugins/
│   ├── EnhancedHotSwapEnabler/
│   ├── GradleMcpTools/
│   ├── IdeaVimToggleIME/
│   ├── LiveTemplatesWithSelection/
│   ├── macOSRecents/
│   ├── SpotlessIntegration/
│   ├── VitePress/
│   └── WorkspaceMcpTools/
├── modules/
│   └── intellij-shared/
├── settings.gradle.kts
└── build.gradle.kts
```

- 插件标准布局：`build.gradle.kts`、`src/`、`README.md`、`CHANGELOG.md`、`TODO.md`。
- 部分插件通过 `projects.txt` 声明嵌套 Gradle 子项目（例：`plugins/SpotlessIntegration/projects.txt` 含 `ModelBuilderService`）。增删条目时保持目录名与 Gradle include 一致。
- 每个插件根目录维护 `TODO.md`，记录当前计划/重构项，简短、可执行、英文。
- 计划项完成后，将最终说明移入插件内的 `docs/<PluginName>-<Topic>.md`，然后刷新 `TODO.md` 为下一阶段计划。

## PSI / VFS / Document

PSI、VFS、Document 读写必须遵循 IntelliJ 线程模型与锁合同。

参见 [intellij-psi-vfs-safety](.agents/skills/intellij-psi-vfs-safety/SKILL.md)。

## Build & Gradle

- **禁止**运行 `verifyPlugin`（耗时长，留给 CI）。
- 单次 Gradle sync 约 1.5–2 分钟。
- 单次 `buildPlugin` 约 2 分钟。
- 修改 public API 时更新 Kotlin ABI 文件。
- 使用专用 MCP 工具代替命令行操作。

## Diagnostics & Docs

- 文档中明确区分已实现项与计划项，减少 agent 困惑。
- 插件 `TODO.md` 仅作为过渡文档；完成的工作必须归档到 `docs/`，使用主题明确的文件名，保持计划与历史记录分离。
- Changelog 条目描述对最终用户或集成者可见的变更结果，不列出内部重构步骤、纯测试工作或实现细节。
- 仅手动在 `Unreleased` 部分添加 changelog 条目。不要手动将 `Unreleased` 转为带日期的版本号段落（CI 负责 changelog rollover）。
- 版本号提升仍需在发布变更时手动更新 Gradle metadata。

## Shared Modules

`AutoCleanKey` 使用指南参见 [intellij-shared-AutoCleanKey](.agents/skills/intellij-shared-AutoCleanKey/SKILL.md)。
