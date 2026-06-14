# REST Inspection And Format API

Status: implemented as public-API-limited v1.

All routes require `X-Ghostflyby-Workspace-Session-Id` and default to Markdown.
Use JSON only with `Accept: application/json` for structured consumers.

## Problems View

```text
GET /api/v1/files/{path...}?problems=true&minSeverity=ERROR
```

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

Supported filters:

- `minSeverity=ERROR|WARNING|WEAK_WARNING|INFO`
- `name=...`, repeated, matches inspection/message/group text
- `inspection=...`, repeated, exact short name
- `fixable=true`
- `groupBy=severity&groupBy=inspection&groupBy=file`
- `limit=N`

Public-only v1 reports PSI syntax errors and IDE file problem state. It does
not expose full inspection descriptors or quick-fix lists because the available
IntelliJ APIs for those details are internal/ex implementation APIs.

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

The response is the same Markdown problem report used by `problems=true`.

## Format Operations

```text
PATCH /api/v1/files/{path...}
```

Use patch-like workspace operations for deterministic IDE formatting actions:

```patch
*** Begin Patch
*** Optimize Imports: src/A.kt
*** Reformat File: src/A.kt
*** Cleanup: src/B.kt
*** End Patch
```

When the PATCH target is a file, the file path may be omitted:

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
failed:
- src/B.kt: Cleanup is not supported without IntelliJ public APIs; CodeCleanupCodeProcessor delegates to internal/ex inspection APIs.
```

`Reformat File` uses `ReformatCodeProcessor`. `Optimize Imports` uses
`OptimizeImportsProcessor`. `Cleanup` is accepted as a request operation but is
reported unsupported in public-only v1 rather than calling internal inspection
cleanup APIs.

## Problem Fix

```text
PATCH /api/v1/files/{path...}?problemFix=true
```

Intended body shape:

```patch
*** Begin Patch
*** Fix Problem: src/A.kt
@@
- val unused = 1
+ XXXXXXXXXXXXXX
fix: Safe delete
*** End Patch
```

Public-only v1 returns `409 Conflict` with a clear unsupported message because
IntelliJ does not expose the required problem quick-fix discovery/invocation
surface without internal/ex APIs.