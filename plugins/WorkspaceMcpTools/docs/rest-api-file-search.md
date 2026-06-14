# File Search REST API

## Route

```text
GET /api/v1/search/files
X-Ghostflyby-Workspace-Session-Id: <sessionId>
```

The session identifies the IntelliJ project and the path prefix used as the search root. File search does not take a
path tailcard and does not accept project/root identifiers.

## Query Parameters

| Param           | Type    | Default  | Description                               |
|-----------------|---------|----------|-------------------------------------------|
| `query`         | string  | required | File name pattern.                        |
| `limit`         | integer | `50`     | Maximum files to return, capped at `200`. |
| `timeoutMillis` | integer | `20000`  | Search timeout in milliseconds.           |

The implementation uses IntelliJ's Goto File indexes for fuzzy matching. Results are restricted to files under the
session `pathPrefix`; directories are omitted.

## Examples

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/files?query=RestSessionService&limit=20"

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/files?query=restsession"
```

## Response

Default text format returns frontmatter with query/options/count/truncation metadata followed by a Markdown table:

```markdown
---
query: "RestSessionService"
pathPrefix: "/Users/ghostflyby/repos/learn/IntelliJ-Plugins/plugins/WorkspaceMcpTools"
limit: 20
timeoutMillis: 20000
count: 1
truncated: false
timedOut: false
---
## Files
| name | path | encodedFileUrl | fileType | score |
| --- | --- | --- | --- | ---: |
| RestSessionService.kt | src/main/kotlin/dev/ghostflyby/mcp/rest/RestSessionService.kt |  | Kotlin | 0 |
```

Structured responses contain the same route-local DTO fields: `name`, `fileUrl`, `encodedFileUrl`, `filePath`,
`relativePath`, `line`, `column`, `fileType`, and `score`. Use `encodedFileUrl` as a single `{path...}` segment for
follow-up `/files` requests against a full VFS URL. Markdown rows show compact relative paths for project files;
external or JAR VFS rows show the full `fileUrl` and `encodedFileUrl`.
