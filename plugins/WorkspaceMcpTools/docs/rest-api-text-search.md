# Text Search REST API Design

## Route

```text
GET /api/v1/projects/{projectKey}/search/text/{rootId}/{relativePath...}
```

Follows the existing `/files/` and `/glob/` path prefix convention. `rootId` and the optional
tailcard path select the search root directory. All search parameters are query parameters.

---

## Query parameters

| Param           | Type        | Default                | Description                                                             |
|-----------------|-------------|------------------------|-------------------------------------------------------------------------|
| `query`         | string      | **required**           | Search text or regex pattern.                                           |
| `regex`         | boolean     | `false`                | Interpret `query` as a regular expression.                              |
| `caseSensitive` | boolean     | `true`                 | Case-sensitive matching.                                                |
| `wholeWord`     | boolean     | `false`                | Match whole words only.                                                 |
| `context`       | string list | `string,comment,other` | Comma-separated list of contexts to search in.                          |
| `fileFilter`    | string      | none                   | Glob pattern for file filtering under the search root (e.g. `**/*.kt`). |
| `limit`         | integer     | `100`                  | Maximum hits to return.                                                 |

### context

Comma-separated list of context types to include in the search. Each value represents a
syntactic context within source files:

| Value     | Meaning                            |
|-----------|------------------------------------|
| `string`  | Inside string literals             |
| `comment` | Inside comments (line, block, doc) |
| `other`   | Code outside strings and comments  |

The default `string,comment,other` searches everywhere. Omitting the parameter is equivalent.

Mapping to `FindModel.SearchContext`:

| `context` value                  | Internal enum                         |
|----------------------------------|---------------------------------------|
| default / `string,comment,other` | `ANY`                                 |
| `string`                         | `IN_STRING_LITERALS`                  |
| `comment`                        | `IN_COMMENTS`                         |
| `other`                          | `EXCEPT_COMMENTS_AND_STRING_LITERALS` |
| `string,other`                   | `EXCEPT_COMMENTS`                     |
| `comment,other`                  | `EXCEPT_STRING_LITERALS`              |
| `string,comment`                 | 400 — not supported by IntelliJ       |

The combination `string,comment` (search strings and comments but not code) has no corresponding
`FindModel.SearchContext` value and returns 400 with a descriptive error.

---

## Examples

```bash
# Search entire root for a class name
curl -i "$BASE/projects/$PROJECT_KEY/search/text/$ROOT_ID?query=ApplicationCall&caseSensitive=true&fileFilter=**/*.kt&limit=50"

# Search a subdirectory with regex, only in code (not strings or comments)
curl -i "$BASE/projects/$PROJECT_KEY/search/text/$ROOT_ID/src/main?query=fun\\s+\\w+\\(&regex=true&context=other"

# Find TODO in comments only
curl -i "$BASE/projects/$PROJECT_KEY/search/text/$ROOT_ID?query=TODO&context=comment"
```

---

## Response

### Text (default, omit `Accept`)

```
# search/text
# query: ApplicationCall
# root: workspace-44-main/src/main/kotlin
# fileFilter: **/*.kt
# limit: 50
# context: string,comment,other
# results: 2
kotlin/com/example/App.kt:12:17
fun handle(call: ApplicationCall) {
kotlin/com/example/App.kt:45:21
println(call)
```

- Paths are relative to the search root directory.
- Each hit: `file:line:column` header followed by the full matched line.
- When results are capped at `limit`, `# truncated: true` appears in the header.
- Lines longer than 240 chars are truncated around the match with `...`.
- Column is 1-based UTF-16 code unit offset (IntelliJ Document model).

### JSON (`Accept: application/json`)

```json
{
  "query": "ApplicationCall",
  "regex": false,
  "caseSensitive": true,
  "wholeWord": false,
  "context": [
    "string",
    "comment",
    "other"
  ],
  "fileFilter": "**/*.kt",
  "limit": 50,
  "truncated": false,
  "hits": [
    {
      "filePath": "kotlin/com/example/App.kt",
      "lineNumber": 12,
      "column": 17,
      "lineText": "fun handle(call: ApplicationCall)",
      "matchedText": "ApplicationCall",
      "startOffset": 150,
      "endOffset": 165,
      "occurrenceId": "a1b2c3d4e5f6a7b8"
    }
  ]
}
```

- `lineNumber`: 1-based.
- `column`: 1-based UTF-16 code unit within the line.
- `startOffset` / `endOffset`: 0-based document offsets, for subsequent PATCH targeting.
- `occurrenceId`: stable hash of (file URL, range, query, mode).

---

## Search scope

The search root is determined by `rootId` + optional relative path (same as `/files/` and
`/glob/`). `fileFilter` further narrows candidates via `WorkspaceGlobPattern`.

The backend translates this into existing infrastructure:

1. `rootId` + `relativePath` → resolve directory `VirtualFile` →
   `GlobalSearchScopesCore.directoryScope(project, dir, withSubdirs=true)`
2. `fileFilter` → compile via `WorkspaceGlobPattern`. Pre-filter via `FilenameIndex` or `FileTypeIndex` when the glob
   yields a known extension.
3. Execute via `FindInProjectUtil.findUsages` with a `FindModel` configured from all query parameters.

---

## Empty results

```text
# search/text
# query: nothing_matches_this
# root: workspace-44-main
# results: 0
```

JSON: `{"query": "...", "truncated": false, "hits": []}`.

---

## Errors

| Condition                      | Status | Body                                                                            |
|--------------------------------|--------|---------------------------------------------------------------------------------|
| `query` empty or blank         | 400    | `{"error": "query must not be blank."}`                                         |
| Invalid `fileFilter` glob      | 400    | `{"error": "Invalid glob pattern: ..."}`                                        |
| Invalid regex                  | 400    | `{"error": "Invalid regex pattern: ..."}`                                       |
| Unknown value in `context`     | 400    | `{"error": "Unknown context value: ..."}`                                       |
| `context=string,comment`       | 400    | `{"error": "context 'string,comment' is not supported by IntelliJ FindModel."}` |
| `rootId` not found             | 404    | `{"error": "Root not found"}`                                                   |
| Search root is not a directory | 404    | `{"error": "Search root not found: ..."}`                                       |

---

## Implementation

Reuse the existing `ScopeTextSearchTools` backend (`FindInProjectUtil.findUsages` +
`buildFindModel` + `toRawOccurrence`). The REST handler differs only in scope construction:
build a `GlobalSearchScope` from `rootId` + relative path instead of resolving a full
`ScopeProgramDescriptor`, and maps the `context` list to `FindModel.SearchContext`.

All fields in `FindModel` that are unset by this API (e.g. `isMultipleFiles`, `isProjectScope`,
`isWithSubdirectories`) are initialised to sensible defaults for a multi-file search.
