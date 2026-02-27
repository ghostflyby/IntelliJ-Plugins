# EnhancedHotSwapEnabler Agent Lifecycle Refactor

Status: Completed  
Last Updated: 2026-02-27

## Scope

1. Replace blocking/remote HotSwapAgent acquisition logic with packaged distribution lookup.
2. Remove unnecessary runtime resolution abstractions and keep agent lookup deterministic.
3. Tighten run configuration user-data lifecycle cleanup for plugin unload safety.
4. Align docs/licenses/build scripts with bundled-agent distribution.

## Implemented

1. Switched agent acquisition to bundled-jar lookup from plugin `lib/`.
2. Removed blocking agent lookup from run configuration patching and Gradle task patching paths.
3. Replaced broad dispose-time project scan with weak-tracked holder cleanup in run configuration extension.
4. Added Gradle-based bundled dependency distribution:
   - custom `hotswapAgentDistribution` configuration
   - `prepareSandbox`/`prepareTestSandbox` copy to plugin `lib/`
   - version managed from `gradle/libs.versions.toml`
5. Simplified final agent path access to a top-level lazy property (removed service/resolver indirection).
6. Added third-party notice for bundled HotSwapAgent (GPL-2.0) and source location.
7. Removed obsolete download-related localization keys now that runtime download is gone.

## Outcome Against Done Criteria

1. No remote download path remains in patcher execution paths.
2. Agent path resolution is deterministic from packaged plugin `lib/`.
3. Documentation, changelog, and license notice reflect packaged-agent distribution.

## Traceability

Source TODO was moved from:
`plugins/EnhancedHotSwapEnabler/TODO.md`
