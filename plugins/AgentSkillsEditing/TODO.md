# Agent Skills Editing — TODO

## v0.1.0 (initial)

- [x] Plugin skeleton with `build.gradle.kts` and `plugin.xml`
- [x] JSON Schema for SKILL.md front-matter (`skill-md-schema.json`)
- [x] `JsonSchemaProviderFactory` registration via `JavaScript.JsonSchema.ProviderFactory`
- [x] `SkillMdInspection` — validates YAML front-matter, required fields, kebab-case, name consistency
- [x] `FixSkillNameQuickFix` — converts arbitrary names to kebab-case
- [x] `MatchDirectoryNameQuickFix` — sets name to parent directory name

## v0.2.0

- [ ] Add `docs/` folder with detailed design notes after stabilization
- [ ] Test fixtures with real `SKILL.md` samples
- [ ] Integration test via `LightPlatformCodeInsightFixtureTestCase`
