# EnhancedHotSwapEnabler TODO

Status: WSL/EEL support in progress
Last Updated: 2026-09-08

## WSL / EEL support (in progress)

- Goal: debug runs of WSL-hosted projects (Windows IDE + WSL project) must work, verified in CI
  (`.github/workflows/wsl-tests.yml`, PR-triggered, runs `@Tag("wsl")` tests via `-PwslDistro`).
- Done:
  - VM parameter injection moved from `java.programPatcher` (too late for target-based runs) to
    `HotswapRunConfigurationExtension.updateJavaParameters` (4-arg) + `JavaTargetDependentParameters`
    (coverage-plugin pattern). `DCEVMProgramPatcher` removed.
  - Gradle init-script classpath + agent env var converted via `toDaemonVisiblePath`
    (EEL descriptor detection + `WSLDistribution.getWslPath` fallback for `/mnt/c`).
- Pending CI validation:
  - WSL job first run: project open from `\\wsl.localhost\...`, flags check via EEL routing,
    `-javaagent` upload for target runs. On path-conversion failures: stop and report.
- Next:
  - Docker/EEL spike (`workflow_dispatch` job, platform `@TestApplicationWithEel`/`@DockerTest`)
    to probe the container path; promote to PR trigger only if green.
  - Rollout to other plugins: any plugin adds `@Tag("wsl")` tests; switch the workflow to root
    `test -PwslDistro=...` when more plugins participate.
- Known limitations:
  - dev containers have no drive mapping (agent path conversion unsupported); needs an EEL
    transfer-based approach.
  - JetBrains "Dev Containers" plugin is Ultimate-only; CI uses the EEL Docker backend instead.
