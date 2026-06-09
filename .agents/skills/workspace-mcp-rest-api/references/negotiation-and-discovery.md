# Negotiation And Discovery

## Base URL

Default:

```bash
BASE=http://127.0.0.1:63341/api/v1
```

The port can be overridden when the IDE starts with `-Ddev.ghostflyby.mcp.workspace.port=<port>`.

## Headers And Content Negotiation

Examples use `curl`, but any HTTP client can call the API.

Almost always inspect response headers together with the body. Status and `Content-Type` are part of the API result
because many endpoints negotiate Markdown/plain/JSON output.

Supported negotiated response types:

- `text/markdown`
- `text/x-markdown`
- `text/plain`
- `application/json`

For normal agent or human exploration, omit `Accept`:

```bash
curl -i "$BASE/server/info"
curl -i "$BASE/projects"
```

`Accept: */*`, `text/plain`, `text/markdown`, and `text/x-markdown` are also valid, but usually not needed:

```bash
curl -i -H 'Accept: */*' "$BASE/server/info"
curl -i -H 'Accept: text/markdown' "$BASE/server/info"
curl -i -H 'Accept: text/plain' "$BASE/projects/missing"
```

Use JSON only when a structured consumer needs it:

```bash
curl -i -H 'Accept: application/json' "$BASE/projects"
curl -sS -H 'Accept: application/json' "$BASE/projects" | jq
```

JSON responses return the payload directly, not a wrapper. JSON is good for machines, but escaping and nested structure
make it less ideal for direct agent reading than the default Markdown/plain responses.

Use `-sS -D -` if you need headers and a script-friendly body:

```bash
curl -sS -D - "$BASE/server/info"
curl -sS -D - -H 'Accept: application/json' "$BASE/projects"
```

## Discovery Flow

1. Server information:

```bash
curl -i "$BASE/server/info"
curl -i -H 'Accept: application/json' "$BASE/server/info"
```

Response includes `instanceKey` and `version`.

2. Open projects:

```bash
curl -i "$BASE/projects"
curl -i -H 'Accept: application/json' "$BASE/projects"
```

Each project entry includes `projectKey`, `name`, and `basePath`.

3. Project detail:

```bash
curl -i "$BASE/projects/$PROJECT_KEY"
curl -i -H 'Accept: application/json' "$BASE/projects/$PROJECT_KEY"
```

4. Project roots:

```bash
curl -i "$BASE/projects/$PROJECT_KEY/roots"
curl -i -H 'Accept: application/json' "$BASE/projects/$PROJECT_KEY/roots"
```

Root entries include `id`, display information, access flags, and URL. Use the root `id` as `ROOT_ID`.

## Error Handling

Prefer reading both headers and body:

```bash
curl -i "$BASE/projects/missing"
curl -i -H 'Accept: text/plain' "$BASE/projects/missing"
curl -i -H 'Accept: application/json' "$BASE/projects/missing"
```

Typical errors include:

- `400 Bad Request` for malformed glob or patch input
- `403 Forbidden` for policy failures
- `404 Not Found` for unresolved project, root, file, or hidden/excluded targets
- `409 Conflict` for create conflicts and non-empty directory delete
- `415 Unsupported Media Type` for unsupported binary mutations

## Discovery Checklist

1. Set `BASE`.
2. Use `curl -i` or equivalent header capture.
3. Discover `projectKey`.
4. Discover `ROOT_ID`.
5. Omit `Accept` unless you need JSON or a specific negotiated format.
