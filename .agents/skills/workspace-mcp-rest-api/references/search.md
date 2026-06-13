# Search

Load `negotiation-and-discovery.md` first if `BASE` or `SESSION_ID` are not known.

## Search Route

```text
GET /api/v1/search/text/{relativePath...}
X-Ghostflyby-Workspace-Session-Id: <sessionId>
```

The optional tailcard path selects the search root directory relative to the
session `pathPrefix`. An empty tailcard searches the session prefix.

## Query Parameters

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `query` | string | required | Search text or regex pattern. |
| `regex` | boolean | `false` | Interpret `query` as a regular expression. |
| `caseSensitive` | boolean | `true` | Case-sensitive matching. |
| `wholeWord` | boolean | `false` | Match whole words only. |
| `context` | string list | `string,comment,other` | Syntactic contexts to search in. |
| `fileFilter` | string | none | Glob pattern for file filtering. |
| `limit` | integer | `100` | Maximum hits to return. |

## Examples

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/text?query=ApplicationCall&fileFilter=**/*.kt&limit=50"

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/search/text/src/main?query=fun\\s+\\w+\\(&regex=true&context=other"
```

Use `Accept: application/json` for structured hits with `filePath`,
`lineNumber`, `column`, `lineText`, `matchedText`, `startOffset`, `endOffset`,
and `occurrenceId`.
