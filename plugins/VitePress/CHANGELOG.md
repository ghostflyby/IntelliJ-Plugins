# VitePress Changelog

## [Unreleased]

### Added

### Changed

### Deprecated

### Removed

### Fixed

- wrong enum names for AI agents in tool calling [#194](https://github.com/ghostflyby/IntelliJ-Plugins/pull/194)

### Security

## [1.0.0] - 2026-03-09

### Added

- Automatic VitePress root detection that recognizes site Markdown files and opens them with the dedicated `VitePress`
  file type.
- Mixed Markdown and Vue editing support for VitePress pages, including top-level Vue tags and interpolations.
- Vue template support inside plain text, headings, and link text, with Vue highlighting preserved inside injected
  ranges.
- VitePress preview support that resolves pages against a running local preview server and opens them in the browser
  from the IDE.
- `package.json` script detection for VitePress preview and dev setups, including omitted `dev`, `preview`, `serve`,
  `--base`, and `cd some/dir && vitepress ...` command forms.
- VitePress-aware commenting behavior for Vue template fragments inside Markdown.
- Support for VitePress custom containers and custom fence handling.
- Bundled spellchecker dictionary entries for VitePress terminology.

### Fixed

- Stable mixed highlighting for headings and links so Markdown host styling no longer overrides injected Vue template
  ranges.
- Stable top-level HTML block handling for multi-line blocks such as `script`, avoiding fragmented lexing and broken
  embedded highlighting.

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/VitePress-v1.0.0...HEAD
[1.0.0]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/VitePress-v1.0.0
