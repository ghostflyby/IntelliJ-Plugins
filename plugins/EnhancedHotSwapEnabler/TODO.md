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
- Root cause found in first CI run: `WslEelProvider.getEelDescriptor` is gated by
  `WslIjentAvailabilityService.useIjentForWslNioFileSystem()` (per-product build constant in real
  IDEs), so WSL UNC paths may resolve to `LocalEelDescriptor` — GeneralCommandLine's implicit EEL
  routing cannot be relied upon for WSL executables.
- Routing (see `wsl/EelJavaSupport.kt`): flags check runs via explicit EEL exec (non-local
  descriptor) -> WSL distribution patching (`WSLDistribution.patchCommandLine`, platform picks
  IJent or wsl.exe) -> plain local process. `toDaemonVisiblePath` checks the WSL branch first,
  independent of the gate.
- Test env (`WslTestEnvironment`) performs all in-distro operations through the same patched
  command line (`/bin/sh -c`); no raw `wsl.exe` calls mixed with 9P file operations (that mixing
  caused a `wsl chmod` visibility race in the first run).
- Pending CI validation:
  - WSL job rerun: flags check via the WSL branch, `/mnt/c` drive mapping, UNC project open.
  - On remaining path-conversion failures: stop and report.
- Next:
  - Docker/EEL spike (`workflow_dispatch` job, platform `@TestApplicationWithEel`/`@DockerTest`)
    to probe the container path; promote to PR trigger only if green.
  - Rollout to other plugins: any plugin adds `@Tag("wsl")` tests; switch the workflow to root
    `test -PwslDistro=...` when more plugins participate.
- Known limitations:
  - Container Gradle path conversion copies host files through the routed NIO filesystem
    (`EelPath.asNioPath` + `java.nio.file.Files`, public Experimental API only); pending real
    validation by the Docker/EEL spike job.
  - JetBrains "Dev Containers" plugin is Ultimate-only; CI uses the EEL Docker backend instead.
