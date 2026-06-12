# Search

Load `negotiation-and-discovery.md` first if `BASE`, `PROJECT_KEY`, or `ROOT_ID` are not known.

## Search Route

```text
GET /api/v1/projects/{projectKey}/search/text/{rootId}/{relativePath...}
```

`rootId` and the optional tailcard path select the search root directory. An empty tailcard
searches the entire root.

## Query Parameters

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `query` | string | required | Search text or regex pattern. |
| `regex` | boolean | `false` | Interpret `query` as a regular expression. |
| `caseSensitive` | boolean | `true` | Case-sensitive matching. |
| `wholeWord` | boolean | `false` | Match whole words only. |
| `context` | string list | `string,comment,other` | Comma-separated syntactic contexts to search in. |
| `fileFilter` | string | none | Glob pattern for file filtering (e.g. `**/*.kt`). |
| `limit` | integer | `100` | Maximum hits to return. |

### context

Comma-separated list of where to search: `string` (inside string literals), `comment` (inside
comments), `other` (code outside strings and comments). Default searches everywhere.
Individual values or combinations map to IntelliJ `SearchContext`:

| context value | Behaviour |
|---------------|-----------|
| `string,comment,other` or absent | Search everywhere |
| `string` | Only in string literals |
| `comment` | Only in comments |
| `other` | Only in code (not strings or comments) |
| `string,other` | Not in comments |
| `comment,other` | Not in strings |

`string,comment` (not in code) is not supported and returns 400.

## Examples

```bash
# Search entire root for a class name
curl -i "$BASE/projects/$PROJECT_KEY/search/text/$ROOT_ID?query=ApplicationCall&fileFilter=**/*.kt&limit=50"

# Search a subdirectory with regex, only in code
curl -i "$BASE/projects/$PROJECT_KEY/search/text/$ROOT_ID/src/main?query=fun\\s+\\w+\\(&regex=true&context=other"

# Find TODO in comments only
curl -i "$BASE/projects/$PROJECT_KEY/search/text/$ROOT_ID?query=TODO&context=comment"
```

## Response

Default text format returns `file:line:column` headers with matched lines. Use
`Accept: application/json` for structured hits with `filePath`, `lineNumber`, `column`,
`lineText`, `matchedText`, `startOffset`, `endOffset`, and `occurrenceId`.
