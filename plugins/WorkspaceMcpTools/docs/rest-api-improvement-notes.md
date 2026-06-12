---
date: 2026-06-12
---
# WorkspaceMcpTools REST API — Usage Observations & Improvement Ideas

**Context:** Self-hosted feature implementation (glob `limit` parameter) using the REST API for
both reading and editing source files. All issues were discovered during a single development
session interacting with the IDE instance that hosts the plugin itself.

---

## Fix: Bugs and Reliability Issues

### F1. PATCH Changes Not Flushed to Disk

A PATCH request may return HTTP 200 with `applied` status without the change reaching the disk.
The in-memory VFS Document is updated (visible in the IDE editor and re-readable via GET), but
`git diff` and direct filesystem reads show the original content.

- **Root cause:** The PATCH route handler modifies the in-memory Document via `WriteCommandAction`
  but never calls `FileDocumentManager.saveDocument()` to flush the Document to the underlying
  `VirtualFile`.
- **Fix:** Add `FileDocumentManager.saveDocument(document)` after applying each successful hunk,
  or at least once before the response is sent.

None of the other PATCH or edit issues in this document are related to F1. The single-file patch
sequence that produced misleading results (PATCH #2 failed with `Patch does not apply`, PATCH #3
returned `applied` but disk was unchanged) was a context mismatch — the second patch deleted an
import that never existed on disk because the first patch to that area had already failed. The
`applied` response was based on a fuzzy match in the in-memory Document, which then diverged from
the on-disk state. Fixing F1 prevents this divergence by keeping Document and disk consistent.

### F2. PATCH Single-File Target Should Ignore Section Path

When the PATCH URL targets a specific file (not a directory), the Codex patch format's
`*** Update File: <path>` must exactly match the URL-relative path. Mismatches produce:

```
Section targets 'RestResources.kt' but target is 'plugins/WorkspaceMcpTools/.../RestResources.kt'
```

If the target is already a single file, requiring a matching section path is redundant.

- **Fix:** When the PATCH URL resolves to a file (not a directory), ignore or default the section
  path from the patch body. Apply all hunks to the target file regardless of what the section
  path says. The Git diff format does not need this change since `--- a/...` / `+++ b/...` are
  inherent to the format.
- **Caution:** Section paths are meaningful when the target is a directory (they select which
  child file to patch). The fix should only apply to file-targeted PATCH URLs.

### F3. `No valid hunks` Error Lacks Debug Context

Both Codex and Git diff formats produce `No valid hunks` or `Patch does not apply` when the
context lines in the patch body do not match the current file content. The error message does not
indicate whether the failure is due to a path mismatch, whitespace differences, or stale context.

- **Potential fix:** Include the resolved target path and a preview of nearby lines from the
  file in the error response so the caller can adjust the patch.
- **Exploration:** Consider whether the error should distinguish between "file not found" (path
  issue), "context mismatch" (content diff), and "section path does not match URL" (structural).

---

## Feat: Missing Features

### G1. PATCH `dry-run` Query Parameter

Currently, there is no way to validate whether a patch will apply before executing it. Callers
must send a real PATCH request and inspect the response for failures.

- **Suggested shape:** `?dryRun=true` on PATCH requests. When true, parse the patch, check all
  hunks against the file content, and return the same response shape with `applied`/`failed`
  lists — but do not modify any files.
- **Implementation:** Extract the patch application logic into a preview step that returns the
  parsed result without writing.

### G2. Glob `limit` Query Param Requires Manual `Parameters.build` in Tests

Ktor Resources `href()` does not serialize parent resource constructor parameters (`glob`,
`limit` on `GlobEntry`) into the URL query string. Test helpers must manually append them in
`Parameters.build`. The Ktor route handler does parse them correctly from query params, so
runtime behaviour is correct.

- **Status:** Documented limitation, not a bug. The test `globPathUrl` helper needs to stay in
  sync with `GlobEntry`'s constructor parameters.

### G3. `GET /projects/{projectKey}` for Unknown Key Returns 200

Briefly observed that `GET /projects/missing` returned 200 with the current project's info
instead of 404. The source code in `ProjectRoutes.kt` does handle
`WorkspaceProjectResolution.Unresolved` by returning `HttpStatusCode.NotFound`, so this
suggests the resolver may have silently fallen back to the frontmost/current project.

- **Exploration:** Check whether `WorkspaceProjectResolver.resolve(projectKey = "missing")`
  falls back to the active project when the key is not found. If so, the route should add
  an explicit key-match check or the resolver should not fall back.

### G4. Structure Tree Should Include Line Numbers

The current `?structure=true` response returns a declaration tree (classes, functions, properties)
but without source positions. This makes it a read-only overview: you can see what exists but
cannot navigate from a declaration to its source text.

- **Suggested change:** Add `startLine` (and optionally `endLine`) to `StructureElement`. The
  structure tree then enables a precise peek-read workflow:
  1. `?structure=true` → see declarations with line ranges
  2. `?startLine=N&endLine=M` → read exactly that declaration's source
- **Implementation:** `StructureElement` is already `@Serializable`; add two optional `Int` fields.
  The PSI collect step already visits each element — line numbers are available from the PSI
  element's text range.
- **Serialization:** The Markdown rendering should show line numbers inline, e.g.
  `Foo (class) [25-82]`.

### G5. Line Range / Peek Read (`?startLine=N&endLine=M`)

Already implemented in both `RestResources.kt` (query params on `Root`, `Root.File`, `Vfs`) and
`FileRoutes.kt` (`FileLineRange` model with three modes: Lines, MaxLines, Around). The
implementation is solid but was not discovered during the session because the skill documentation
mentions none of these parameters. Update the skill docs to list `?startLine=N&endLine=M`,
`?startLine=N&maxLines=M`, and `?aroundLine=N&radius=M`.

---

## Route Design Improvements

### R1. Separate File Operations from Root Metadata

**Problem:** The `/roots/{rootId}` path conflates two distinct concerns — root metadata (listing
root info) and file operations (reading/writing files under the root). This forces `Root` to
carry every file-related query parameter just because `Root.File` nests under it.

**Current state:**

| Route                        | Purpose         | Carries                                                     |
|------------------------------|-----------------|-------------------------------------------------------------|
| `GET /roots/{rootId}`        | Root metadata   | meta, content, structure, force, line range... (irrelevant) |
| `GET /roots/{rootId}/{path}` | File operations | meta, content, structure, force, line range...              |

**Proposed state:**

| Route                           | Purpose           | Carries                                     |
|---------------------------------|-------------------|---------------------------------------------|
| `GET /roots/{rootId}`           | Root metadata     | None (follows `/glob/` pattern)             |
| `GET /files/{rootId}`           | Directory listing | meta                                        |
| `GET /files/{rootId}/{path...}` | File operations   | meta, content, structure, line range, force |

This mirrors the existing `/glob/{rootId}` design: one path prefix per responsibility.

**RestResources.kt before (simplified):**

```kotlin
public class Root(
    public val parent: Project,
    public override val rootId: String,
    public val meta: Boolean = false,
    public val content: Boolean = false,
    public val exists: Boolean = false,
    public val structure: Boolean = false,
    public val force: Boolean = false,
    public val startLine: Int? = null,   // irrelevant for root metadata
    public val endLine: Int? = null,     // irrelevant for root metadata
    ...
) : ProjectProvider by parent, RootProvider {
    public class File(
        // duplicates all params above
        public val parent: Root,
        public val relativePath: List<String> = emptyList(),
        public val meta: Boolean = false,
        ...
    )
}
```

**RestResources.kt after (simplified):**

```kotlin
@Resource("/roots/{rootId}")
public class Root(
    public val parent: Project,
    public override val rootId: String,
) : ProjectProvider by parent, RootProvider

@Resource("/files/{rootId}")
public class FilesEntry(
    public val parent: Project,
    public override val rootId: String,
    public val meta: Boolean = false,
    public val content: Boolean = false,
    public val exists: Boolean = false,
    public val structure: Boolean = false,
    public val force: Boolean = false,
    public val startLine: Int? = null,
    public val endLine: Int? = null,
    public val maxLines: Int? = null,
    public val aroundLine: Int? = null,
    public val radius: Int? = null,
) : ProjectProvider by parent, RootProvider {
    @Resource("/{relativePath...}")
    public class File(
        public val parent: FilesEntry,
        public val relativePath: List<String> = emptyList(),
    )
}
```

**Impact:**

- **Breaking change:** all file URLs move from `/roots/` to `/files/`
- `Root` no longer carries file query parameters — eliminates the root cause of R2/R3
- `FilesEntry.File` inherits all params via `parent` — no duplication needed in constructor
- Test helpers must update from `/roots/` to `/files/` path construction
- Route handler `RespProjectRootFile()` parameter type changes from `Api.Project.Root` to `Api.Project.FilesEntry`

### R2. Extract `FileQuery` Interface to Eliminate Parameter Duplication

**Problem:** Before R1, `Root`, `Root.File`, and `Vfs` each declare identical blocks of
`meta`/`content`/`exists`/`structure`/`force`/`startLine`/... — 3 classes × 10 params.

**After R1**, only `FilesEntry` and `Vfs` share these params. A `FileQuery` interface with
Ktor Resources-compatible delegation would reduce this to one declaration:

```kotlin
interface FileQuery {
    val meta: Boolean
    val content: Boolean
    val exists: Boolean
    val structure: Boolean
    val force: Boolean
    val startLine: Int?
    val endLine: Int?
    val maxLines: Int?
    val aroundLine: Int?
    val radius: Int?
}

data class FileQueryParams(
    override val meta: Boolean = false,
    override val content: Boolean = false,
    override val exists: Boolean = false,
    override val structure: Boolean = false,
    override val force: Boolean = false,
    override val startLine: Int? = null,
    override val endLine: Int? = null,
    override val maxLines: Int? = null,
    override val aroundLine: Int? = null,
    override val radius: Int? = null,
) : FileQuery
```

Then `FilesEntry` becomes:

```kotlin
public class FilesEntry(
    public val parent: Project,
    public override val rootId: String,
    override val meta: Boolean = false,        // from FileQuery
    override val content: Boolean = false,
    ...  // all params declared once
) : ProjectProvider by parent, RootProvider, FileQuery {
```

The `lineRangeQuery()` helper in `FileRoutes.kt` moves to an extension on `FileQuery`:

```kotlin
fun FileQuery.toLineRangeQuery(): LineRangeQuery =
    LineRangeQuery(startLine, endLine, maxLines, aroundLine, radius)
```

### R3. Clarify PATCH Route Target Semantics

The current skill doc says "the target may be a file or directory." When the target is a directory,
the section path resolves under that directory. But the current `filePatchRoutes()` handler
wraps everything through `patch<Api.Project.Root.File>` which always has a tailcard path — so
directory targets aren't actually used.

**Proposal:** Add an explicit directory-level PATCH route alongside the file-level one:

```kotlin
@Resource("/files/{rootId}")          // PATCH a directory root (section paths resolve under it)
public class FilesEntry(...)

@Resource("/files/{rootId}/{relativePath...}")  // PATCH a specific file (section path ignored)
public class File(...)
```

The directory-level PATCH enables multi-file patches in one request (e.g. `PATCH /files/ROOT_ID`
with a Git diff containing 3 files). The file-level PATCH auto-ignores the section path (F2).

### R4. Planning for Read/Write Concern Separation

Current: `force` (write concern) appears alongside `meta`/`content`/`structure` (read concerns)
on every file resource. A `/files/` route reform (R1) creates a natural point to split:

```kotlin
interface ReadQuery {
    val meta: Boolean
    val content: Boolean
    val exists: Boolean
    val structure: Boolean
    val startLine: Int?
    val endLine: Int?
    val maxLines: Int?
    val aroundLine: Int?
    val radius: Int?
}

interface WriteQuery {
    val force: Boolean
}
```

`FilesEntry` implements both. `Vfs` (read) implements `ReadQuery` only; `VfsWrite` (new)
implements `WriteQuery`. This is a follow-up after R1+R2 are stable.

---

## Skill Doc Corrections

### S1. Agent Skill Should Note VFS Read Inconsistency

The `workspace-mcp-rest-api` skill reference `references/negotiation-and-discovery.md` documents
GET responses, but does not mention that reading back patched content via GET may return the
in-memory Document state rather than the on-disk state. Callers relying on `git diff` or direct
file reads after a PATCH should be aware of this discrepancy.

### S2. Skill Improvements (Plan)

Based on the session's experience, the following improvements to the skill documentation would
help future agents use the API more effectively:

1. **Add `?structure=true` output sample** — show what a declaration tree looks like so agents
   immediately recognize it as a "file overview" tool rather than skimming over it.

2. **Add compound response Markdown sample** — show the actual Markdown output of
   `?meta=true&content=true` so agents don't guess about frontmatter vs body layout.

3. **Add exploration workflow guide** — a recommended sequence for understanding an unfamiliar
   file: `?structure=true` → `?content=true` (or range reads once G4/G5 exist).

4. **Mention VFS/disk discrepancy** — after PATCH, GET may return in-memory state; callers that
   need disk-consistent reads should be aware (cross-ref F1).

5. **Add Codex patch section path rules** — document that the section path must match the URL
   path for file targets, or consider fixing F2 first and then documenting the simplified rule.

---

## Skill Improvements: Cross-Endpoint Collaboration Patterns

### P0. Patch Format Selection Guide

During the session I tried both formats. Each has strengths the skill should make explicit:

| Criterion | Codex Patch | Git Diff (`text/x-patch`) |
|-----------|-------------|---------------------------|
| Single file target | Simpler, fewer boilerplate lines | Diff headers add noise |
| Multi-file (directory target) | Must know each section path | Handles this naturally |
| Whitespace sensitivity | Context lines are fragile | `git diff` output matches exactly |
| Retry after failure | Unclear why it failed | Same problem |

**Rule of thumb for agents:** Prefer Git diff when the patch is generated programmatically
(e.g., after reading a file and computing diffs). Prefer Codex patch for simple single-hunk
edits where you know the exact context lines.

**Add to the skill:** A quick-decision table in `write-and-patch.md` before the format examples.

### P1. File Exploration Workflow (Structure → Content)

The single biggest workflow gap. I never used `?structure=true` during the session, even though
it was exactly what I needed. The skill lists `structure` as a read flag but gives no guidance
on when to use it.

```text
When exploring an unfamiliar file:

1. Quick overview:  GET ?meta=true           # size, type, encoding
2. Declaration map: GET ?structure=true      # classes, functions, properties
3. Targeted read:   GET ?content=true        # read the full file
```

Each step is a decision point — after `?structure=true` the agent may already have enough
context. This prevents reading the entire file when only structure is needed.

### P2. Glob → Targeted Read Pipeline

Glob discovers files; the natural next step is reading the most relevant ones. The skill treats
glob and file read as separate endpoints, but agents should chain them:

```text
1. Glob:        GET .../glob/ROOT?glob=**/*.kt      # find files
2. Prioritize:  select target files from results
3. Peek:        GET .../files/ROOT/target.kt?meta=true&structure=true
4. Read:        GET .../files/ROOT/target.kt          # or ?content=true
```

### P3. Edit Session Workflow (Read → Patch → Verify)

The current skill has separate checklists for read and mutation. What's missing is an **edit
session** sequence connecting them:

```text
1. Read the file(s):    GET .../path/to/file.kt
2. Apply the patch:     PATCH .../path/to/file.kt  (or directory for multi-file)
3. Verify the result:   GET .../path/to/file.kt?content=true
4. If patch failed:     GET (re-read), adjust patch context, retry
```

Step 4 is critical. When `No valid hunks` occurs, the most likely cause is stale context
(the file changed between the read and the patch). **Always re-read the file before retrying.**

### P4. Error Recovery Patterns

The skill lists HTTP error statuses but doesn't say what to do after each:

| HTTP Status | Meaning | Recovery |
|-------------|---------|----------|
| `400` (glob) | Invalid pattern | Fix pattern and retry |
| `400` (patch) | Patch parse error | Check patch format detection |
| `403` | Policy rejection | Check `force=true` or file classification |
| `404` | Not found | Verify project key, root ID, file path |
| `415` | Binary restriction | Cannot write binary via text endpoints |
| `200` with `failed` list | Context mismatch | Re-read file, regenerate patch |

### P5. VFS State Awareness After Write

After any mutation (PUT/POST/PATCH/DELETE), the in-memory Document state and the on-disk state
may diverge until the Document is saved. The fix is F1. Until then, the divergence is normal
IDE behaviour — the Document is the authoritative state for PSI/VFS operations.

**Recommended practice for agents:** Accept the in-memory state as the source of truth for the
current session. Only verify via `git diff` if the user explicitly asks about persisted state.

### P6. Content Negotiation Switching Strategy

The skill says "omit Accept for exploration, use JSON for structured consumers." During my
session I stayed in Markdown even when JSON would have been better. The skill should guide:

| Task | Recommended Accept | Why |
|------|-------------------|-----|
| Human/agent exploration | omit (Markdown) | Most readable |
| Read file content | omit (raw text) | No frontmatter |
| Compound read for agent | omit (Markdown) | Code fences separate fields |
| Machine parsing | `application/json` | `jq` or JSON parser ready |
| Verify after write | omit (raw text) | Direct byte comparison |
| Error diagnosis | `text/plain` | Simplest fallback |

### P7. Multi-File Edit Ordering

When changes span multiple files, the order of edits matters for patch context freshness:

1. **Edit dependency leaves first** — edit B before A if A depends on B. This way A's patch
   context is based on the post-edit B.
2. **Re-read before re-patching the same file** — if a file needs two edit rounds, re-read it
   between patches. The second PATCH's context lines must match the post-first-PATCH state.
3. **New files first, edits second** — PUT/POST new files before PATCHing existing ones. Avoids
   patches referencing files that don't exist yet.
4. **Verify critical files after the full batch** — after all edits, read back the most complex
   changed file to confirm the cumulative result.

---

## Summary

| Type | ID | Area      | Issue                                         | Status                                |
|------|----|-----------|-----------------------------------------------|---------------------------------------|
| Fix  | F1 | PATCH     | In-memory Document not flushed to disk        | Root cause clear; fix known           |
| Fix  | F2 | PATCH     | Single-file target should ignore section path | Fix clear; low risk                   |
| Fix  | F3 | PATCH     | `No valid hunks` lacks debug context          | Design needed for error format        |
| Feat | G1 | PATCH     | No `dry-run` validation                       | Implementation straightforward        |
| Feat | G2 | Test      | Glob params need manual `Parameters.build`    | Documented; no code change needed     |
| Feat | G3 | Discovery | Unknown project key may return 200            | Needs investigation                   |
| Feat | G4 | Structure | Declaration tree lacks line numbers           | Small PSI change; high value          |
| Feat | G5 | Read      | Line range peek read undocumented in skill    | Already implemented; update docs      |
| Ref  | R1 | Route     | `/roots/` → `/files/` for file operations     | Breaking; clean semantics             |
| Ref  | R2 | Route     | Extract FileQuery interface                   | Low effort; eliminates 2x duplication |
| Ref  | R3 | PATCH     | Directory-level PATCH for multi-file          | Follows R1 naturally                  |
| Ref  | R4 | Route     | Read/Write concern separation                 | Defer after R1+R2; cleanup            |
| Doc  | S1 | Skill     | VFS read/write inconsistency not documented   | Minor clarification                   |
| Doc  | S2 | Skill     | Missing samples and workflow guide            | Low effort; high impact               |
