---
name: workspace-mcp-rest-api
description: Use when calling, testing, debugging, documenting, or generating client requests for the WorkspaceMcpTools REST API exposed by the IntelliJ plugin at /api/v1. Covers endpoint discovery, session headers, curl examples, content negotiation, file/glob/read/write/patch routes, and safe AI usage rules.
---

# WorkspaceMcpTools REST Development Workflow

Use this skill when developing or reviewing code through the WorkspaceMcpTools REST API exposed by the IntelliJ
plugin. The REST API is the working surface; the goal is to follow an IDE-aware development loop, not just issue
isolated HTTP calls.

Default base URL:

```bash
BASE=http://127.0.0.1:63341/api/v1
```

The port can be overridden when the IDE starts with `-Ddev.ghostflyby.mcp.workspace.port=<port>`.

## Core Contract

- Create a session before file operations with `POST /sessions` and a `pathPrefix`, then send
  `X-Ghostflyby-Workspace-Session-Id` on every `/files`, `/glob`, `/search/text`, `/search/files`,
  `/search/symbols`, `/inspections`, and `/navigation` request.
- Prefer the narrowest suitable `pathPrefix` for the task. A plugin, module, or source directory keeps search,
  navigation, and patch targets small.
- The default response is Markdown/plain optimized for agent reading. Use JSON only when structured parsing is needed.
- Inspect response headers together with the body during exploration. At minimum check status, `Content-Type`,
  redirects/errors, and negotiated format.
- Use explicit boolean query values. Write `?meta=true`, `?content=true`, `?exists=true`, `?structure=true`, and
  `?force=true`; do not rely on bare presence-only flags such as `?meta`.
- URL-encode path segments and query values. Full VFS URLs returned as `encodedFileUrl`, `encodedUrl`, or `encodedUri`
  are already route-ready single `{path...}` segments.
- Read-only exploration uses `GET`. Do not use `PUT`, `POST`, `PATCH`, or `DELETE` unless the user explicitly asked for
  mutation.
- For writes and patches, treat `force=true` as an explicit override for protected text paths such as ignored files. Do
  not add it by default.
- `/files` PATCH accepts the OpenAI Responses API `apply_patch` format when the body starts with `*** `. Load
  `references/apply-patch-format.md` only when the exact format is unknown or a patch fails because of formatting.

## Development Loop

1. Establish the session and scope.
    - Create a session rooted at the smallest directory that still contains the work.
    - If the task may cross modules, start at the repository or plugin root, then narrow with glob/search.

2. Discover repository shape before reading large files.
    - Use glob with small limits to find candidate files.
    - Read `meta=true` for file identity and policy when the file kind is uncertain.
    - Read `structure=true` before full content for source files. Use returned line numbers for targeted peeks.

3. Search before opening broad content.
    - Use `/search/files` for fuzzy file names.
    - Use `/search/text/{path...}` for literals, API names, configuration keys, TODOs, and error strings.
    - Use `/search/symbols` for declarations, including dependencies when `libraries=true` is needed.
    - For library or JAR results, use the returned full `fileUrl`/`encodedFileUrl`; do not turn it into a fake relative
      path.

4. Read narrowly.
    - Prefer `aroundLine=N&radius=M`, `startLine=N&endLine=M`, or `startLine=N&maxLines=M`.
    - Escalate to full `content=true` only when the local context is insufficient.
    - For unfamiliar files, inspect `structure=true` again after major edits to keep navigation anchored.

5. Use IDE semantic navigation instead of guessing.
    - From a call site to a declaration: read the call-site range, send a `*** Goto:` navigation hunk, then read the
      returned target with a line range.
    - To understand behavior: use `*** Documentation:` on the symbol at the call site or declaration.
    - Before deleting, renaming, moving, or changing a public API, run `*** Usages:` from the declaration or a precise
      symbol occurrence and inspect all returned call sites that are in scope.
    - If usages are ambiguous, inspect nearby code and repeat with a tighter hunk.

6. Edit only after the target and references are understood.
    - Prefer `/files` PATCH with the OpenAI Responses API `apply_patch` format for text edits.
    - Keep patches small, based on current content, and scoped to the requested behavior.
    - Use `PUT`/`POST`/`DELETE` only when the requested operation is naturally create/replace/create-only/delete.
    - For multi-file edits, use one coherent `/files` PATCH rooted at the relevant directory when possible.

7. Verify after every meaningful edit.
    - Re-read changed files or targeted ranges to confirm IDE-visible state.
    - Use `problems=true` or `/inspections/{path...}` when syntax/problem feedback is needed.
    - Re-run relevant search/navigation checks when references should have changed.
    - Run targeted tests or build tasks appropriate to the change. If tests cannot run, report why.
    - If an edit should remove a symbol or call pattern, use text search and/or usages to confirm no unintended
      references remain.

## Common Workflows

### Find a File and Peek

1. `/search/files?query=Name`
2. `/files/{path}?structure=true`
3. `/files/{path}?aroundLine=N&radius=20`

### From Call Site to Declaration

1. Read the call site with a small range.
2. `POST /navigation/{path}` with:

```text
*** Goto:
@@
- service.doWork(arg)
+ service.XXXXXX(arg)
```

3. Read the returned declaration target with `aroundLine`.
4. Use `Documentation` if the declaration contract is not obvious.

### Safely Remove or Change an API

1. Find the declaration with symbol search or goto.
2. Run `Usages` on the declaration.
3. Inspect each usage category before editing.
4. Patch declaration and call sites together when possible.
5. Re-run usages and text search for the old symbol/name.
6. Run focused tests.

### Inspect Dependency or Library Code

1. Use `/search/symbols?query=Name&libraries=true`.
2. Use returned `encodedFileUrl` for follow-up `/files/{path...}` reads.
3. Treat library and SDK files as read-only context unless the user explicitly asks for diagnostics about them.

### Check Problems and Format

1. Read problems with `/files/{path}?problems=true&minSeverity=ERROR`.
2. Run multi-file problem checks with `/inspections/{path}` and `*** Inspect File:` operations.
3. Run deterministic cleanup with `/files` PATCH operations such as `*** Optimize Imports:` and `*** Reformat File:`.
4. Treat `problemFix=true` unsupported responses as public API limitations unless a newer public API is confirmed.

## Reference Files

Load only the reference needed for the current step:

- `references/negotiation-and-discovery.md`: base URL, session creation, headers, response negotiation, server info,
  and common error handling.
- `references/read-and-glob.md`: file reads, metadata/content/exists/structure flags, compound responses, and glob
  queries.
- `references/search.md`: text search via FindModel, file and symbol search via IDE indexes, context filtering, file
  glob, occurrence IDs for PATCH follow-up.
- `references/navigation.md`: goto declaration, find usages, documentation lookup via Codex patch hunk selection.
- `references/write-and-patch.md`: PUT/POST/DELETE/PATCH, force semantics, patch formats, write responses, and mutation.
- `references/inspection-and-format.md`: `problems=true`, `/inspections`, format/import workspace operations,
  and public-only quick-fix/cleanup limits.
- `references/apply-patch-format.md`: OpenAI Responses API `apply_patch` body format for `/files` PATCH. Load only
  when the exact format is unknown or a patch fails because of formatting.
