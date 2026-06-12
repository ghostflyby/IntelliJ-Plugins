# Read And Glob

Load `negotiation-and-discovery.md` first if `BASE`, `PROJECT_KEY`, or `ROOT_ID` are not known.

## File Read Routes

Project-root scoped paths:

```text
GET /api/v1/projects/{projectKey}/roots/{rootId}/{relativePath...}
```

Root itself:

```text
GET /api/v1/projects/{projectKey}/roots/{rootId}
```

Raw VFS URL:

```text
GET /api/v1/vfs/{rawVfsUrl...}
```

Use explicit boolean query values:

- `meta=true`
- `content=true`
- `exists=true`
- `structure=true`

Do not rely on bare query flags such as `?meta`.

## Content

File content is the default when no read flag is provided. A plain file GET and `?content=true` return only the file
body, without YAML frontmatter or wrapper text, so they can be piped directly to command-line tools:

```bash
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt"
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?content=true"
```

Binary content-only reads return direct bytes with the detected media type.

## Metadata

Default metadata responses are optimized for direct reading:

```bash
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?meta=true"
```

Use JSON for structured consumption:

```bash
curl -i -H 'Accept: application/json' "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?meta=true"
```

## Existence

```bash
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?exists=true"
```

`exists=true` only returns direct `text/plain` `true` or `false`.

## Structure

Use `structure=true` as a lightweight file overview before reading a large source file. It returns a declaration or
document tree such as classes, functions, properties, or Markdown headings.

```bash
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?structure=true"
curl -i -H 'Accept: application/json' "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?structure=true"
```

Example Markdown/plain output:

```text
## Structure
App (class) [3-18]
	run (function) [6-12]
```

Structure elements include 1-based inclusive `startLine` and `endLine` when the IDE can locate the declaration. Some
fallback elements may still have `null` ranges, but available ranges can be used for targeted follow-up reads.

## Range And Peek Reads

Use range reads after `structure=true` when only a declaration or nearby context is needed. Range parameters trim the
returned content and count as a content request by themselves; they do not suppress explicitly requested `meta=true`,
`exists=true`, or `structure=true` views. Content-only responses still return raw `text/plain` file text, while compound
responses keep the normal Markdown/JSON wrapper and include the ranged text in the `content` field/body.

```bash
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?startLine=25&endLine=82"
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?aroundLine=40&radius=5"
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?startLine=25&maxLines=20"
```

Supported modes are mutually exclusive:

- `startLine=N&endLine=M` reads inclusive lines `N..M`.
- `startLine=N&maxLines=M` reads at most `M` lines from `N`.
- `aroundLine=N&radius=M` reads `N-M..N+M`; `radius=0` returns the single center line.

Line numbers are 1-based. `maxLines` must be positive, `radius` may be zero, and invalid range combinations return
`400 Bad Request`. Ranged content reads are text-file only; directory and binary targets return `400 Bad Request`.

## Compound Reads

Combine flags when one call should return several views:

```bash
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?meta=true&content=true&structure=true&exists=true"
curl -i -H 'Accept: application/json' "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?meta=true&content=true"
```

Compound responses can include `content`, `contentFormat`, `meta`, `exists`, and `structure`. They are wrapped as
Markdown or JSON rather than raw file bodies. Binary content in compound responses is base64 and has `contentFormat` set
to `base64`.

Example Markdown/plain compound shape:

````text
---
meta:
  name: Main.kt
  fileType: Kotlin
  lineCount: 42
exists: null
---

```kt
package demo
```
````

## Raw VFS Reads

```bash
VFS_URL='file:///Users/me/project/src/Main.kt'
curl -i "$BASE/vfs/$VFS_URL?meta=true"
curl -i "$BASE/vfs/$VFS_URL?content=true"
```

URL-encode the VFS URL path component if the client does not do it for you.

## Glob Routes

Glob routes search under a project root and optional relative subdirectory:

```text
GET /api/v1/projects/{projectKey}/glob/{rootId}/{relativePath...}?glob=PATTERN&limit=N
```

At least one `glob` query value is required. Multiple patterns are allowed and merged. Use `limit=N` during exploration
to cap the number of returned paths:

```bash
curl -i "$BASE/projects/$PROJECT_KEY/glob/$ROOT_ID?glob=**/*.kt&limit=50"
curl -i "$BASE/projects/$PROJECT_KEY/glob/$ROOT_ID?glob=**/*.kt&glob=**/*.kts"
curl -i "$BASE/projects/$PROJECT_KEY/glob/$ROOT_ID/src?glob=**/*Test.kt"
```

Use JSON when the caller needs a machine-readable list:

```bash
curl -i -H 'Accept: application/json' "$BASE/projects/$PROJECT_KEY/glob/$ROOT_ID?glob=**/*.kt"
```

Default Markdown/plain fallback renders a prefix block. JSON returns a list of relative paths.

## Exploration Workflow

For unfamiliar code, chain the read endpoints:

1. Discover files with glob, usually with `limit=N`.
2. Read `meta=true` or `structure=true` for the likely target files.
3. Use line ranges from `structure=true` for a targeted content read, or read raw content only for files that still need full
   text.

## Read Checklist

1. Use `curl -i` or equivalent header capture.
2. Omit `Accept` for agent/human reading.
3. Use `Accept: application/json` only for structured consumers.
4. Use explicit boolean query values.
5. Prefer project-root scoped routes when project/root identity is known.
6. Use `structure=true` before full-content reads when a structural overview or line-range peek is enough.
