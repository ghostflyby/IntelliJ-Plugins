---
name: workspace-mcp-rest-api
description: Use in IntelliJ projects with `.idea/` when searching, editing, inspecting, formatting, or exploring workspace and library symbols/text/problems through the IDE.
---

# WorkspaceMcpTools REST Development Workflow

Use this skill when developing or reviewing code through the WorkspaceMcpTools REST API exposed by the IntelliJ
plugin. The REST API is the working surface; the goal is to follow an IDE-aware development loop, not just issue
isolated HTTP calls.

Default base URL:

```bash
BASE=http://127.0.0.1:63441/api/v1
```

Default port is 63441. If already in use, the server scans upward and uses the first available port.

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
    - Create a session rooted at the smallest directory that still contains the work. If the task may cross modules,
      start at the repository or plugin root, then narrow with `/glob` or `/search/*`.

2. Discover and search through the REST API first.
    - Use `/search/files` for fuzzy file names, `/glob` for path patterns, `/search/text/{path...}` for literal or regex
      text search, and `/search/symbols` for declarations.
    - Use `libraries=true` only when dependency or SDK symbols are needed. Follow external/JAR results with their
      `encodedFileUrl`; do not invent filesystem paths for libraries.

3. Read narrowly and keep IDE state visible.
    - Prefer `structure=true`, `aroundLine=N&radius=M`, `startLine=N&endLine=M`, or `startLine=N&maxLines=M`.
    - Use `meta=true` when file identity, policy, writability, or encoded VFS URLs matter.

4. Navigate semantically instead of guessing.
    - Use `*** Goto:`, `*** Documentation:`, and `*** Usages:` through `/navigation/{path}` for declarations,
      contracts, and reference checks.

5. Edit, cleanup, and verify.
    - Use `/files` PATCH for text edits and workspace operations. Use `PUT`/`POST`/`DELETE` only when replacing,
      create-only, or delete is the natural operation.
    - After edits, run IDE operations as needed: `*** Cleanup: path`, `*** Optimize Imports: path`, and
      `*** Reformat File: path`.
    - Re-read changed ranges, check `problems=true` or `/inspections/{path...}`, rerun relevant search/navigation
      checks, then run focused tests or builds.

## Typical Mistakes And Replacements

Avoid shell-first exploration when this skill is active. The REST API has IDE indexes, session scoping, VFS identity,
and dependency/library awareness that shell tools do not.

| Do not do this                          | Use this instead                                                                                         |
|-----------------------------------------|----------------------------------------------------------------------------------------------------------|
| `find path/to/libraries`                | `/search/symbols?query=Name&libraries=true`, then read returned `encodedFileUrl` with `/files/{path...}` |
| `find . -name '*Route*'`                | `/search/files?query=Route&limit=50` or `/glob?glob=**/*Route*.kt`                                       |
| `rg project/directory/`                 | `/search/text/project/directory?query=...&regex=true&fileFilter=**/*.kt` or `regex=false`                |
| `rg 'class Foo'`                        | `/search/symbols?query=Foo` for declarations; `/search/text?query=class%20Foo` only for text shape       |
| Read a whole source file first          | `/files/{path}?structure=true`, then `aroundLine` or `startLine` ranges                                  |
| Turn a JAR/library URL into a fake path | Use the returned `encodedFileUrl` exactly as the `/files/{path...}` route segment                        |
| Run a formatter command blindly         | `/files` PATCH with `*** Cleanup`, `*** Optimize Imports`, and `*** Reformat File`                       |

### Archive And JAR Inspection

When a user forbids `unzip` or `jar tf`, do not use either command for Gradle caches or plugin artifacts. Prefer a ZIP
reader that lists entries without extracting:

```bash
jshell --execution local
```

```java
var zip = new java.util.zip.ZipFile(System.getProperty("user.home") + "/.gradle/some.jar");
zip.

stream().

map(java.util.zip.ZipEntry::getName).

forEach(System.out::println);
zip.

close();
```

For one-off scripted checks, use a runtime ZIP API such as `java.util.zip.ZipFile` or Node packages that read the ZIP
central directory. Keep inspection read-only unless the user explicitly asks to extract or rewrite an artifact.

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
4. Treat `problemFix=true` conflict responses as non-mutating failures unless a concrete fix was applied.

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
  and problem-fix conflict behavior.
- `references/apply-patch-format.md`: OpenAI Responses API `apply_patch` body format for `/files` PATCH. Load only
  when the exact format is unknown or a patch fails because of formatting.
