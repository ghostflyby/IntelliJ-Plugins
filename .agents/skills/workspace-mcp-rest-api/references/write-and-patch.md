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
```

Use `DELETE` for files or empty directories:

```bash
curl -i -X DELETE \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/notes/today.txt"
```

Example body:

```text
true
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

Codex patch format is auto-detected when the body starts with `*** `:

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

Example partial failure body:

```text
applied: none
failed:
- src/Main.kt: Patch does not apply
```

## Edit Session Workflow

1. Read the file or files with `GET ...?content=true`.
2. Apply `PATCH`, `PUT`, `POST`, or `DELETE`.
3. Verify IDE-visible REST state with `GET ...?content=true`, `meta=true`, or `exists=true`.
4. PATCH saves successful Document updates to disk.
