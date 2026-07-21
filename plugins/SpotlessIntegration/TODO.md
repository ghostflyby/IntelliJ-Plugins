# SpotlessIntegration TODO

Status: Ready Last Updated: 2026-07-20

## Next Steps

1. Add UI-enabled integration coverage for status-bar availability changes and popup inline actions.
2. Add a second provider fixture to exercise the public daemon-provider EP independently of Gradle.
3. Re-check the isolated `StatusBarWidgetsManager` dependency when updating the IntelliJ Platform baseline.

## Done Criteria

1. Status widget creation, removal, and root action rendering are covered in a real IDE-frame test.
2. Provider-neutral behavior is validated with two independent provider implementations.
3. The status-bar availability adapter remains the only dependency on the platform implementation package.

## Completed Work

See `docs/SpotlessIntegration-AsyncLifecycleRefactor.md`.
