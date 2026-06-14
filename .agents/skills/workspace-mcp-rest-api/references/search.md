# Search

Load `negotiation-and-discovery.md` first if `BASE` or `SESSION_ID` are not known.

## Text Search Route

```text
GET /api/v1/search/text/{path...}
X-Ghostflyby-Workspace-Session-Id: <sessionId>
```

The optional tailcard path selects the search root directory. It can be relative
to the session `pathPrefix` or a URL-encoded full VFS URL.

## Query Parameters

| Param           | Type        | Default                | Description                                |
|-----------------|-------------|------------------------|--------------------------------------------|
| `query`         | string      | required               | Search text or regex pattern.              |
| `regex`         | boolean     | `false`                | Interpret `query` as a regular expression. |
| `caseSensitive` | boolean     | `true`                 | Case-sensitive matching.                   |
| `wholeWord`     | boolean     | `false`                | Match whole words only.                    |
| `context`       | string list | `string,comment,other` | Syntactic contexts to search in.           |
| `fileFilter`    | string      | none                   | Glob pattern for file filtering.           |
| `limit`         | integer     | `100`                  | Maximum hits to return.                    |

## Examples

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/text?query=ApplicationCall&fileFilter=**/*.kt&limit=50"

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/text/src/main?query=fun\\s+\\w+\\(&regex=true&context=other"
```

Example body:

```markdown
---
query: ApplicationCall
regex: false
caseSensitive: true
wholeWord: false
fileFilter: **/*.kt
limit: 50
truncated: false
hitCount: 1
---
## Hits
plugins/WorkspaceMcpTools/src/main/kotlin/dev/ghostflyby/mcp/rest/FileRoutes.kt:73:14
  get<Api.FilesEntry.File> { resource ->
  match: Api.FilesEntry.File
  occurrenceId: 7f3a0d8c21a4b912
```

## Symbol Search Route

```text
GET /api/v1/search/symbols
X-Ghostflyby-Workspace-Session-Id: <sessionId>
```

Symbol search uses the current session project and IntelliJ symbol indexes. It does not take a path tailcard.

## Symbol Query Parameters

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `query` | string | required | Symbol name pattern. |
| `libraries` | boolean | `false` | Include project libraries and dependency symbols. |
| `kind` | string | none | Optional returned-kind filter: `class`, `method`, `field`, `symbol`, or `unknown`. |
| `limit` | integer | `50` | Maximum symbols to return, capped at `200`. |
| `timeoutMillis` | integer | `20000` | Search timeout in milliseconds. |

Examples:

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/symbols?query=RestSessionService&limit=20"

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/symbols?query=route&kind=symbol&libraries=false"
```

Example body:

```markdown
---
query: "RestSessionService"
libraries: false
limit: 20
timeoutMillis: 20000
count: 1
truncated: false
timedOut: false
---
## Symbols
| name | kind | path | line | qualifiedName |
| --- | --- | --- | ---: | --- |
| RestSessionService | symbol | plugins/WorkspaceMcpTools/src/main/kotlin/dev/ghostflyby/mcp/rest/RestSessionService.kt | 43 | dev.ghostflyby.mcp.rest.RestSessionService |
```
