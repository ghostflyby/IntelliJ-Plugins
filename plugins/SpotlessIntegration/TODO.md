# SpotlessIntegration TODO

Status: Ready Last Updated: 2026-07-21

## Next Steps

1. Add UI-enabled integration coverage for status-bar availability changes and popup inline actions.
2. Add a small third-party provider compatibility fixture for the public generation-based provider API.
3. Review cleanup deadline telemetry after real Gradle daemon failures and tune warning detail if needed.

## Done Criteria

1. Status widget creation, removal, and root action rendering are covered in a real IDE-frame test.
2. A provider outside the built-in Gradle integration compiles and runs against the documented public contract.
3. Cleanup timeout reports identify the provider session and normalized external-project root.

## Completed Work

See `docs/SpotlessIntegration-AsyncLifecycleRefactor.md` and
`docs/SpotlessIntegration-ApiSurfaceContract.md`.
