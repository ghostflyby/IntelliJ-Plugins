# REST Path-Prefix Session API

Status: Implemented.

The REST API uses a short-lived session to bind file operations to a path prefix.
After a session is created, requests send the session id in
`X-Ghostflyby-Workspace-Session-Id`. File routes accept either paths relative to
the session prefix or URL-encoded full VFS URLs.

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

Agent-facing file operation routes use `{path...}` so callers can pass a
relative path or a URL-encoded full VFS URL:

| Operation                                            | Route                                          |
|------------------------------------------------------|------------------------------------------------|
| Read file, directory, metadata, structure, existence | `GET /api/v1/files/{path...}`                  |
| Create or replace text                               | `PUT /api/v1/files/{path...}`                  |
| Create only, or create directory with empty body     | `POST /api/v1/files/{path...}`                 |
| Delete file or empty directory                       | `DELETE /api/v1/files/{path...}`               |
| Apply Codex or Git patch                             | `PATCH /api/v1/files/{path...}`                |
| Glob under a directory                               | `GET /api/v1/glob/{path...}?glob=PATTERN`      |
| Text search under a directory                        | `GET /api/v1/search/text/{path...}?query=TEXT` |
| File search under the session prefix                 | `GET /api/v1/search/files?query=NAME`          |
| Symbol search in the session project                 | `GET /api/v1/search/symbols?query=NAME`        |
| Goto/usages/documentation                            | `POST /api/v1/navigation/{path...}`            |

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
3. Resolve relative `{path...}` under the stored path prefix; resolve full VFS
   URLs directly for file reads.
4. Reject relative paths outside the session prefix or inferred root.
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
  "$BASE/files/file%3A%2F%2F%2FUsers%2Fghostflyby%2Frepos%2Flearn%2FIntelliJ-Plugins%2FREADME.md"

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
