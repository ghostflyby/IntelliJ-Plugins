# Mill WSL/EEL Execution Targets

Status: Implemented (local + WSL)
Completed: 2026-11-24
Reference patterns: `plugins/EnhancedHotSwapEnabler` `EelJavaSupport.kt`, `TargetPathConversion.kt`, `docs/EnhancedHotSwapEnabler-EelSupport.md`

## Design

`MillExecutionTarget` (`backend/.../settings/MillExecutionTarget.kt`) models where a linked
Mill project lives: `Local`, `Wsl(distribution)`, or `Eel(description)` (detected, not
executable yet). It is `Serializable` and carried inside `MillExecutionSettings` into the
external-system remote process, so **everything that consumes it is plain-JVM**: `wsl.exe`
process calls and string-level path conversion only. The IDE-side detector
(`MillExecutionTargets.detect`) is the only place allowed to touch platform APIs:

- WSL detection goes through `WslPath.parseWindowsUncPath(String)` **first**, because the EEL
  WSL layer is product-gated (`WslIjentAvailabilityService.useIjentForWslNioFileSystem()`) and
  resolves WSL UNC paths to `LocalEelDescriptor` when the gate is off — the same caveat
  EnhancedHotSwapEnabler documented. `WSLDistribution.id` is the name `wsl.exe -d` accepts.
- Non-local `getEelDescriptor()` results become `Eel` targets; importing them fails fast with
  "not supported yet" instead of running a Linux binary on the host.

## Command Layer (MillCommandLineUtil)

- `createCommandLine` branches on the target: local builds the Mill command as before; WSL
  builds `wsl.exe -d <distro> --cd <linuxProjectRoot> --exec <exe> <args...>` (`--exec`
  bypasses the shell so argv quoting survives; `--cd` makes wrapper scripts resolve against
  the project); `Eel` throws with a clear message.
- Path conversion: `toTargetPath` maps `\\wsl.localhost\<distro>\…` / `\\wsl$\<distro>\…` to
  `/…`; `toHostPath` maps absolute `/…` values from `mill show` output back to
  `\\wsl.localhost\<distro>\…` UNC so the IDE project model (content roots, libraries, JDK
  homes, build-script classpaths) resolves real files. Non-absolute values (versions, target
  names) pass through untouched.
- Wrapper discovery keeps using host-side NIO over the UNC root (works from Windows); PATH
  discovery runs inside the distribution (`sh -lc "command -v mill"`) because the host PATH
  knows nothing about the shell profile.
- `probeExecutable`/`discoverExecutables`/`resolveExecutable` take a `MillExecutionTarget`
  (defaulting to `Local`, which is why all pre-existing call sites and tests are unchanged).

## Wiring

- `MillExternalSystemManager.getExecutionSettingsProvider` detects the target from the linked
  project path and serializes it into `MillExecutionSettings`; the remote resolver and task
  manager pick it up transparently.
- `MillConfigurableViewModel` detects the target per project (inside the existing
  `runInterruptible(Dispatchers.IO)` blocks — the EEL provider must not be touched on EDT) and
  threads it into the injected discovery/probe lambdas.

## Known Limitations

- Environment variables from settings are not forwarded into WSL yet (`WSLENV` plumbing);
  args containing spaces rely on wsl.exe's argv reconstruction.
- EEL container targets: detected and reported as unsupported; execution needs the same
  Ijent-backed machinery EnhancedHotSwapEnabler parked on IJPL-178929 / IJPL-243361.
- CI: the Windows/WSL matrix leg runs `:plugins:Mill:test` — `MillWslExecutionTest` self-skips
  without a distribution and otherwise exercises real `wsl.exe` target detection and PATH
  discovery (no Mill launcher needed inside the distro).
