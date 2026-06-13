# Text Search REST API

## Route

```text
GET /api/v1/search/text/{relativePath...}
X-Ghostflyby-Workspace-Session-Id: <sessionId>
```

The session binds the request to a path prefix. The optional tailcard path
selects the search root directory relative to that prefix. An empty tailcard
searches the session prefix itself.

## Query Parameters

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `query` | string | required | Search text or regex pattern. |
| `regex` | boolean | `false` | Interpret `query` as a regular expression. |
| `caseSensitive` | boolean | `true` | Case-sensitive matching. |
| `wholeWord` | boolean | `false` | Match whole words only. |
| `context` | string list | `string,comment,other` | Comma-separated syntactic contexts to search in. |
| `fileFilter` | string | none | Glob pattern for file filtering under the search root. |
| `limit` | integer | `100` | Maximum hits to return. |

`context` supports `string`, `comment`, and `other`. The combination
`string,comment` is not supported by IntelliJ `FindModel` and returns `400`.

## Examples

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/text?query=ApplicationCall&fileFilter=**/*.kt&limit=50"

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/text/src/main?query=fun\\s+\\w+\\(&regex=true&context=other"

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/text?query=TODO&context=comment"
```

## Response

Default text format returns `file:line:column` headers with matched lines. Use
`Accept: application/json` for structured hits with `filePath`, `lineNumber`,
`column`, `lineText`, `matchedText`, `startOffset`, `endOffset`, and
`occurrenceId`.

Paths are relative to the IntelliJ project base path in current responses.
