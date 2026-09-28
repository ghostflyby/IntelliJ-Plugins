# Agent Skills Editing — TODO

## v0.1.0 (initial)

- [x] Plugin skeleton with `build.gradle.kts` and `plugin.xml`
- [x] JSON Schema for SKILL.md front-matter (`skill-md-schema.json`)
- [x] `JsonSchemaProviderFactory` registration via `JavaScript.JsonSchema.ProviderFactory`
- [x] `SkillMdInspection` — validates YAML front-matter, required fields, kebab-case, name consistency
- [x] `FixSkillNameQuickFix` — converts arbitrary names to kebab-case
- [x] `MatchDirectoryNameQuickFix` — sets name to parent directory name

## v0.2.0

- [x] Add `docs/` folder with detailed design notes after stabilization
- [x] Test fixtures with real `SKILL.md` samples
- [x] Integration test via `BasePlatformTestCase`

## v1.0.0 — Inline Rename + Unified Inspection

- [x] `SkillNameInspection` unified fix decision system (one state calc → three problem types → filtered fix candidates)
- [x] QuickFix priority via `PriorityAction` (AutoSetName=TOP, AutoRenameDir=HIGH, AutoRenameBoth=NORMAL, ManualRename=LOW)
- [x] `AgentSkillSymbol` declaration from SKILL.md `name` scalar
- [x] `SkillNameRenameUsageSearcher` bridges Symbol rename to directory references and directory rename
- [x] Shared `renameSkillDirectory()` helper for refactoring-aware directory quick fixes
- [x] Manual rename quick fix delegates to the platform rename handler
- [x] `NameQuality` / `NamePart` / `analyzeSkillName()` name analysis model
- [x] `normalizeSkillNameOrNull()` safe normalization
- [x] `SKILL_NAME_REGEX` disallows consecutive hyphens
- [x] `GotoDeclarationHandler` for scalar → directory navigation
- [x] `SkillDirRenameSearchExecutor` + `SkillDirectoryNameReference` for directory→YAML sync
- [x] `SkillMdPsiUtil` extraction of shared PSI helpers
- [x] Real fixture tests with `mcp-sdk` skill samples
- [x] Remove old `psi.referenceContributor` (forward PsiReference) from plugin.xml
- [x] Bundle-localized quickfix names with `quickfix.auto.*` / `quickfix.manual.*` keys
