# SpotlessIntegration TODO

Status: Ready Last Updated: 2026-07-22

## Next Steps

1. Add a third-party provider compatibility fixture for the stable `runDaemon` and presentation APIs.
2. Add UI-enabled integration coverage for status-bar availability changes and popup inline actions.
3. Prototype split-mode module movement without changing the fixed public FQNs or adding RPC prematurely.

## Done Criteria

1. A provider outside the built-in Gradle integration compiles and runs against the documented public contract.
2. Status widget creation, removal, and root action rendering are covered in a real IDE-frame test.
3. Frontend/backend module movement preserves the current ABI and behavior in local and split deployments.

## Completed Work

See `docs/SpotlessIntegration-AsyncLifecycleRefactor.md` and
`docs/SpotlessIntegration-ApiSurfaceContract.md`.
