# Inspection And Format

Load `negotiation-and-discovery.md` first if `BASE` or `SESSION_ID` are not known.

All routes require:

```text
X-Ghostflyby-Workspace-Session-Id: <sessionId>
```

Default output is Markdown. Do not use JSON as the primary agent-facing format.

## Problems

```text
GET /api/v1/files/{path...}?problems=true&minSeverity=ERROR
```

Useful filters:

- `minSeverity=ERROR|WARNING|WEAK_WARNING|INFO`
- `name=unused`
- `inspection=SyntaxError`
- `fixable=true`
- `groupBy=severity&groupBy=inspection`
- `limit=200`

Example response:

```markdown
---
path: "src/Broken.xml"
minSeverity: "ERROR"
count: 1
truncated: false
timedOut: false
---
## Problems
| severity | file | line | inspection | message | fixes |
| --- | --- | ---: | --- | --- | --- |
| ERROR | src/Broken.xml | 1 | SyntaxError | Element root is not closed |  |
## Diagnostics
- Problem details are public-API limited: syntax errors come from PSI; file problem state comes from WolfTheProblemSolver. Full inspection quick fixes are not exposed without internal/ex APIs.
```

## Inspection Request

```text
POST /api/v1/inspections/{path...}?minSeverity=ERROR&limit=200
```

No body inspects the route path. Multi-file targets use patch-like operations:

```patch
*** Begin Patch
*** Inspect File: src/A.kt
*** Inspect File: src/B.kt
*** End Patch
```

The response is the same Markdown problem report as `problems=true`.

## Format Operations

Use `/files` PATCH with patch-like workspace operations:

```patch
*** Begin Patch
*** Optimize Imports: src/A.kt
*** Reformat File: src/A.kt
*** Cleanup: src/B.kt
*** End Patch
```

When several workspace operations target the same file, the server runs them in
stable order: `Fix Problem`, `Cleanup`, `Optimize Imports`, then `Reformat File`.
Duplicate operation kinds for the same file are applied once.

When PATCH targets one file, the operation path may be omitted:

```patch
*** Begin Patch
*** Optimize Imports
*** Reformat File
*** End Patch
```

Example response:

```text
applied:
- optimize-imports src/A.kt
- reformat src/A.kt
- cleanup src/B.kt
```

## Problem Fix

```text
PATCH /api/v1/files/{path...}?problemFix=true
```

Expected request shape:

```patch
*** Begin Patch
*** Fix Problem: src/A.kt
@@
- val unused = 1
+ XXXXXXXXXXXXXX
fix: Safe delete
*** End Patch
```

Public-only v1 returns `409 Conflict` because IntelliJ does not expose full
problem quick-fix discovery/invocation without internal/ex APIs.