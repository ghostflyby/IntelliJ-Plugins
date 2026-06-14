# Read And Glob

Load `negotiation-and-discovery.md` first if `BASE` or `SESSION_ID` are not known.

All file routes require:

```text
X-Ghostflyby-Workspace-Session-Id: <sessionId>
```

## File Read Route

```text
GET /api/v1/files/{path...}
```

The path can be relative to the session `pathPrefix` or a URL-encoded full VFS
URL. Use explicit boolean query values:

When an API response includes an encoded VFS URL field such as `encodedUrl`, use
that value as a single `{path...}` segment for follow-up `/files` requests.

- `meta=true`
- `content=true`
- `exists=true`
- `structure=true`
- `problems=true`

Do not rely on bare query flags such as `?meta`.

## Content

File content is the default when no read flag is provided:

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?content=true"
```

Text content returns the file body directly:

```kotlin
package demo

fun main() = println("hello")
```

Binary content-only reads return direct bytes with the detected media type.

## Metadata

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?meta=true"
```

Example body:

```markdown
---
name: Main.kt
url: file:///workspace/src/Main.kt
encodedUrl: file%3A%2F%2F%2Fworkspace%2Fsrc%2FMain.kt
path: /workspace/src/Main.kt
isDirectory: false
fileType: Kotlin
isBinary: false
classification: WORKSPACE_TEXT
reason: Workspace text file
---
```

## Existence

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?exists=true"
```

`exists=true` returns direct `text/plain` `true` or `false`.

Example body:

```text
true
```

## Structure

Use `structure=true` as a lightweight file overview before reading a large file.
Returned declarations include `startLine` and `endLine` when available.

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?structure=true"
```

Example body:

```markdown
## Structure
MainKt (file) [1-8]
	main (fun) [3-5]
```

## Problems

Use `problems=true` for a Markdown problem report. The public-only v1 reports
PSI syntax errors and IDE file problem state; full inspection descriptors and
quick-fix lists are intentionally not exposed through internal/ex APIs.

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Broken.xml?problems=true&minSeverity=ERROR"
```

Example body:

```markdown
---
path: "src/Broken.xml"
minSeverity: "ERROR"
count: 1
truncated: false
timedOut: false
---
## Problems
| severity | file | line | inspection | message | fixes |
| --- | --- | ---: | --- | --- | --- |
| ERROR | src/Broken.xml | 1 | SyntaxError | Element root is not closed |  |
```

## Range And Peek Reads

Range parameters trim the returned content and count as a content request by
themselves. They do not suppress explicitly requested `meta=true`,
`exists=true`, or `structure=true`.

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?startLine=25&endLine=82"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?aroundLine=40&radius=5"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?startLine=25&maxLines=20"
```

Line numbers are 1-based. Invalid range combinations return `400 Bad Request`.
Ranged content reads are text-file only.

Example body:

```kotlin
fun selectedFunction() {
    println("only this range")
}
```

## Compound Reads

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?meta=true&content=true&structure=true&exists=true"
```

Compound responses can include `content`, `contentFormat`, `meta`, `exists`,
and `structure`.

Example body:

````markdown
---
meta:
  name: Main.kt
  classification: WORKSPACE_TEXT
exists: true
---

```kotlin
fun main() = println("hello")
```

## Structure
main (fun) [1-1]
````

## Glob Route

```text
GET /api/v1/glob/{path...}?glob=PATTERN&limit=N
```

At least one `glob` query value is required. Multiple patterns are allowed and
merged. The path can be relative to the session `pathPrefix` or a URL-encoded
full VFS URL.

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/glob?glob=**/*.kt&limit=50"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/glob?glob=**/*.kt&glob=**/*.kts"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/glob/src?glob=**/*Test.kt"
```

Default Markdown/plain fallback renders a prefix block.

Example body:

```text
@
RootFile.kt
@ nested/
NestedFile.kt
```

## Exploration Workflow

1. Create a session for the narrowest useful `pathPrefix`.
2. Discover files with glob, usually with `limit=N`.
3. Read `meta=true` or `structure=true` for likely targets.
4. Use line ranges from `structure=true` for targeted reads.
