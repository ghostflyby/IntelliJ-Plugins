# Negotiation And Discovery

## Base URL

Default:

```bash
BASE=http://127.0.0.1:63341/api/v1
```

Default port is 63341. If in use, server scans 10 ports forward and persists.

## Headers And Content Negotiation

Examples use `curl`, but any HTTP client can call the API.

Almost always inspect response headers together with the body. Status and
`Content-Type` are part of the API result because many endpoints negotiate
Markdown/plain output.

Response negotiation: omit `Accept` in normal agent or human use; the default
body is Markdown/plain, while structured clients can request another supported
content type explicitly when they need machine JSON.

```bash
curl -i "$BASE/server/info"
```

Example body:

```markdown
---
instanceKey: workspace-abc123
version: 1.2.3
---
```

## Session Setup

File operations require a session. Create one for the narrowest useful path
prefix:

```bash
SESSION_ID=$(curl -sS -X POST "$BASE/sessions" \
  -H 'Content-Type: application/json' \
  -d '{"pathPrefix": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"}' \
  | awk '/^sessionId:/ {print $2; exit}')
```

Example body:

```markdown
---
sessionId: s_8f4d0f8f2d6f4e0aa0d3c7e3c1a2b9c0
pathPrefix: /Users/ghostflyby/repos/learn/IntelliJ-Plugins
project:
  name: IntelliJ-Plugins
  basePath: /Users/ghostflyby/repos/learn/IntelliJ-Plugins
exposedRoot:
  path: /Users/ghostflyby/repos/learn/IntelliJ-Plugins
expiresAt: 2026-06-13T12:30:00Z
---
```

Then pass the vendor-scoped session header on every file operation:

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/README.md?meta=true"
```

Example body:

```markdown
---
name: README.md
url: file:///Users/ghostflyby/repos/learn/IntelliJ-Plugins/README.md
path: /Users/ghostflyby/repos/learn/IntelliJ-Plugins/README.md
isDirectory: false
fileType: Markdown
isBinary: false
classification: WORKSPACE_TEXT
reason: Workspace text file
---
```

## Diagnostic Discovery

These endpoints remain useful for diagnostics and project metadata, but they are
not file path locators:

```bash
curl -i "$BASE/server/info"
curl -i "$BASE/projects"
curl -i "$BASE/projects/$PROJECT_KEY"
curl -i "$BASE/projects/$PROJECT_KEY/roots"
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID"
```

Example project list body:

```markdown
| projectKey | name | basePath |
| --- | --- | --- |
| intellij-plugins | IntelliJ-Plugins | /Users/ghostflyby/repos/learn/IntelliJ-Plugins |
```

Example roots body:

```markdown
| id | displayName | kind | readable | writable | url |
| --- | --- | --- | --- | --- | --- |
| workspace-intellij-plugins | /Users/ghostflyby/repos/learn/IntelliJ-Plugins | workspace | true | true | file:///Users/ghostflyby/repos/learn/IntelliJ-Plugins |
```

## Error Handling

Prefer reading both headers and body:

```bash
curl -i "$BASE/projects/missing"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" "$BASE/files/missing.txt"
```

Example body:

```text
File not found
```

Typical errors include:

- `400 Bad Request` for malformed glob, range, search, or patch input
- `403 Forbidden` for policy failures or paths outside the session prefix
- `404 Not Found` for missing sessions, files, roots, or hidden/excluded targets
- `409 Conflict` for ambiguous session creation, create conflicts, and non-empty directory delete
- `415 Unsupported Media Type` for unsupported binary mutations

## Checklist

1. Set `BASE`.
2. Create `SESSION_ID` with `POST /sessions`.
3. Use `curl -i` or equivalent header capture.
4. Send `X-Ghostflyby-Workspace-Session-Id` for file operations.
5. Prefer the default Markdown/plain response during exploration.
