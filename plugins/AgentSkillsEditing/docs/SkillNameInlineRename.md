# Skill Name Rename

## Architecture

The skill name rename system has three entry points, all converging on refactoring-aware rename paths:

1. **Name scalar rename** -> `SkillNameDeclarationProvider` exposes an `AgentSkillSymbol`
2. **Inspection directory fixes** -> `renameSkillDirectory()` -> `RenameProcessor(project, directory, ...)`
3. **Directory rename** -> `SkillDirRenameSearchExecutor` + `SkillDirectoryNameReference` -> updates YAML name

### Key components

- `AgentSkillSymbol` - Symbol rename target backed by the skill directory `VirtualFile`
- `SkillNameRenameUsageSearcher` - bridges Symbol rename into directory-name references and a directory file rename
- `SkillDirRenameSearchExecutor` - `referencesSearch` bridge for `PsiDirectory` rename
- `SkillDirectoryNameReference` - precise `PsiReference` covering only the YAML scalar value range
- `renameSkillDirectory()` - shared helper for quick fixes that must rename the directory through IntelliJ refactoring

## Inspection Fix Decision System

`SkillNameInspection` is a single `LocalInspectionTool` that:
1. Analyzes both name and directory once into `NamePart` (VALID / NORMALIZABLE / INVALID)
2. Computes `SkillNameState`
3. Registers up to 3 problem types: `INVALID_NAME`, `INVALID_DIRECTORY`, `MISMATCH`
4. Each problem provides filtered, deduplicated fix candidates sorted by `PriorityAction`

QuickFix priorities:
- `TOP` - AutoSetName (change YAML value)
- `HIGH` - AutoRenameDir (rename the directory through `RenameProcessor`)
- `NORMAL` - AutoRenameBoth (change both)
- `LOW` - ManualRename (delegate to the platform rename handler)

## References

- `SkillNameRenameHandler.kt` - rename handler entry point
- `SkillNameSymbolRename.kt` - Symbol declaration and rename usage bridge
- `SkillDirRenameSearchExecutor.kt` - directory-target reference search
- `SkillNameInspection.kt` - unified inspection with fix decision system
- `SkillMdPsiUtil.kt` - shared PSI utilities and name analysis
- `plugin.xml` - extension registrations
