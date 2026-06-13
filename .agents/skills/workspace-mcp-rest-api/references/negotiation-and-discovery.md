# Negotiation And Discovery

## Base URL

Default:

```bash
BASE=http://127.0.0.1:63341/api/v1
```

The port can be overridden when the IDE starts with
`-Ddev.ghostflyby.mcp.workspace.port=<port>`.

## Headers And Content Negotiation

Examples use `curl`, but any HTTP client can call the API.

Almost always inspect response headers together with the body. Status and
`Content-Type` are part of the API result because many endpoints negotiate
Markdown/plain/JSON output.

For normal agent or human exploration, omit `Accept`:

```bash
curl -i "$BASE/server/info"
```

Use JSON only when a structured consumer needs it:

```bash
curl -i -H 'Accept: application/json' "$BASE/projects"
```

Supported negotiated response types include `text/markdown`, `text/x-markdown`,
`text/plain`, and `application/json`.

## Session Setup

File operations require a session. Create one for the narrowest useful path
prefix:

```bash
SESSION_ID=$(curl -sS -X POST "$BASE/sessions" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"pathPrefix": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"}' \
  | jq -r .sessionId)
```

Then pass the vendor-scoped session header on every file operation:

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/README.md?meta=true"
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

## Error Handling

Prefer reading both headers and body:

```bash
curl -i "$BASE/projects/missing"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" "$BASE/files/missing.txt"
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
5. Omit `Accept` unless you need JSON or a specific negotiated format.
