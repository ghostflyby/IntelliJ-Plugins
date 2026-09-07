# EnhancedHotSwapEnabler — WSL / EEL Support

Status: WSL validated in CI (PR #293)
Last Updated: 2026-09-08

## What this covers

Debug runs (`-agentlib:jdwp`) of projects hosted in non-local environments — WSL distributions
(Windows IDE + WSL project) and dev containers — get the DCEVM detection and the bundled
HotSwapAgent injected so they work inside the target environment.

## Architecture

### Injection point

VM parameters are injected via `HotswapRunConfigurationExtension.updateJavaParameters`
(the 4-arg `RunConfigurationExtension` overload) + `JavaTargetDependentParameters` /
`JavaTargetParameter` — the coverage-plugin pattern. This happens before the command line is
built, which matters because:

- WSL JDKs force the target flow (`JavaRunConfigurationBase.runsUnderWslJdk()`), and
  `JavaProgramPatcher` runs *after* the `TargetedCommandLine` is built there — parameters added by
  a program patcher are silently dropped (this is why `DCEVMProgramPatcher` was removed).
- For local runs, `JavaTargetParameter.toLocalParameter()` resolves to the plain host path.
- For WSL/container targets, the platform (`JdkCommandLineSetup.appendVmParameters`) uploads the
  agent jar into an `AGENTS_VOLUME` and rewrites `-javaagent:` to the target-side path.

Debug-run detection uses the `Executor` (4-arg overload) with a jdwp-parameter fallback.
`ExternalSystemRunConfiguration` (Gradle runs) is skipped — the Gradle daemon side is handled by
`DCEVMGradleManagerExtension` + the init-script plugin.

### Flags check routing (`wsl/EelJavaSupport.javaOptionLines`)

`<jdkHome>/bin/java -XX:+PrintFlagsFinal -version` must execute *inside* the environment hosting
the JDK (a Linux ELF from Windows otherwise fails with `CreateProcess error=193`). Routing order:

1. **EEL exec** — non-local `EelDescriptor` (`Path.getEelDescriptor()`):
   `descriptor.toEelApi().exec.spawnProcess(...)`. Covers dev containers and WSL when EEL NIO is
   enabled.
2. **WSL distribution** — `WslPath.parseWindowsUncPath` non-null:
   `WSLDistribution.patchCommandLine(cmd, null, WSLCommandLineOptions().setLaunchWithWslExe(true))`.
3. **Plain local process** otherwise.

Both non-local branches are wrapped in `ProgressManager.runProcess` with an
`EmptyProgressIndicator`: `WSLDistribution.doPatchCommandLine` resolves the distro shell path via
`runBlockingCancellable`, which throws on threads without a ProgressIndicator/Job (plain test
threads; some IDE call contexts). The EEL exec branch uses a plain `runBlocking` for the same
reason. `getDcevmSupport` caches per JDK, so only the first call per JDK pays the cost.

### Gradle daemon path conversion (`wsl/TargetPathConversion.toDaemonVisiblePath`)

The init-script classpath and the `ijHotswapAgentJarPath` env var must be daemon-visible paths:

- WSL project (checked first, independent of any gate): same-distribution UNC paths → Linux form
  (`WslPath.linuxPath`); host drive paths → `/mnt/<drive>/...` via
  `WSLDistribution.getWslPath`.
- Other non-local environments (dev containers): no drive mapping exists — the host file is
  copied through the routed NIO filesystem (`EelPath.parse` + `asNioPath` + `java.nio.file.Files`)
  with per-source caching and existence revalidation.
- Local: unchanged.

## Platform findings (2026.1 / build 261)

- `WslEelProvider.getEelDescriptor` is gated by
  `WslIjentAvailabilityService.useIjentForWslNioFileSystem()` — a per-product build constant in
  real IDEs (`isMultiRoutingFileSystemEnabledForProduct`). When off, WSL UNC paths resolve to
  `LocalEelDescriptor`, and `GeneralCommandLine`'s implicit EEL routing (registry
  `ide.general.command.line.use.eel=true`) does not engage either. Do not rely on
  `getEelDescriptor` to detect WSL UNC paths; use `WslPath.parseWindowsUncPath` for the WSL case.
- `mustRunCommandLineWithIjent = isIjentAvailable && !options.isLaunchWithWslExe && ...`;
  `setLaunchWithWslExe(true)` forces the deterministic wsl.exe path.
- All EEL surface used here is `@ApiStatus.Experimental`
  (`com.intellij.platform.eel.provider`), no `@ApiStatus.Internal` API.
- EEL provides no public Windows-drive → `/mnt/<drive>` mapping; JetBrains' own
  `TerminalLocalPathTranslator` combines EEL detection with `WSLDistribution.getWslPath` — the
  same pattern used here.

## CI

- `.github/workflows/wsl-tests.yml` (PR-triggered): `windows-latest` + `Vampire/setup-wsl@v7`
  (Ubuntu-24.04, set-as-default) running `@Tag("wsl")` tests via `-PwslDistro=Ubuntu-24.04`.
  Note: quote the property value — PowerShell splits unquoted `Ubuntu-24.04`.
- Convention plugin (`repo.intellij-lib.gradle.kts`): `-PwslDistro` forwards to the `wsl.distro`
  system property; without it, `wsl`-tagged tests are excluded everywhere. Rollout to other
  plugins = add tagged tests, then widen the workflow invocation to root `test`.
- `WslTestEnvironment` performs all in-distro setup through the same patched command line
  (`/bin/sh -c`); mixing raw `wsl.exe` calls with 9P file operations raced in the first run
  (files created via `\\wsl.localhost` were not immediately visible to `wsl chmod`).
- Validated green: project open + VFS + indexing from `\\wsl.localhost\...`, DCEVM detection
  (Auto/RequiresArg/None/AltJvm) through the WSL branch, `/mnt/c` drive mapping, target-parameter
  resolution (local host path vs uploaded target path).

## Known limitations / next steps

- Container path conversion (routed-NIO copy) is untested in CI; a Docker/EEL spike
  (`workflow_dispatch`, platform `@TestApplicationWithEel` / `@DockerTest`) is planned before it
  is promoted to PR-triggered.
- JetBrains "Dev Containers" plugin is Ultimate-only (no IC-compatible release ever published);
  CI uses the EEL Docker backend instead.
