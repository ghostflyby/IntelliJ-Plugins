# WorkspaceMcpTools TODO

Status: In Progress
Last Updated: 2026-06-14

## Active Plan

1. Keep the REST API contract authoritative for agent-facing workspace file operations:
   session header plus `/files/{path...}`, `/glob/{path...}`, `/search/text/{path...}`, and `/navigation/{path...}`.
2. Maintain regression coverage for session lifecycle, relative path and full VFS URL resolution, project-only writes,
   markdown-first responses, and removed legacy file routes.
3. Keep `.agents/skills/workspace-mcp-rest-api` and REST docs synchronized with implemented route behavior and output shape.
4. Audit README and older design notes for stale Resources-era path model language after the REST contract settles.

## Archive

Completed Resources-era core/feature boundary notes moved to:
`plugins/WorkspaceMcpTools/docs/WorkspaceMcpTools-CoreFeatureBoundary.md`
