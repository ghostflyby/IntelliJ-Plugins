# REST Session API Proposal

Status: Draft, not implemented.

This document proposes an additive session facade for the REST API. A session
binds to a normalized path prefix and lets the server infer the owning IntelliJ
project from the open project/content-root model. It should not remove the
existing project/root-scoped routes until the path-prefix contract is
implemented, tested, and explicitly accepted as a breaking migration.

## Motivation

Current project-scoped routes require callers to keep and re-send both
`projectKey` and `rootId`:

```text
/api/v1/projects/{projectKey}/files/{rootId}/{relativePath...}
```

That shape is explicit and stateless, which is good for debugging and fallback
use, but `projectKey` and `rootId` primarily exist to inline a compact path
locator into every URL. They are not a natural state model for agents, which
usually think in terms of a working directory or repository prefix.

The proposed session facade creates a short-lived context once, binds it to an
already-resolved path prefix, and lets later requests use short relative paths.
The server infers the project and exposed root from that prefix. The existing
explicit routes remain the source of truth during migration.

---

## Current Route Baseline

The current REST API is mounted under `/api/v1`.

| Responsibility | Current route |
|----------------|---------------|
| Server info | `GET /api/v1/server/info` |
| Project list | `GET /api/v1/projects` |
| Project detail | `GET /api/v1/projects/{projectKey}` |
| Root list | `GET /api/v1/projects/{projectKey}/roots` |
| Root metadata | `GET /api/v1/projects/{projectKey}/roots/{rootId}` |
| File read/write/patch | `/api/v1/projects/{projectKey}/files/{rootId}/{relativePath...}` |
| Glob | `GET /api/v1/projects/{projectKey}/glob/{rootId}/{relativePath...}` |
| Text search | `GET /api/v1/projects/{projectKey}/search/text/{rootId}/{relativePath...}` |
| Navigation | `POST /api/v1/projects/{projectKey}/navigation/{rootId}/{relativePath...}` |
| Raw VFS read/write | `/api/v1/vfs/{rawVfsUrl...}` |

Query parameters keep the existing response contract: omitted `Accept` remains
the normal Markdown/plain reading path, `application/json` is for structured
consumers, and range parameters trim only the `content` portion when compound
responses also request `meta`, `exists`, or `structure`.

---

## Proposed Session Lifecycle

### Create

```text
POST /api/v1/sessions
```

Body:

```json
{
  "pathPrefix": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"
}
```

`pathPrefix` is the session's primary locator. The server normalizes it and
infers the owning IntelliJ project from open project base paths, content roots,
and exposed roots. `projectKey` and `rootId` are intentionally not required
inputs for the new model.

Response:

```json
{
  "sessionId": "s_8f4d0f8f2d6f4e0aa0d3c7e3c1a2b9c0",
  "pathPrefix": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins",
  "project": {
    "projectKey": "IntelliJ-Plugins-1234abcd",
    "name": "IntelliJ-Plugins",
    "basePath": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"
  },
  "exposedRoot": {
    "rootId": "workspace-IntelliJ-Plugins",
    "path": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"
  },
  "expiresAt": "2026-06-13T12:30:00Z"
}
```

`project.projectKey` and `exposedRoot.rootId` are response metadata for
diagnostics and compatibility with the old route model. They are not required
for subsequent session requests.

### Use

Session facade requests carry `X-Session-Id`:

```text
X-Session-Id: s_8f4d0f8f2d6f4e0aa0d3c7e3c1a2b9c0
```

Path resolution:

- Relative paths are resolved against the session path prefix.
- Absolute paths are allowed only when they are inside the session path prefix.
- Paths outside the session policy boundary return 403 or 404. A session must
  not turn absolute paths into a bypass around existing file access rules.

### Destroy

```text
DELETE /api/v1/sessions/{sessionId}
```

Idle sessions expire after 10 minutes. Expired, unknown, project-closed, or
root-invalid sessions return 404.

---

## Proposed Session Routes

These routes are an additive facade over the existing project/root handlers.
During the additive phase, implementation may internally delegate to the old
handlers by translating the session prefix back into the currently required
`projectKey` and `rootId`. That translation is an implementation detail, not a
caller-visible contract.

| Session route | Existing route it delegates to |
|---------------|--------------------------------|
| `GET /api/v1/session/files/{relativePath...}` | `GET /api/v1/projects/{projectKey}/files/{rootId}/{relativePath...}` |
| `PUT /api/v1/session/files/{relativePath...}` | `PUT /api/v1/projects/{projectKey}/files/{rootId}/{relativePath...}` |
| `POST /api/v1/session/files/{relativePath...}` | `POST /api/v1/projects/{projectKey}/files/{rootId}/{relativePath...}` |
| `DELETE /api/v1/session/files/{relativePath...}` | `DELETE /api/v1/projects/{projectKey}/files/{rootId}/{relativePath...}` |
| `PATCH /api/v1/session/files/{relativePath...}` | `PATCH /api/v1/projects/{projectKey}/files/{rootId}/{relativePath...}` |
| `GET /api/v1/session/glob/{relativePath...}?glob=PATTERN` | `GET /api/v1/projects/{projectKey}/glob/{rootId}/{relativePath...}` |
| `GET /api/v1/session/search/text/{relativePath...}?query=...` | `GET /api/v1/projects/{projectKey}/search/text/{rootId}/{relativePath...}` |
| `POST /api/v1/session/navigation/{relativePath...}` | `POST /api/v1/projects/{projectKey}/navigation/{rootId}/{relativePath...}` |

Unchanged:

- `/api/v1/server/info`
- `/api/v1/projects`
- `/api/v1/projects/{projectKey}`
- `/api/v1/projects/{projectKey}/roots`
- `/api/v1/projects/{projectKey}/roots/{rootId}`
- `/api/v1/vfs/{rawVfsUrl...}`

Dedicated structure routes are not needed. Use the existing read contract with
`?structure=true`:

```text
GET /api/v1/session/files/{relativePath...}?structure=true
```

---

## Resolution Rules

Create-time resolution:

1. Normalize `pathPrefix` to an absolute canonical path.
2. Find open IntelliJ projects whose base path, content roots, or exposed roots
   contain the prefix, or are contained by it.
3. Prefer the most specific match, such as the longest owning content root.
4. If there is no match, return 404.
5. If multiple matches are equally specific, return an ambiguous-target error.
6. Store the normalized path prefix, inferred project identity, inferred exposed
   root, and expiry metadata.

Request-time validation:

1. Validate `X-Session-Id`.
2. Verify the project is still open and not disposed.
3. Verify the stored path prefix is still inside the inferred project/root
   boundary.
4. Resolve the request path under the stored path prefix.
5. Apply the same read/write policy as the existing explicit route.

---

## Examples

```bash
# Create session
curl -i -X POST "$BASE/sessions" \
  -H 'Content-Type: application/json' \
  -d '{"pathPrefix": "/Users/ghostflyby/repos/learn/IntelliJ-Plugins"}'

# Use session - short relative paths
curl -i -H 'X-Session-Id: s_8f4d0f8f2d6f4e0aa0d3c7e3c1a2b9c0' \
  "$BASE/session/files/plugins/WorkspaceMcpTools/docs/README.md"

curl -i -H 'X-Session-Id: s_8f4d0f8f2d6f4e0aa0d3c7e3c1a2b9c0' \
  "$BASE/session/glob/plugins/WorkspaceMcpTools/src?glob=**/*.kt&limit=50"

curl -i -X PATCH -H 'X-Session-Id: s_8f4d0f8f2d6f4e0aa0d3c7e3c1a2b9c0' \
  -H 'Content-Type: text/x-patch' \
  --data-binary @change.patch \
  "$BASE/session/files/plugins/WorkspaceMcpTools/docs/README.md"
```

---

## Lifecycle and Security Notes

- `SessionService` should be an application-level service with platform-managed
  coroutine scope so cleanup is cancelled on plugin unload.
- Session ids must be high-entropy random tokens.
- Session storage is in-memory only; sessions do not survive IDE restart.
- Session records store normalized path prefixes, not caller-supplied raw paths.
- Session creation should refresh expiry on use, or document fixed expiry
  explicitly. The first implementation should prefer idle expiry.
- Deleting a session is idempotent.
- A session must not widen access beyond the exposed root policy already used by
  explicit project/root routes.
- Ambiguous multi-project inference is an error, not an auto-discovery success.

---

## Test-First Implementation Plan

### Phase 0: Documentation

- Keep this document as a draft proposal.
- Record the current explicit route baseline and the proposed additive facade.
- Defer any breaking route removal until after review.

### Phase 1: Contract Tests

- URL/resource generation for `Api.Sessions`, `Api.Session.Files`, `Api.Session.Glob`,
  `Api.Session.SearchText`, and `Api.Session.Navigation`.
- Session creation accepts a path prefix without caller-supplied `projectKey` or
  `rootId`.
- Unknown path prefix, ambiguous path prefix, expired session, and closed
  project all fail predictably.
- Longest/specific content-root match wins when inference has a clear best
  candidate.
- Relative paths resolve under the session path prefix.
- Absolute paths inside the session path prefix work; outside paths are rejected.
- Existing explicit routes keep passing unchanged.

### Phase 2: Additive Implementation

- Add `SessionService` with in-memory path-prefix session records and idle expiry.
- Add `SessionRoutes.kt` for create/delete and session facade endpoints.
- Reuse existing file/glob/search/navigation route logic by extracting shared
  target resolution helpers instead of duplicating endpoint behavior.
- Keep `RestResources.kt` as the central typed resource declaration file.

### Phase 3: Documentation and Skill Updates

- Update REST usage docs only after the session facade exists.
- Keep omitted `Accept` as the default exploration path.
- Add session examples as shortcuts, not replacements for explicit routes.

### Phase 4: Locator Removal Migration

Only after explicit approval:

- Mark old `projectKey`/`rootId` path-locator URLs as deprecated.
- Move agent-facing docs and skill examples to session path-prefix routes.
- Keep compatibility for at least one development window if practical.
- Remove or hide old `projectKey`/`rootId` routes in a separate, reviewable
  commit.
- Remove obsolete route helper/test surfaces that only exist to carry
  `projectKey` and `rootId` inline.
