# REST Path-Prefix Session API

Status: Implemented.

The REST API uses a short-lived session to bind file operations to a path prefix.
After a session is created, file routes use only relative paths; the session id is
sent in `X-Ghostflyby-Workspace-Session-Id`.

## Lifecycle

Create a session:

```text
POST /api/v1/sessions
Content-Type: application/json
```

```json
{
  "pathPrefix": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"
}
```

The server normalizes `pathPrefix`, infers the owning open IntelliJ project and
exposed root, and returns:

```json
{
  "sessionId": "s_8f4d0f8f2d6f4e0aa0d3c7e3c1a2b9c0",
  "pathPrefix": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins",
  "project": {
    "name": "IntelliJ-Plugins",
    "basePath": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"
  },
  "exposedRoot": {
    "path": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"
  },
  "expiresAt": "2026-06-13T12:30:00Z"
}
```

Destroy a session:

```text
DELETE /api/v1/sessions/{sessionId}
```

Sessions are in-memory, idle-expiring, and refreshed on successful use. Unknown,
deleted, or expired sessions return `404`.

## Session Header

Every file operation requires:

```text
X-Ghostflyby-Workspace-Session-Id: s_8f4d0f8f2d6f4e0aa0d3c7e3c1a2b9c0
```

`projectKey` and `rootId` are not accepted in file operation paths.

## File Routes

All file route paths are relative to the session path prefix:

| Operation | Route |
|-----------|-------|
| Read file, directory, metadata, structure, existence | `GET /api/v1/files/{relativePath...}` |
| Create or replace text | `PUT /api/v1/files/{relativePath...}` |
| Create only, or create directory with empty body | `POST /api/v1/files/{relativePath...}` |
| Delete file or empty directory | `DELETE /api/v1/files/{relativePath...}` |
| Apply Codex or Git patch | `PATCH /api/v1/files/{relativePath...}` |
| Glob under a directory | `GET /api/v1/glob/{relativePath...}?glob=PATTERN` |
| Text search under a directory | `GET /api/v1/search/text/{relativePath...}?query=TEXT` |
| Goto/usages/documentation | `POST /api/v1/navigation/{relativePath...}` |

Project metadata routes remain available for diagnostics:

```text
GET /api/v1/projects
GET /api/v1/projects/{projectKey}
GET /api/v1/projects/{projectKey}/roots
GET /api/v1/projects/{projectKey}/roots/{rootId}
```

They are not file path locators.

Removed agent-facing file routes:

```text
/api/v1/projects/{projectKey}/files/{rootId}/{relativePath...}
/api/v1/projects/{projectKey}/glob/{rootId}/{relativePath...}
/api/v1/projects/{projectKey}/search/text/{rootId}/{relativePath...}
/api/v1/projects/{projectKey}/navigation/{rootId}/{relativePath...}
/api/v1/session/files/{relativePath...}
/api/v1/session/glob/{relativePath...}
/api/v1/session/search/text/{relativePath...}
/api/v1/session/navigation/{relativePath...}
/api/v1/vfs/{rawVfsUrl...}
```

## Resolution Rules

Create-time resolution:

1. Normalize `pathPrefix` to an absolute real path.
2. Match open IntelliJ projects and exposed workspace roots.
3. Prefer the most specific owning root.
4. Return `404` when no open project owns the prefix.
5. Return `409` when equally specific projects/roots make the prefix ambiguous.

Request-time validation:

1. Validate `X-Ghostflyby-Workspace-Session-Id`.
2. Refresh session expiry.
3. Resolve `{relativePath...}` under the stored path prefix.
4. Reject paths outside the session prefix or inferred root.
5. Apply the same read/write policy as existing workspace file access.

## Examples

```bash
BASE=http://127.0.0.1:63341/api/v1

SESSION_ID=$(curl -sS -X POST "$BASE/sessions" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"pathPrefix": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"}' \
  | jq -r .sessionId)

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/plugins/WorkspaceMcpTools/docs/rest-api-session.md?structure=true"

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/glob/plugins/WorkspaceMcpTools/src?glob=**/*.kt&limit=50"

curl -i -X PATCH \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  -H 'Content-Type: text/x-patch' \
  --data-binary @change.patch \
  "$BASE/files/plugins/WorkspaceMcpTools/docs/rest-api-session.md"
```

Omitted `Accept` remains the Markdown/plain optimized reading path. Use
`Accept: application/json` for structured clients.
