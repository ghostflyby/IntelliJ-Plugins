# Write And Patch

Load `negotiation-and-discovery.md` first if `BASE` or `SESSION_ID` are not known.

Only mutate through this API when the user clearly asked for mutation. Avoid
`force=true` unless requested or clearly necessary.

All write routes require:

```text
X-Ghostflyby-Workspace-Session-Id: <sessionId>
```

## Write Routes

```text
PUT    /api/v1/files/{path...}
POST   /api/v1/files/{path...}
DELETE /api/v1/files/{path...}
```

Use `PUT` to create or replace text:

```bash
curl -i -X PUT \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary $'package demo\n\nclass Demo\n' \
  "$BASE/files/src/Demo.kt"
```

Example body:

```text
uri: file:///workspace/src/Demo.kt
encodedUri: file%3A%2F%2F%2Fworkspace%2Fsrc%2FDemo.kt
```

Use `POST` to create only when absent. Empty body creates a directory:

```bash
curl -i -X POST \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary 'note' \
  "$BASE/files/notes/today.txt"

curl -i -X POST \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  --data-binary '' \
  "$BASE/files/new-directory"
```

Example body:

```text
uri: file:///workspace/new-directory
encodedUri: file%3A%2F%2F%2Fworkspace%2Fnew-directory
```

Use `DELETE` for files or empty directories. File deletion uses IntelliJ safe-delete
refactoring. If code references are found, the response is `409 Conflict` with a
reference table and the file is not deleted unless `force=true` is present.

```bash
curl -i -X DELETE \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/notes/today.txt"
```

Example body:

```text
---
path: "notes/today.txt"
deleted: true
referenceCount: 0
---
```

## Force

Only pass `force=true` intentionally:

```bash
curl -i -X PUT \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary 'override ignored text' \
  "$BASE/files/generated.txt?force=true"
```

`force=false` is explicit false and must not bypass policy.

Example policy failure body:

```text
Ignored text file requires force=true for writes
force: false
```

## Patch Route

```text
PATCH /api/v1/files/{path...}
```

The target may be a file or directory. If the target is a file, patch body paths
are ignored or normalized and all hunks apply to the URL target file. If the
target is a directory, patch section paths are resolved under that directory.

The OpenAI Responses API `apply_patch` format is auto-detected when the body
starts with `*** `. Load `apply-patch-format.md` only when the exact format is
unknown or a patch fails because of formatting.

```bash
curl -i -X PATCH \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary @change.patch \
  "$BASE/files/src/Main.kt"
```

Example body:

```text
applied:
- update src/Main.kt
```

Git patch format is auto-detected from `diff --git` or `--- `, or explicitly
selected with `text/x-patch`:

```bash
curl -i -X PATCH \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  -H 'Content-Type: text/x-patch' \
  --data-binary @change.diff \
  "$BASE/files"
```

Patch rejects binary targets and respects write policy. Use `force=true` only
for intentional policy override.

`*** Delete File` sections use the same safe-delete behavior as `DELETE`. If
references are found without `force=true`, that section is listed under `failed`
and the response includes a `references` table. `*** Move to:` uses IntelliJ move
refactoring; if the target basename changes, the route performs move then rename
refactoring so references can be updated by the IDE.

Example partial failure body:

```text
applied: none
failed:
- src/Main.kt: Patch does not apply
```

## Format And Problem Operations

`PATCH /api/v1/files/{path...}` also accepts patch-like workspace operations.
These are not OpenAI `apply_patch` file edit sections; use them only for IDE
format/problem actions.

```patch
*** Begin Patch
*** Optimize Imports: src/A.kt
*** Reformat File: src/A.kt
*** Cleanup: src/B.kt
*** End Patch
```

For each target file, workspace operations are applied in stable order: `Fix Problem`,
`Cleanup`, `Optimize Imports`, then `Reformat File`. Duplicate operation kinds for
the same file are applied once.

Example response:

```text
applied:
- optimize-imports src/A.kt
- reformat src/A.kt
- cleanup src/B.kt
```

Problem fixes use `problemFix=true`. If the requested fix cannot be selected or
applied, the route returns `409 Conflict` without modifying the file.

## Edit Session Workflow

1. Read the file or files with `GET ...?content=true`.
2. Apply `PATCH`, `PUT`, `POST`, or `DELETE`.
3. Verify IDE-visible REST state with `GET ...?content=true`, `meta=true`, or `exists=true`.
4. PATCH saves successful Document updates to disk.
