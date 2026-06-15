# Workspace Agent Bridge

<!-- Plugin description -->
Workspace Agent Bridge exposes IntelliJ workspace capabilities to local coding agents through a Markdown-first REST API
and a lightweight MCP SDK endpoint. The REST API is the primary agent-facing surface for file reads, edits, search,
navigation, inspections, formatting, cleanup, and refactoring-aware file operations.

## REST API

The REST server is hosted once per IDE application.

- Base URL: `http://127.0.0.1:63341/api/v1`
- Override the port with `-Ddev.ghostflyby.mcp.workspace.port=<port>`.
- Create a workspace session with `POST /sessions` and pass
  `X-Ghostflyby-Workspace-Session-Id` on workspace requests.
- Default responses are Markdown/plain text optimized for agents. Use `Accept: application/json` only for structured
  clients.

Core routes:

| Capability | Route |
| --- | --- |
| Project/session discovery | `GET /projects`, `POST /sessions`, `DELETE /sessions/{sessionId}` |
| File read, metadata, structure, problem view | `GET /files/{path...}` |
| Create or replace text | `PUT /files/{path...}` |
| Create only, or create directory with an empty body | `POST /files/{path...}` |
| Refactoring-aware delete | `DELETE /files/{path...}` |
| Apply OpenAI `apply_patch`, Git patch, format, cleanup, move, and delete operations | `PATCH /files/{path...}` |
| Glob and text search | `GET /glob/{path...}`, `GET /search/text/{path...}` |
| Fuzzy file and symbol search | `GET /search/files`, `GET /search/symbols` |
| Goto, usages, documentation | `POST /navigation/{path...}` |
| Inspection refresh/report | `POST /inspections/{path...}` |

Path inputs use session-relative paths or URL-encoded full IntelliJ VFS URLs. Full VFS URLs are accepted for reads;
write operations remain restricted to project workspace files.

`DELETE /files/{path...}` uses IntelliJ safe-delete behavior for files. If references are found, the default response is
`409 Conflict` with a references table; retry with `force=true` only when deletion is intentional. `PATCH` delete and
move sections use the same PSI/VFS-aware refactoring path, including IntelliJ move and rename refactoring for
`*** Move to:`.

## MCP SDK Endpoint

The Streamable HTTP MCP endpoint remains available at `http://127.0.0.1:63341/mcp` for SDK clients. It is hosted once
per IDE application and resolves project-specific behavior from open IDE projects and request context.

Current SDK feature registration is intentionally small:

- `quality_get_file_problems`
- `quality_reformat_file`
- `quality_optimize_imports_file`
- `quality_fix_file_quick`
- `quality_list_inspection_profiles`
- `quality_code_cleanup_file`
- existing quality scope variants for internal compatibility

The old standalone `scope_*` MCP tools have been removed. Use the REST search/navigation/file APIs instead of scope
program descriptors for new agent integrations.

## Documentation

- REST sessions and route overview: `docs/rest-api-session.md`
- File reads and glob: `.agents/skills/workspace-mcp-rest-api/references/read-and-glob.md`
- Search routes: `.agents/skills/workspace-mcp-rest-api/references/search.md`
- Navigation routes: `.agents/skills/workspace-mcp-rest-api/references/navigation.md`
- Write and patch routes: `.agents/skills/workspace-mcp-rest-api/references/write-and-patch.md`
- Inspection and format routes: `.agents/skills/workspace-mcp-rest-api/references/inspection-and-format.md`
<!-- Plugin description end -->
