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

- `meta=true`
- `content=true`
- `exists=true`
- `structure=true`

Do not rely on bare query flags such as `?meta`.

## Content

File content is the default when no read flag is provided:

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?content=true"
```

Binary content-only reads return direct bytes with the detected media type.

## Metadata

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?meta=true"

curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  -H 'Accept: application/json' \
  "$BASE/files/src/Main.kt?meta=true"
```

## Existence

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?exists=true"
```

`exists=true` returns direct `text/plain` `true` or `false`.

## Structure

Use `structure=true` as a lightweight file overview before reading a large file.
Returned declarations include `startLine` and `endLine` when available.

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?structure=true"
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

## Compound Reads

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/files/src/Main.kt?meta=true&content=true&structure=true&exists=true"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  -H 'Accept: application/json' \
  "$BASE/files/src/Main.kt?meta=true&content=true"
```

Compound responses can include `content`, `contentFormat`, `meta`, `exists`,
and `structure`.

## Glob Route

```text
GET /api/v1/glob/{relativePath...}?glob=PATTERN&limit=N
```

At least one `glob` query value is required. Multiple patterns are allowed and
merged.

```bash
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/glob?glob=**/*.kt&limit=50"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/glob?glob=**/*.kt&glob=**/*.kts"
curl -i -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  "$BASE/glob/src?glob=**/*Test.kt"
```

Default Markdown/plain fallback renders a prefix block. JSON returns a list of
relative paths.

## Exploration Workflow

1. Create a session for the narrowest useful `pathPrefix`.
2. Discover files with glob, usually with `limit=N`.
3. Read `meta=true` or `structure=true` for likely targets.
4. Use line ranges from `structure=true` for targeted reads.
