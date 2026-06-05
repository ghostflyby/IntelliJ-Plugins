# Agent Skills Editing

<!-- Plugin description -->
Provides editing support for Codex SKILL.md files:

- **JSON Schema injection** — Autocomplete and validation for SKILL.md YAML front-matter
- **Metadata inspection** — Validates required fields (`name`, `description`)
- **Naming convention checks** — Enforces kebab-case for skill names
- **Name consistency** — Ensures skill name matches the parent directory name
- **QuickFixes** — One-click fixes for naming issues
<!-- Plugin description end -->

## Features

| Feature     | Description                                                               |
|-------------|---------------------------------------------------------------------------|
| JSON Schema | Provides schema for `name` (kebab-case pattern) and `description`         |
| Inspection  | Validates front-matter structure, required fields, and naming conventions |
| QuickFix    | Auto-convert names to kebab-case or match parent directory                |

## SKILL.md Format

```yaml
---
name: my-skill-name
description: A brief description of the skill's purpose
---

# Skill Title
... documentation body ...
```
