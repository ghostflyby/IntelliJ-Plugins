# Qodana warm-cache skip-sync false positives (2026-11-20..22)

Investigation archive for the burst of `KotlinUnreachableCode` /
`KotlinUnnecessaryOptInAnnotation` code-scanning alerts in the four buildSrc
precompiled script plugins (`repo.module`, `repo.intellij-lib`,
`repo.intellij-module`, `repo.intellij-plugin`), and the fixes that ended it.

## Summary

Between 2026-11-20 16:07 UTC and 2026-11-21 10:43 UTC, code scanning received
47 false-positive alerts, all in `buildSrc/src/main/kotlin/*.gradle.kts`, all
flagging every sub-expression of the `alias(libs.plugins.*)` chains as
unreachable code. The producing runs had restored the Qodana native analysis
cache: those runs skipped the Gradle import entirely (project opening stage
14–18 s instead of 3–5.5 min, no Gradle daemon) and analyzed the scripts
against a project model whose build-generated artifacts did not exist on the
fresh CI workspace.

## Root cause

Three caches with disjoint responsibilities cover the analysis environment:

| cache | carries | covers |
| --- | --- | --- |
| `qodana-native-v4` (`--cache-dir`) | IntelliJ project model + indexes | not workspace build outputs |
| gradle-home (`gradle/actions`) | external dependency jars, Gradle build cache | all external deps |
| ijpl-ides | IDE distributions | external |

Every CI run starts from a fresh checkout, so `build/` is empty. The only
action that materializes the version-catalog accessor classes the cached model
references (`generatePrecompiledScriptPluginAccessors`, `compilePluginsBlocks`,
`generateLibrariesForLibs` under `buildSrc/build/`) is a Gradle import. When
Qodana 2026.3 decides its cached model is fresh and skips the import:

- `alias(...)`, `libs.*`, `abiValidation()` resolve to error types → `Nothing`
  → unreachable-code cascade on every dot segment;
- the degradation is **silent**: the 17 `Roots ... were not resolved` warnings
  are byte-identical in clean and polluted runs (they reference dangling
  project jars that nothing needs at resolve time);
- only buildSrc scripts are affected — plugin sources resolve against external
  jars, which the other two caches restore.

Warm reuse is not probabilistically broken, it is structurally broken: the
cache-dir physically cannot carry workspace build outputs, so any skipped
import on a fresh workspace leaves the model pointing at nothing.

## Evidence: 13-run lineage (2026-11-20..21)

| run | commit | cache | diff vs cache source | sync | problems |
| --- | --- | --- | --- | --- | --- |
| 44709524175 | ccba63c | cold (first 2026.3) | — | yes | 55 |
| 44716891013 | f6a7bd5 | ccba63c | wrapper 9.8.1→9.9.1 | yes | 54 |
| 44721892086 | e3308e1 | cold | `libs.versions.toml` | yes | 54 |
| 44768989328 | cbc10c5 | e3308e1 | `.github` only | **no** | **101** |
| 44785335602 | 9feeb82 | cold | `build.gradle.kts` version | yes | 54 |
| 44864107540 | 9c8419a | 9feeb82 | `.github` only | **no** | **101** |
| 44876142860 | 1742504 | 9c8419a | 3 source files | **no** | **101** |
| 44889867766 | a6f811f | cold | `buildSrc/*.gradle.kts` + fixes | yes | 35 |
| 44895174059 | 098f676 | a6f811f | 2 source files | **no** | **79** |
| 44898941780 | d570556 | cold | `buildSrc/settings.gradle.kts` | yes | 32 |
| 44902096976 | 0a7c46f | cold | `build.gradle.kts` version | yes | 32 |
| 44912099909 | ce2b1aa | 0a7c46f | skill rename + changelogs | **no** | **75** |
| 44920728550 | 69f22ff | ce2b1aa | new source + docs | **no** | **77** |

The law, 13/13 consistent:

- **cold** (hash change or first run) → always full import → always clean;
- **warm** (gradle-hash equal) → model reuse, import skipped → polluted in 6
  of 7 cases;
- the single warm exception (f6a7bd5) coincides with a Gradle **distribution**
  change, which the IDE detects and forces a re-import — an input the cache key
  did not hash at the time.

The baseline amplified the damage: it is only written back by "healthy" runs,
and the polluted runs (47 new < the then-limit of 150) passed as healthy, so
the accepted-problem set oscillated 54↔101↔75↔77. Each oscillation round-tripped
alerts: the 16:07 batch in `repo.intellij-lib.gradle.kts` (7380–7396) was
auto-marked *fixed* by the next clean upload, then recreated as new alerts
(7428–7445) after a two-line edit shifted the fingerprints.

## Discriminating experiment (2026-11-21, PR #354, run 44930735812)

Hypothesis: the cached model is valid and only the artifacts are missing.
Test: run `./gradlew help` between the cache restore and the scan (any Gradle
invocation builds buildSrc first).

Result — confirmed in the strongest form:

- accessor tasks ran (`generatePrecompiledScriptPluginAccessors FROM-CACHE`,
  `:buildSrc:jar`), essentially free via the Gradle build cache;
- the scan **still skipped** the import (opening 12 s, daemon count 0) — this
  is not a forced re-import;
- analysis returned **30 problems, 0 new, zero buildSrc findings**;
- the clean upload auto-closed all 47 open false-positive alerts and the
  baseline was rewritten from the polluted 77 to a clean 30.

## Fixes

1. **`Materialize Gradle build state` step** (`.github/workflows/inspect-code.yml`):
   `./gradlew help --console=plain` before every scan. Workaround; remove once
   Qodana re-imports reliably on fresh workspaces.
2. **Health-gate recalibration** (2026-11-22): `MAX_TOTAL_PROBLEMS` 250→45 —
   the load-bearing, baseline-independent wall (healthy ≤35, skip-sync variant
   ≥75); `MAX_NEW_PROBLEMS` 150→60 — must stay above the baseline-less total
   (~35) or a cache eviction deadlocks baseline re-establishment;
   `MAX_SANITY` 50→10 (healthy runs are always 0).
3. **Cache-key hash extended** to `*gradle.properties` and
   `gradle/wrapper/gradle-wrapper.properties`: a distribution change must
   never reuse a model built by another Gradle.

## Related upstream

- [QD-11906](https://youtrack.jetbrains.com/issues/QD/11906) — same
  `Roots ... were not resolved` output, module pattern
  `kotlin.scripts.KotlinBuildScript.buildSrc:...`, native mode.
- [QD-7419492](https://youtrack.jetbrains.com/issues/QD/7419492) — JetBrains:
  *"the issue is the native itself: instead of storing gradle caches, we rely
  on 'native' caches, which are not present on CI"*.
- [QD-12483](https://youtrack.jetbrains.com/issues/QD/12483) — import logic
  "significantly refactored" in 2026.1; same cache-present-second-run family.
- [QD-13917](https://youtrack.jetbrains.com/issues/QD/13917),
  [QD-7563725](https://youtrack.jetbrains.com/issues/QD/7563725) — other
  members of the family (dropped findings; "no modules").
- Report pair: polluted [bQBg9V](https://qodana.cloud/projects/ww1K7/reports/bQBg9V)
  vs clean [0Z2ZeQ](https://qodana.cloud/projects/ww1K7/reports/0Z2ZeQ).

## Follow-ups

- [ ] File the upstream report: fresh workspace + warm native cache → Gradle
  import skipped deterministically; `.gradle.kts` analyzed against missing
  build artifacts; workaround is any Gradle invocation before the scan.
- [ ] Remove the materialize step once fixed upstream.
