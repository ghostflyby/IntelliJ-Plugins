---
name: workspace-mcp-rest-api
description: Use when calling, testing, debugging, documenting, or generating client requests for the WorkspaceMcpTools REST API exposed by the IntelliJ plugin at /api/v1. Covers endpoint discovery, session headers, curl examples, content negotiation, file/glob/read/write/patch routes, and safe AI usage rules.
---

# WorkspaceMcpTools REST API

Use this skill when an agent needs to call or reason about the WorkspaceMcpTools REST API exposed by the IntelliJ
plugin.

Default base URL:

```bash
BASE=http://127.0.0.1:63341/api/v1
```

The port can be overridden when the IDE starts with `-Ddev.ghostflyby.mcp.workspace.port=<port>`.

## Operating Rules

- Examples use `curl`, but any HTTP client is supported.
- Almost always inspect response headers together with the body. At minimum check HTTP status, `Content-Type`,
  redirects/errors, and negotiated format. With `curl`, prefer `-i` during exploration.
- The default response is optimized for agent and human reading and aligns with the Markdown/plain family.
- Use explicit boolean query values. Write `?meta=true`, `?content=true`, `?exists=true`, `?structure=true`, and
  `?force=true`; do not rely on bare presence-only flags such as `?meta`.
- Create a session before file operations with `POST /sessions` and a `pathPrefix`, then send
  `X-Ghostflyby-Workspace-Session-Id` on every `/files`, `/glob`, `/search/text`, `/search/files`,
  `/search/symbols`, and `/navigation` request.
- Prefer the narrowest suitable `pathPrefix` for the task. A plugin or source directory keeps glob and patch targets
  smaller than the repository root.
- For writes and patches, treat `force=true` as an explicit override for protected text paths such as ignored files. Do
  not add it by default.
- `/files` PATCH accepts the OpenAI Responses API `apply_patch` format when the body starts with `*** `. Load
  `references/apply-patch-format.md` only when the exact format is unknown or a patch fails because of formatting.
- URL-encode path segments and query values. Relative paths and query values can contain characters that need escaping.
- Read-only exploration should use `GET`; do not use `PUT`, `POST`, `PATCH`, or `DELETE` unless the user clearly asked
  for mutation.
- For unfamiliar code, use a workflow: glob with `limit=N`, then `meta=true` or `structure=true`, then `content=true`
  only for files that still need full text. After `structure=true`, use the returned line numbers with
  `startLine=N&endLine=M` or `aroundLine=N&radius=M` for targeted reads of specific declarations.
  Use `/navigation/{path}` with `*** Goto:` / `*** Usages:` / `*** Documentation:` blocks
  to navigate code structure.

## Details

Load only the reference needed for the task:

`references/negotiation-and-discovery.md`: base URL, session creation, headers, response negotiation, server info, and
common error handling.

- `references/read-and-glob.md`: file reads, metadata/content/exists/structure flags, compound responses, and glob
  queries.
- `references/write-and-patch.md`: PUT/POST/DELETE/PATCH, force semantics, patch formats, write responses, and mutation
- `references/apply-patch-format.md`: OpenAI Responses API `apply_patch` body format for `/files` PATCH. Load only
  when the exact format is unknown or a patch fails because of formatting.
- `references/search.md`: text search via FindModel, file and symbol search via IDE indexes, context filtering, file
  glob, occurrence IDs for PATCH follow-up.
- `references/navigation.md`: goto declaration, find usages, documentation lookup via Codex patch hunk selection.
  safety.
