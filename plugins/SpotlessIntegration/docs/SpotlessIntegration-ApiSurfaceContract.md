# SpotlessIntegration API Surface Contract

## Scope

This note records the intentionally public API surface after the provider lifecycle and formatting-preprocessor
refactors.

The plugin keeps public daemon-provider and formatting-preprocessor extension points, but the formatter orchestration,
capability cache, daemon registry, and SpotlessDaemon HTTP client are internal implementation
details. The external SpotlessDaemon HTTP API is owned outside this project and must remain
unchanged by this plugin.

## Public Types Kept Intentionally

### `dev.ghostflyby.spotless.SpotlessDaemonProvider`

`SpotlessDaemonProvider` remains public because it is the interface of the public
`dev.ghostflyby.spotless.spotlessDaemonProvider` extension point.

Contract notes:

- `resolveTarget(project, file)` performs cheap, file-specific resolution. Returning `null` lets the core try the next
  provider instead of claiming the whole project.
- `startDaemon(context)` receives a core-owned `SpotlessDaemonStartContext` and returns a `SpotlessDaemonEndpoint`.
- The start context exposes the IntelliJ project, external project root, and daemon lifecycle without exposing mutable
  core state. Providers consume the context but do not construct or retain it after startup.
- Provider-owned runtime resources must be registered through `lifecycle.registerCleanup`. Provider-owned asynchronous
  work remains owned by the provider's own plugin/project scope and must be synchronously cancelled from its cleanup.
- `startDaemon(context)` must cooperate with coroutine cancellation. Provider removal, provider replacement, and
  project disposal may cancel a start in progress, so resources must be registered immediately after creation.
- If a provider observes natural daemon process termination, it should call `lifecycle.requestClose(reason)`. The core
  registry removes the daemon entry without using a public project-service callback.
- Providers start the process and return its address; daemon HTTP readiness checks and the external HTTP protocol remain
  core responsibilities.

### `dev.ghostflyby.spotless.SpotlessDaemonLifecycle`

`SpotlessDaemonLifecycle` is public because the core registry passes it to provider implementations.

Contract notes:

- `requestClose(reason)` non-blockingly asks the core registry to detach this daemon. Natural process termination should
  use this path.
- `registerCleanup(cleanup)` registers synchronous provider-owned cleanup. Cleanup is LIFO, exactly once, and
  best-effort;
  providers should keep it fast and idempotent.
- Registering after the lifecycle has closed executes that cleanup synchronously before `registerCleanup` returns. This
  closes the race between asynchronous process creation and core detach.
- Providers must not call project services or mutate registry state directly for daemon release.
- The lifecycle intentionally exposes neither a `CoroutineScope` nor a `Job`: providers cannot bypass registry detach,
  capability-cache invalidation, or HTTP stop ordering.

### `dev.ghostflyby.spotless.SpotlessDaemonEndpoint`

`SpotlessDaemonEndpoint` remains public because it crosses the provider/core boundary.

The concrete `Localhost` and `UnixSocket` variants are part of that contract and therefore stay public. Endpoint values
contain only connection information; provider-private temp directories and process resources must stay in registered
cleanup closures.

### `dev.ghostflyby.spotless.SpotlessDaemonTarget`

`SpotlessDaemonTarget` is public because providers return it from `resolveTarget(...)`.

It contains the external project path used for daemon ownership and the concrete file path sent to the daemon.

### `dev.ghostflyby.spotless.SpotlessFormattingPreprocessor`

`SpotlessFormattingPreprocessor` is the interface of the public, dynamic
`dev.ghostflyby.spotless.spotlessFormattingPreprocessor` extension point.

Contract notes:

- `isApplicableTo(psiFile)` runs against the actual formatting target under read access and must be cheap.
- `preprocess(context)` receives the current content and daemon steps, decides whether work is needed, and may return
  transformed text plus daemon step names to skip. Returning `null` leaves the
  request unchanged.
- `content` is the authoritative formatter input. `psiFile` supplies invocation context and may not yet reflect content
  transformed by an earlier preprocessor.
- The supplied `PsiFile` is invocation-scoped. Implementations must not retain it and must follow IntelliJ PSI and
  Document threading rules for every access.
- The core validates returned step names against daemon configuration, de-duplicates them, and preserves daemon step
  order when sending repeated `skipStep` query parameters.

### `SpotlessFormattingPreprocessContext` and `SpotlessFormattingPreprocessResult`

The core-owned context interface carries the invocation-scoped PSI target, current request text, and daemon step list.
The result carries transformed text and requested skipped step names. Neither type exposes the
daemon endpoint, HTTP client, daemon lifecycle, or formatter result.

## Internal-Only Surface

The following are intentionally internal and are not part of the public ABI:

- formatter orchestration through `SpotlessProjectService`;
- `SpotlessFormatResult`;
- `SpotlessDaemonClient`;
- `SpotlessDaemonRegistry`;
- `SpotlessCapabilityCache`;
- Gradle settings/runtime implementation details.

## ABI Decision

This phase intentionally removes the old public `Spotless` formatter service and public `SpotlessFormatResult`.

Only daemon-provider and formatting-preprocessor APIs remain public. This is a breaking change for consumers that called
the old formatter service directly, or used the temporary daemon-control callback, but it keeps supported
extension-point
use cases while allowing lifecycle, cache, daemon state, and formatter orchestration to stay internal.
