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

**Status:** Implemented after this change.

A PATCH request may return HTTP 200 with `applied` status without the change reaching the disk.
The in-memory VFS Document is updated (visible in the IDE editor and re-readable via GET), but
`git diff` and direct filesystem reads show the original content.

- **Root cause:** The PATCH route handler modifies the in-memory Document via `WriteCommandAction`
  but never calls `FileDocumentManager.saveDocument()` to flush the Document to the underlying
  `VirtualFile`.
- **Fix:** `FilePatchRoutes` now calls `FileDocumentManager.saveDocument(document)` after a
  successful Document update path.

F1 explains Document/disk divergence after an apparently successful PATCH. Path validation and
context matching failures still require separate handling. In the observed single-file sequence,
one PATCH failed because its context no longer matched the target, while a later PATCH appeared
successful against the in-memory Document and left the on-disk file unchanged. Fixing F1 keeps the
IDE-visible Document and disk state consistent for successful PATCH updates, but it does not remove
the need for clearer path rules or better context-mismatch diagnostics.

### F2. PATCH Single-File Target Should Ignore Section Path

**Status:** Implemented after this change.

When the PATCH URL targets a specific file (not a directory), the Codex patch format's
`*** Update File: <path>` must exactly match the URL-relative path. Mismatches produce:

```
Section targets 'RestResources.kt' but target is 'plugins/WorkspaceMcpTools/.../RestResources.kt'
```

If the target is already a single file, requiring a matching section path is redundant.

- **Fix:** When the PATCH URL resolves to a file (not a directory), ignore or normalize the
  section path from the patch body. Apply all hunks to the URL target file regardless of whether
  the patch section names only the basename or the full root-relative path.
- **Caution:** Section paths are meaningful when the target is a directory (they select which
  child file to patch). The fix should only apply to file-targeted PATCH URLs.

### F3. `No valid hunks` Error Lacks Debug Context

Both Codex and Git diff formats produce `No valid hunks` or `Patch does not apply` when the
context lines in the patch body do not match the current file content. The error message does not
indicate whether the failure is due to a path mismatch, whitespace differences, or stale context.

- **Potential fix:** Include the resolved target path, the failing hunk index, and a preview of
  nearby lines from the file in the error response so the caller can adjust the patch.
- **Exploration:** Consider whether the error should distinguish between "file not found" (path
  issue), "context mismatch" (content diff), "unrecognized patch format" (parse/detection
  issue), and "section path does not match URL" (structural).

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

**Status:** Implemented after this change.

`?structure=true` now returns a declaration tree (classes, functions, properties) with optional
source positions. This makes it a navigable overview: callers can use a declaration's line range
to request only the matching source text.

- **Change:** Add `startLine` and `endLine` to `StructureElement`. The
  structure tree then enables a precise peek-read workflow:
  1. `?structure=true` → see declarations with line ranges
  2. `?startLine=N&endLine=M` → read exactly that declaration's source
- **Implementation:** Line numbers are computed from structure view backing PSI/navigation ranges
  when available. Nodes that cannot be located keep `null` line fields. A lightweight text
  declaration fallback is used when no IDE structure builder is available.
- **Serialization:** The Markdown rendering should show line numbers inline, e.g.
  `Foo (class) [25-82]`.

### G5. Line Range / Peek Read (`?startLine=N&endLine=M`)

**Status:** Implemented after this change.

Paired with G4, this adds a targeted content read:

- `?startLine=N&endLine=M` — read exactly lines N through M (inclusive)
- `?startLine=N&maxLines=M` — read M lines starting from line N
- `?aroundLine=N&radius=M` — read lines `N-M` through `N+M` (centered)

When no range is specified, behaviour is unchanged (full content). When range parameters are
specified, they request ranged content and trim that content response rather than replacing the
whole response strategy. Content-only responses stay raw text; compound responses still include
explicitly requested `meta`, `exists`, and `structure` views through the normal Markdown/JSON
wrapper, with the `content` field/body limited to the requested range.

Invalid combinations or invalid values return `400 Bad Request`. Directory and binary targets
also return `400 Bad Request` for ranged content reads.

- **Use case:** Agent reads structure → sees `Foo (class) [25-82]` → requests
  `?startLine=25&endLine=82` to read exactly that class body.

---

## Skill Doc Corrections

### S1. Agent Skill Should Note Post-Mutation Verification Layers

The `workspace-mcp-rest-api` skill should distinguish REST-visible state from disk-visible state.
F1 is fixed for successful PATCH Document updates, so REST GET and direct filesystem reads should
agree after PATCH. Still, callers that need persistence guarantees should verify with direct
filesystem or git reads as an independent check.

### S2. Skill Improvements

Based on the session's experience, the following improvements to the skill documentation would
help future agents use the API more effectively:

1. **Add `?structure=true` output sample** — show what a declaration tree looks like so agents
   immediately recognize it as a "file overview" tool rather than skimming over it.

2. **Add compound response Markdown sample** — show the actual Markdown output of
   `?meta=true&content=true` so agents don't guess about frontmatter vs body layout.

3. **Add exploration workflow guide** — a recommended sequence for understanding an unfamiliar
   file: `?structure=true` → targeted content read via `?startLine=N&endLine=M` → `?content=true` only when
   full text is still needed.

4. **Mention verification layers** — after PATCH, REST GET verifies IDE-visible state; callers
   that need persisted state can additionally verify via git/filesystem too.

5. **Add Codex patch section path rules** — document that file-targeted PATCH ignores body paths,
   while directory-targeted PATCH still uses body paths to select child files.

- **Status:** These workflow and sample notes have been added to the local skill references. The
  API behaviour changes for F2 and G4/G5 are implemented after this change.

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

**Skill status:** A quick-decision table has been added to `write-and-patch.md` before the
format examples.

### P1. File Exploration Workflow (Structure → Content)

The single biggest workflow gap. I never used `?structure=true` during the session, even though
it was exactly what I needed. The skill lists `structure` as a read flag but gives no guidance
on when to use it.

```text
When exploring an unfamiliar file:

1. Quick overview:  GET ?meta=true           # size, type, encoding
2. Declaration map: GET ?structure=true      # classes, functions, properties
3. Targeted content read: GET ?startLine=N&endLine=M
4. Full read:       GET ?content=true        # only when needed
```

Each step is a decision point — after `?structure=true` the agent may already have enough
context. This prevents reading the entire file when only structure is needed.

### P2. Glob → Targeted Read Pipeline

Glob discovers files; the natural next step is reading the most relevant ones. The skill treats
glob and file read as separate endpoints, but agents should chain them:

```text
1. Glob:        GET .../glob/ROOT?glob=**/*.kt      # find files
2. Prioritize:  select target files from results
3. Peek:        GET .../roots/ROOT/target.kt?meta=true&structure=true
4. Read:        GET .../roots/ROOT/target.kt          # or ?content=true
```

### P3. Edit Session Workflow (Read → Patch → Verify)

The current skill has separate checklists for read and mutation. What's missing is an **edit
session** sequence connecting them:

```text
1. Read the file(s):    GET .../path/to/file.kt
2. Apply the patch:     PATCH .../path/to/file.kt  (or directory for multi-file)
3. Verify the result:   GET .../path/to/file.kt?content=true
4. Verify persistence:  optional git/filesystem read when needed
5. If patch failed:     GET (re-read), adjust patch context, retry
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

After any mutation (PUT/POST/PATCH/DELETE), verify through the surface that matters for the next
step. REST GET verifies the IDE-visible VFS/Document view. For successful PATCH Document updates,
F1 now saves the Document, so disk state should match as well. Direct filesystem or `git diff`
verification remains useful when persistence is important to the user or the next workflow step.

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

| Type | ID | Area      | Issue                                         | Status                            |
|------|----|-----------|-----------------------------------------------|-----------------------------------|
| Fix  | F1 | PATCH     | In-memory Document not flushed to disk         | Implemented after this change     |
| Fix  | F2 | PATCH     | Single-file target should ignore section path  | Implemented after this change     |
| Fix  | F3 | PATCH     | `No valid hunks` lacks debug context           | Design needed for error format    |
| Feat | G1 | PATCH     | No `dry-run` validation                        | Implementation straightforward    |
| Feat | G2 | Test      | Glob params need manual `Parameters.build`     | Documented; no code change needed |
| Feat | G3 | Discovery | Unknown project key may return 200             | Needs investigation               |
| Feat | G4 | Structure | Declaration tree lacks line numbers            | Implemented after this change     |
| Feat | G5 | Read      | No line range / peek read                      | Implemented after this change     |
| Doc  | S1 | Skill     | Verification layers not clearly documented     | Updated in skill guidance         |
| Doc  | S2 | Skill     | Missing samples and workflow guide             | Added to skill references         |
