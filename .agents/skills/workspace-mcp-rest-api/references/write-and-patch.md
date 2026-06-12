# Write And Patch

Load `negotiation-and-discovery.md` first if `BASE`, `PROJECT_KEY`, or `ROOT_ID` are not known.

Only mutate through this API when the user clearly asked for mutation. Avoid `force=true` unless requested or clearly
necessary.

For long text bodies, use a heredoc to avoid manual escaping:

```bash
curl -i -X PUT \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary @- \
  "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Demo.kt" <<'EOF'
package demo

class Demo
EOF
```

## Write Routes

Project-root scoped writes:

```text
PUT    /api/v1/projects/{projectKey}/roots/{rootId}/{relativePath...}
POST   /api/v1/projects/{projectKey}/roots/{rootId}/{relativePath...}
DELETE /api/v1/projects/{projectKey}/roots/{rootId}/{relativePath...}
```

Raw VFS writes are also supported:

```text
PUT    /api/v1/vfs/{rawVfsUrl...}
POST   /api/v1/vfs/{rawVfsUrl...}
DELETE /api/v1/vfs/{rawVfsUrl...}
```

Use `PUT` to create or replace text:

```bash
curl -i -X PUT \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary $'package demo\n\nclass Demo\n' \
  "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Demo.kt"
```

Use `POST` to create only when absent. Empty body creates a directory:

```bash
curl -i -X POST \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary 'note' \
  "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/notes/today.txt"

curl -i -X POST \
  --data-binary '' \
  "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/new-directory"
```

Use `DELETE` for files or empty directories:

```bash
curl -i -X DELETE "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/notes/today.txt"
```

## Force

Only pass `force=true` intentionally:

```bash
curl -i -X PUT \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary 'override ignored text' \
  "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/generated.txt?force=true"
```

`force=false` is explicit false and must not bypass policy. Do not rely on bare `?force`.

## Write Responses

- `201 Created` with `{"uri": "..."}`
- `200 OK` with `{"uri": "..."}`
- `409 Conflict` for existing resources or non-empty directories
- `403 Forbidden` for policy failures
- `404 Not Found`
- `415 Unsupported Media Type` for binary write/delete restrictions

Always inspect headers and body:

```bash
curl -i -X DELETE "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/notes/today.txt"
```

## Patch Routes

Patch only project-root scoped paths:

```text
PATCH /api/v1/projects/{projectKey}/roots/{rootId}/{relativePath...}
```

The target may be a file or directory. If the target is a file, patch body paths are ignored or normalized and all
hunks apply to the URL target file. If the target is a directory, patch section paths are resolved under that directory.

Choose patch format by task:

| Task | Prefer | Reason |
|------|--------|--------|
| Small single-file edit | Codex patch | Compact and easy to write by hand |
| Multi-file edit under a directory target | Git diff (`text/x-patch`) | File paths are part of the diff format |
| Programmatically generated patch | Git diff (`text/x-patch`) | Context usually matches exact file output |

For file-targeted PATCH requests, the section path in a Codex patch, or the `---`/`+++` path in a Git patch, does not
need to match the URL target. For directory-targeted PATCH requests, the patch body path still selects the child file
under the directory URL.

Codex patch format is auto-detected when the body starts with `*** `:

```bash
curl -i -X PATCH \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary @change.patch \
  "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt"
```

Git patch format is auto-detected from `diff --git` or `--- `, or explicitly selected with `text/x-patch`:

```bash
curl -i -X PATCH \
  -H 'Content-Type: text/x-patch' \
  --data-binary @change.diff \
  "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID"
```

Patch responses use the usual REST negotiation rules. Omit `Accept` for the default Markdown/frontmatter rendering, or
send `Accept: application/json` for a JSON response:

```json
{
  "applied": [
    {
      "path": "src/Main.kt",
      "operation": "update"
    }
  ],
  "failed": [],
  "error": null
}
```

Patch rejects binary targets and respects write policy. Use `force=true` only for intentional policy override:

```bash
curl -i -X PATCH \
  -H 'Content-Type: text/x-patch' \
  --data-binary @change.diff \
  "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID?force=true"
```

## Patch Recovery

`No valid hunks` and `Patch does not apply` usually mean the patch context no longer matches the current target text.
Before retrying:

1. Re-read the target with `GET ...?content=true`.
2. Regenerate or adjust the patch against the latest text.
3. For directory-targeted PATCH, check that patch section paths name the intended child files.
4. Retry with smaller hunks if the edit touches several distant areas.

If a PATCH response has a non-empty `failed` list, treat the failed entries as unapplied even when the HTTP status is
`200 OK`.

## Edit Session Workflow

1. Read the file or files with `GET ...?content=true`.
2. Apply `PATCH`, `PUT`, `POST`, or `DELETE`.
3. Verify IDE-visible REST state with `GET ...?content=true`, `meta=true`, or `exists=true`.
4. PATCH saves successful Document updates to disk. If persisted state matters, direct filesystem or git verification
   is still useful as an independent check.

## Mutation Checklist

1. Confirm the user asked for mutation.
2. Use project-root scoped routes when possible.
3. Use `curl -i` or equivalent header capture.
4. Do not use `force=true` by default.
5. Check status and body before assuming the write succeeded.
6. Verify the resulting REST-visible content.
7. Verify disk-visible content too when persistence matters.
