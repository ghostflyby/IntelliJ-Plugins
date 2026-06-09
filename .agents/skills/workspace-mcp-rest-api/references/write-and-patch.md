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

The target may be a file or directory. If the target is a directory, patch section paths are resolved under that
directory. If the target is a file, patch sections must target that same relative path.

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

Patch responses are JSON:

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

## Mutation Checklist

1. Confirm the user asked for mutation.
2. Use project-root scoped routes when possible.
3. Use `curl -i` or equivalent header capture.
4. Do not use `force=true` by default.
5. Check status and body before assuming the write succeeded.
