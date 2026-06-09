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

File content is the default when no read flag is provided:

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

```bash
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?structure=true"
curl -i -H 'Accept: application/json' "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?structure=true"
```

## Compound Reads

Combine flags when one call should return several views:

```bash
curl -i "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?meta=true&content=true&structure=true&exists=true"
curl -i -H 'Accept: application/json' "$BASE/projects/$PROJECT_KEY/roots/$ROOT_ID/src/Main.kt?meta=true&content=true"
```

Compound responses can include `content`, `contentFormat`, `meta`, `exists`, and `structure`. Binary content in compound
responses is base64 and has `contentFormat` set to `base64`.

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
GET /api/v1/projects/{projectKey}/glob/{rootId}/{relativePath...}?glob=PATTERN
```

At least one `glob` query value is required. Multiple patterns are allowed and merged:

```bash
curl -i "$BASE/projects/$PROJECT_KEY/glob/$ROOT_ID?glob=**/*.kt"
curl -i "$BASE/projects/$PROJECT_KEY/glob/$ROOT_ID?glob=**/*.kt&glob=**/*.kts"
curl -i "$BASE/projects/$PROJECT_KEY/glob/$ROOT_ID/src?glob=**/*Test.kt"
```

Use JSON when the caller needs a machine-readable list:

```bash
curl -i -H 'Accept: application/json' "$BASE/projects/$PROJECT_KEY/glob/$ROOT_ID?glob=**/*.kt"
```

Default Markdown/plain fallback renders a prefix block. JSON returns a list of relative paths.

## Read Checklist

1. Use `curl -i` or equivalent header capture.
2. Omit `Accept` for agent/human reading.
3. Use `Accept: application/json` only for structured consumers.
4. Use explicit boolean query values.
5. Prefer project-root scoped routes when project/root identity is known.
