# WorkspaceMcpTools TODO

Status: In Progress
Last Updated: 2026-09-21

## Active Plan

1. Keep the REST API contract authoritative for agent-facing workspace file operations:
   session header plus `/files/{path...}`, `/glob/{path...}`, `/search/text/{path...}`, and `/navigation/{path...}`.
2. Run one interactive `runIdeSplitMode` pass to verify the modular split at runtime (backend binds 63441+, skill
   notification on the frontend); see `docs/WorkspaceMcpTools-ModularSplitMigration.md`.
3. Keep `.agents/skills/workspace-agent-bridge` and REST docs synchronized with implemented route behavior and output
   shape.
4. Evaluate an RPC bridge (`@Rpc` + `remoteApiProvider` + frontend proxy) so remote-development agents can reach the
   REST surface without manual port forwarding.
5. Audit README and older design notes for stale Resources-era path model language after the REST contract settles.

## Archive

Completed modular split migration notes: `docs/WorkspaceMcpTools-ModularSplitMigration.md`.
Completed backend-only runtime notes: `docs/WorkspaceMcpTools-BackendOnlyRuntime.md`.
Completed Resources-era core/feature boundary notes: `docs/WorkspaceMcpTools-CoreFeatureBoundary.md`.
