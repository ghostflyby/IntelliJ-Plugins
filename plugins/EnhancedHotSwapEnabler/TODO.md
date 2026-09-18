# EnhancedHotSwapEnabler TODO

Status: WSL/EEL support validated in CI; the WSL transport test is parked under the default test
classpath (see Next)
Last Updated: 2026-09-18

Completed work is archived in
`plugins/EnhancedHotSwapEnabler/docs/EnhancedHotSwapEnabler-EelSupport.md`.

## Next

- Re-enable `WslDcevmSupportTest`: it is `@Disabled` because the test classpath now carries the
  IDE's bundled plugins (upstream default), which routes WSL UNC paths through the ijent-backed EEL
  machine whose `testServiceImplementation` (`TestIjentExecFileProvider`) ships in no IDE
  distribution (IJPL-178929 / IJPL-222201). Restore once the platform ships that class or removes
  `testServiceImplementation`; the EEL fixture publishing gap is IJPL-243361.
- Docker/EEL spike: `workflow_dispatch` job on `ubuntu-latest` using the platform
  `@TestApplicationWithEel` / `@DockerTest` framework to validate the container path conversion
  (routed-NIO transfer). Promote to PR trigger only if green. Blocked upstream: the EEL test
  fixtures are not published outside the monorepo (IJPL-243361).
- Rollout to other plugins: any plugin adds `@EnabledOnWsl` tests; widen the `Run WSL Tests`
  step in `build.yml` (matrix `windows-latest` leg) to root `test`.

## Known limitations

- JetBrains "Dev Containers" plugin is Ultimate-only (no IC-compatible release); CI uses the EEL
  Docker backend instead of the full dev-container flow.
