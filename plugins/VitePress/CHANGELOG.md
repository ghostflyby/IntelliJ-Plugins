# VitePress Changelog

## [Unreleased]

### Added

- Optional Vue language service workaround for VitePress Markdown files, with a settings entry and a one-time
  application-level notification. The workaround switches the global `.md` association to `Vue`, keeps detected
  VitePress pages on the dedicated `VitePress` file type, and forces other Markdown files back to `Markdown` through
  dynamic file type overrides. [#208](https://github.com/ghostflyby/IntelliJ-Plugins/pull/208)

### Changed

### Deprecated

### Removed

### Fixed

- Renaming `script setup` symbols and VitePress interpolation references now updates usages inside injected heading,
  link text, and table cell template regions without triggering refactoring errors.
- Vue template interpolations inside plain Markdown table cells are now recognized and injected
  correctly. [#204](https://github.com/ghostflyby/IntelliJ-Plugins/pull/204)

### Security

## [1.0.1] - 2026-03-24

### Fixed

- Markdown files now switch to the `VitePress` file type immediately after being moved or copied into a recognized
  VitePress content tree.

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

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/VitePress-v1.0.1...HEAD
[1.0.1]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/VitePress-v1.0.0...VitePress-v1.0.1
[1.0.0]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/VitePress-v1.0.0
