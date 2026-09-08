# EnhancedHotSwapEnabler TODO

Status: WSL/EEL support validated in CI
Last Updated: 2026-09-08

Completed work is archived in
`plugins/EnhancedHotSwapEnabler/docs/EnhancedHotSwapEnabler-EelSupport.md`.

## Next

- Docker/EEL spike: `workflow_dispatch` job on `ubuntu-latest` using the platform
  `@TestApplicationWithEel` / `@DockerTest` framework to validate the container path conversion
  (routed-NIO transfer). Promote to PR trigger only if green.
- Rollout to other plugins: any plugin adds `@Tag("wsl")` tests; widen the `Run WSL Tests` step
  in `build.yml` (matrix `windows-latest` leg) to root `test -PwslDistro=...`.

## Known limitations

- JetBrains "Dev Containers" plugin is Ultimate-only (no IC-compatible release); CI uses the EEL
  Docker backend instead of the full dev-container flow.
