# Skill Name Inline Rename

## Architecture

The skill name rename system has three entry points, all converging on the same inline rename:

1. **Rename shortcut** (Shift+F6) -> `SkillNameRenameHandler` -> `performSkillNameInlineRename()`
2. **Inspection manual fix** -> `ManualRenameQuickFix` -> `performSkillNameInlineRename()`
3. **Directory rename** -> `SkillDirRenameSearchExecutor` + `SkillDirectoryNameReference` -> updates YAML name

### Key components

- `SkillNameInlineElement` - `PsiNamedElement` wrapper for a `YAMLScalar`, provides host-file-relative text range
- `SkillNameInlineReference` - precise `PsiReference` covering only the scalar value range
- `SkillNameInlineRenamer` - extends `VariableInplaceRenamer`, handles template building and directory sync
- `performSkillNameInlineRename()` - shared utility that positions caret and starts inline rename
- `SkillNameRenamePsiElementProcessor` - converts scalar rename into directory rename

## Inspection Fix Decision System

`SkillNameInspection` is a single `LocalInspectionTool` that:
1. Analyzes both name and directory once into `NamePart` (VALID / NORMALIZABLE / INVALID)
2. Computes `SkillNameState`
3. Registers up to 3 problem types: `INVALID_NAME`, `INVALID_DIRECTORY`, `MISMATCH`
4. Each problem provides filtered, deduplicated fix candidates sorted by `PriorityAction`

QuickFix priorities:
- `TOP` - AutoSetName (change YAML value)
- `HIGH` - AutoRenameDir (VFS rename directory)
- `NORMAL` - AutoRenameBoth (change both)
- `LOW` - ManualRename (inline rename)

## References

- `SkillNameRenameHandler.kt` - rename handler entry point
- `SkillNameInlineRename.kt` - inline rename infrastructure
- `SkillNameInspection.kt` - unified inspection with fix decision system
- `SkillMdPsiUtil.kt` - shared PSI utilities and name analysis
- `plugin.xml` - extension registrations
