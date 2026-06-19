# Workspace Agent Bridge

<!-- Plugin description -->
Workspace Agent Bridge lets local coding agents work through the IntelliJ project model instead of guessing from the
filesystem alone. It exposes a local REST API for reading files, searching indexed project content, navigating symbols,
checking problems, formatting code, and applying workspace edits with IntelliJ-aware safety checks.

The plugin starts a local server at `http://127.0.0.1:63341/api/v1`. If that port is already busy, it scans nearby ports
and remembers the selected one. Agents connect by creating a workspace session for the project or directory they intend
to work in, then use that session for file, search, navigation, inspection, and patch requests.

The API is optimized for agent-readable Markdown by default, with JSON available for structured clients. It supports
common coding-agent workflows such as:

- reading files with metadata, ranges, structure summaries, and problem reports;
- searching by glob, text, fuzzy file name, or symbol, including read-only library and SDK context when requested;
- jumping to declarations, finding usages, and reading documentation;
- creating, replacing, deleting, moving, and patching workspace files;
- running IntelliJ cleanup, optimize imports, and reformat operations.

Write access is intentionally limited to project workspace files. Dependency and SDK files are available as read-only
context, and protected paths require explicit confirmation before writes. Delete and move operations use IntelliJ
refactoring behavior where possible, so agents can update references instead of only moving bytes on disk.

For Codex users, the plugin bundles the `workspace-mcp-rest-api` skill and shows an upgrade notification with actions to
copy or reveal the local skill directory. The bundled skill contains the agent-facing workflow details and examples.

<!-- Plugin description end -->
