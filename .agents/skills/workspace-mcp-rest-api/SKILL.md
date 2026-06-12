---
name: workspace-mcp-rest-api
description: Use when calling, testing, debugging, documenting, or generating client requests for the WorkspaceMcpTools REST API exposed by the IntelliJ plugin at /api/v1. Covers endpoint discovery, curl examples, content negotiation, headers, project/root/file/glob/read/write/patch routes, and safe AI usage rules.
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

- Examples use `curl`, but any HTTP client is supported. Prefer existing IDE/MCP read tools when they already provide
  the needed view; use this REST API when testing, debugging, or documenting REST behavior directly.
- Almost always inspect response headers together with the body. At minimum check HTTP status, `Content-Type`,
  redirects/errors, and negotiated format. With `curl`, prefer `-i` during exploration.
- Usually omit `Accept`, or use `Accept: */*`. The default response is optimized for agent and human reading and aligns
  with the Markdown/plain family.
- Use `Accept: application/json` for structured consumers such as scripts, typed clients, `jq`, and tests. JSON escaping
  is not the best direct reading format for agents.
- Explicit `text/plain`, `text/markdown`, and `text/x-markdown` are supported, but usually unnecessary during
  exploration.
- Use explicit boolean query values. Write `?meta=true`, `?content=true`, `?exists=true`, `?structure=true`, and
  `?force=true`; do not rely on bare presence-only flags such as `?meta`.
- Discover project and root identifiers before file operations. Start with `/server/info`, `/projects`, then
  `/projects/{projectKey}/roots`.
- Prefer the narrowest suitable root for the task. A plugin or source-root scope keeps glob and patch targets smaller
  than the repository root.
- For writes and patches, treat `force=true` as an explicit override for protected text paths such as ignored files. Do
  not add it by default.
- URL-encode path segments and query values. `rootId`, `projectKey`, VFS URLs, and relative paths can contain characters
  that need escaping.
- Read-only exploration should use `GET`; do not use `PUT`, `POST`, `PATCH`, or `DELETE` unless the user clearly asked
  for mutation.
- For unfamiliar code, use a workflow: glob with `limit=N`, then `meta=true` or `structure=true`, then `content=true`
  only for files that still need full text.

## Details

Load only the reference needed for the task:

`references/negotiation-and-discovery.md`: base URL, headers, content negotiation, server info, projects, roots, and
common error handling.

- `references/read-and-glob.md`: file reads, metadata/content/exists/structure flags, VFS reads, compound responses, and
  glob queries.
- `references/write-and-patch.md`: PUT/POST/DELETE/PATCH, force semantics, patch formats, write responses, and mutation
  safety.
