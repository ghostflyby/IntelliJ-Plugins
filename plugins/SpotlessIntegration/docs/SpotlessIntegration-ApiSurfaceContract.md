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

- `isApplicableTo(project)` is a cheap readiness/applicability check.
- `findTarget(project, virtualFile)` maps an IntelliJ file to an external project and daemon-side file path.
- `startDaemon(project, externalProject, lifecycle)` starts provider-owned daemon resources and returns a
  `SpotlessDaemonHost`.
- Provider-owned runtime resources must be registered through `lifecycle.onClose`. Provider-owned asynchronous work
  remains owned by the provider's own plugin/project scope and must be synchronously cancelled from its cleanup.
- If a provider observes natural daemon process termination, it should call `lifecycle.requestClose(reason)`. The core
  registry removes the daemon entry without using a public project-service callback.

### `dev.ghostflyby.spotless.SpotlessDaemonLifecycle`

`SpotlessDaemonLifecycle` is public because the core registry passes it to provider implementations.

Contract notes:

- `requestClose(reason)` asks the core registry to detach this daemon. Natural process termination should use this path.
- `onClose(cleanup)` registers synchronous provider-owned cleanup. Cleanup is LIFO, exactly once, and best-effort;
  providers should keep it fast and idempotent.
- Providers must not call project services or mutate registry state directly for daemon release.
- The lifecycle intentionally exposes neither a `CoroutineScope` nor a `Job`: providers cannot bypass registry detach,
  capability-cache invalidation, or HTTP stop ordering.

### `dev.ghostflyby.spotless.SpotlessDaemonHost`

`SpotlessDaemonHost` remains public because it crosses the provider/core boundary.

The concrete `Localhost` and `Unix` variants are part of that contract and therefore stay public.

### `dev.ghostflyby.spotless.SpotlessDaemonTarget`

`SpotlessDaemonTarget` is public because providers return it from `findTarget(...)`.

It contains the external project path used for daemon ownership and the concrete file path sent to the daemon.

### `dev.ghostflyby.spotless.SpotlessFormattingPreprocessor`

`SpotlessFormattingPreprocessor` is the interface of the public, dynamic
`dev.ghostflyby.spotless.spotlessFormattingPreprocessor` extension point.

Contract notes:

- `isApplicableTo(psiFile)` runs against the actual formatting target under read access and must be cheap.
- `isTriggeredBy(daemonSteps)` decides whether the daemon configuration requires the preprocessor.
- `preprocess(request)` may transform request text and return daemon step names to skip. Returning `null` leaves the
  request unchanged.
- The supplied `PsiFile` is invocation-scoped. Implementations must not retain it and must follow IntelliJ PSI and
  Document threading rules for every access.
- The core validates returned step names against daemon configuration, de-duplicates them, and preserves daemon step
  order when sending repeated `skipStep` query parameters.

### `SpotlessFormattingPreprocessRequest` and `SpotlessFormattingPreprocessResult`

These public data types carry the invocation-scoped PSI target, current request text, daemon step list, transformed
text, and requested skipped step names. They do not expose the daemon host, HTTP client, daemon lifecycle, or formatter
result.

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
