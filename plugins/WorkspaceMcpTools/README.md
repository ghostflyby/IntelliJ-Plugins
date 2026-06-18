# Workspace Agent Bridge

<!-- Plugin description -->
Workspace Agent Bridge exposes IntelliJ workspace capabilities to local coding agents through a Markdown-first REST API
and a lightweight MCP SDK endpoint. The REST API is the primary agent-facing surface for file reads, edits, search,
navigation, inspections, formatting, cleanup, and refactoring-aware file operations.

## REST API

The REST server is hosted once per IDE application.

- Base URL: `http://127.0.0.1:63341/api/v1`
- If port 63341 is already in use, the server scans up to 10 ports and persists the result.
- Create a workspace session with `POST /sessions` and pass
  `X-Ghostflyby-Workspace-Session-Id` on workspace requests.
- Default responses are Markdown/plain text optimized for agents. Use `Accept: application/json` only for structured
  clients.

Core routes:

| Capability                                                                          | Route                                                             |
|-------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| Project/session discovery                                                           | `GET /projects`, `POST /sessions`, `DELETE /sessions/{sessionId}` |
| File read, metadata, structure, problem view                                        | `GET /files/{path...}`                                            |
| Create or replace text                                                              | `PUT /files/{path...}`                                            |
| Create only, or create directory with an empty body                                 | `POST /files/{path...}`                                           |
| Refactoring-aware delete                                                            | `DELETE /files/{path...}`                                         |
| Apply OpenAI `apply_patch`, Git patch, format, cleanup, move, and delete operations | `PATCH /files/{path...}`                                          |
| Glob and text search                                                                | `GET /glob/{path...}`, `GET /search/text/{path...}`               |
| Fuzzy file and symbol search                                                        | `GET /search/files`, `GET /search/symbols`                        |
| Goto, usages, documentation                                                         | `POST /navigation/{path...}`                                      |
| Inspection refresh/report                                                           | `POST /inspections/{path...}`                                     |

Path inputs use session-relative paths or URL-encoded full IntelliJ VFS URLs. Full VFS URLs are accepted for reads;
write operations remain restricted to project workspace files.

`DELETE /files/{path...}` uses IntelliJ safe-delete behavior for files. If references are found, the default response is
`409 Conflict` with a references table; retry with `force=true` only when deletion is intentional. `PATCH` delete and
move sections use the same PSI/VFS-aware refactoring path, including IntelliJ move and rename refactoring for
`*** Move to:`.

<!-- Plugin description end -->
