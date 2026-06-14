# Symbol Search REST API

## Route

```text
GET /api/v1/search/symbols
X-Ghostflyby-Workspace-Session-Id: <sessionId>
```

The session identifies the IntelliJ project. Symbol search does not take a path
tailcard and does not accept project/root identifiers.

## Query Parameters

| Param           | Type    | Default  | Description                                                                        |
|-----------------|---------|----------|------------------------------------------------------------------------------------|
| `query`         | string  | required | Symbol name pattern.                                                               |
| `libraries`     | boolean | `false`  | Include project libraries and dependency symbols.                                  |
| `kind`          | string  | none     | Optional returned-kind filter: `class`, `method`, `field`, `symbol`, or `unknown`. |
| `limit`         | integer | `50`     | Maximum symbols to return, capped at `200`.                                        |
| `timeoutMillis` | integer | `20000`  | Search timeout in milliseconds.                                                    |

The initial REST implementation uses IntelliJ symbol contributors and reports a
generic `symbol` kind unless the contributor result is missing PSI context.

## Examples

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/symbols?query=RestSessionService&limit=20"

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/symbols?query=route&kind=symbol&libraries=false"
```

## Response

Default text format returns frontmatter with query/options/count/truncation
metadata followed by a Markdown table:

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

Structured responses contain the same route-local DTO fields: `name`,
`qualifiedName`, `fileUrl`, `encodedFileUrl`, `filePath`, `line`, `column`,
`kind`, `language`, and `score`. Use `encodedFileUrl` as a single `{path...}`
segment for follow-up `/files` requests against a full VFS URL.
