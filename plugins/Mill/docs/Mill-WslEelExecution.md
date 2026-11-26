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

## Command Layer (MillCommandLineUtil, backend)

The command layer lives in the `backend` content module: it spawns processes and therefore never
runs in a split-mode frontend. The frontend reaches it through `MillBackendRpcApi`, so probing
always runs on the host that owns the project (see `Mill-ModularSplitMigration.md`).

- `createCommandLine` branches on the target: local builds the Mill command as before; WSL
  builds `wsl.exe -d <distro> --cd <linuxProjectRoot> --exec <exe> <args...>` (`--exec`
  bypasses the shell so argv quoting survives; `--cd` makes wrapper scripts resolve against
  the project); `Eel` throws with a clear message.
- WSL processes are started by `startProcess` through a plain `ProcessBuilder` instead of
  `GeneralCommandLine.toProcessBuilder()`. For remote-looking commands the platform may route
  process creation through its EEL daemon, and environments that refuse to start that daemon
  (an elevated Windows CI runner answers `Failure(82 (ELEVATED_PRIVILEGES))`) would otherwise
  break every Mill call. The argv is rebuilt from `exePath + parametersList.list`, so the
  command is unchanged; output is decoded as UTF-8 (the distribution's encoding).
- A `\\wsl.localhost` path is never handed to `CreateProcess` as the working directory: the
  `--cd` argument already selects it inside the distribution.
- Path conversion: `toTargetPath` maps `\\wsl.localhost\<distro>\…` / `\\wsl$\<distro>\…` to
  `/…`; `toHostPath` maps absolute `/…` values from `mill show` output back to
  `\\wsl.localhost\<distro>\…` UNC so the IDE project model (content roots, libraries, JDK
  homes, build-script classpaths) resolves real files. Non-absolute values (versions, target
  names) pass through untouched.
- Wrapper discovery keeps using host-side NIO over the UNC root (works from Windows). A bare
  command (e.g. `mill`) is resolved inside the distribution with
  `sh -lc "command -v <cmd>"` — the host PATH knows nothing about the shell profile that sets
  up in-distribution tools. `findTargetCommandPath` is the single lookup entry point
  (`discoverExecutables` and `resolveTargetExecutable` both use it); successful lookups are
  cached per `"<distro>:<command>"`, misses are not, so installing a launcher later is picked
  up. Only simple command tokens are looked up; absolute, UNC and project-relative paths bypass
  the shell entirely.
- `probeExecutable`/`discoverExecutables`/`resolveExecutable` take a `MillExecutionTarget`
  (defaulting to `Local`, which is why all pre-existing call sites and tests are unchanged).

## Wiring

- `MillExternalSystemManager.getExecutionSettingsProvider` detects the target from the linked
  project path and serializes it into `MillExecutionSettings`; the remote resolver and task
  manager pick it up transparently.
- `MillBackendRpcApiImpl` (backend) detects the target from the requested project path before
  discovery/probing; the frontend settings view model only sends the project path and executable
  configuration over RPC, so the EEL provider is touched on the backend host only.

## Known Limitations

- Environment variables from settings are not forwarded into WSL yet (`WSLENV` plumbing);
  args containing spaces rely on wsl.exe's argv reconstruction.
- Bare-command resolution uses `sh -lc`, which reads login profiles (`/etc/profile`,
  `~/.profile`) but not interactive files such as `~/.bashrc`/`~/.zshrc`. A launcher whose PATH
  is exported only from an interactive shell file is not found; configure an absolute path in
  that case.
- EEL container targets: detected and reported as unsupported; execution needs the same
  Ijent-backed machinery EnhancedHotSwapEnabler parked on IJPL-178929 / IJPL-243361.
- CI: the Windows/WSL matrix leg runs
  `:plugins:Mill:backend:test --tests "dev.ghostflyby.mill.wsl.MillWslExecutionTest"`.
  `MillWslExecutionTest` is `@EnabledOnOs(WINDOWS)`; the UNC-detection test needs no installed
  distribution, and the login-shell test self-skips without one. Its lookups spawn `wsl.exe`
  directly — the same path production uses — so the leg also guards against the EEL-daemon
  regression that motivated `startProcess`.
