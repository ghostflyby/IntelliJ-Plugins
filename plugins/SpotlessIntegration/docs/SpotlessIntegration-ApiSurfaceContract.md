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

- `EP_NAME` is the public, JVM-static extension-point handle for discovery and dynamic registration.
- `presentableName` identifies the provider source in project-level UI. It should describe the integration, not a daemon
  process version.
- `state(project)` returns the stable, project-owned `StateFlow` that is the provider's single source of truth. Every
  value must be immutable and structurally comparable.
- `SpotlessDaemonProviderState.externalProjects` contains the external-project roots where the provider has positively
  detected Spotless. The core copies and normalizes these paths and uses an empty result to hide provider UI.
- Provider state equality must cover `externalProjects` and every provider-specific input that can affect target
  resolution or daemon startup. `StateFlow` suppresses equal values; the initial value initializes core state without
  restarting anything, while each subsequent distinct emission restarts currently active daemons whose roots remain
  valid and stops daemons for removed roots.
- Providers may include a private revision or event counter in their state implementation when an external event must
  invalidate active daemons even though the derived roots and configuration are unchanged. Such markers are provider
  implementation details and are not part of the public state interface.
- The flow producer must be owned by a project-level service. The core collects it in a provider-specific child scope,
  which is cancelled before a dynamically removed provider extension is released.
- `resolveTarget(project, file)` performs cheap, file-specific resolution. Returning `null` lets the core try the next
  provider instead of claiming the whole project. The target's external-project root must be present in the current
  provider state; the core rejects mismatched targets.
- `startDaemon(context)` receives a core-owned `SpotlessDaemonStartContext` and returns a `Endpoint`.
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

### `dev.ghostflyby.spotless.SpotlessDaemonProviderState`

`State` is public because it is the value contract transported by the public provider state flow. It is an interface so
providers can keep configuration and invalidation details private.

Contract notes:

- `externalProjects` is the complete current set of positively detected external-project roots.
- Implementations must be immutable and implement structural `equals`/`hashCode` semantics covering all state that can
  affect `resolveTarget(...)` or `startDaemon(...)`.
- The core intentionally reads only `externalProjects`; provider-specific fields exist solely to make state equality
  reflect configuration or invalidation changes.
- The Gradle provider keeps a private synchronization generation in its implementation so every completed project
  synchronization emits a distinct state without exposing that mechanism as public API.

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

`Endpoint` remains public because it crosses the provider/core boundary.

The concrete `Localhost` and `UnixSocket` variants are part of that contract and therefore stay public. Endpoint values
contain only connection information; provider-private temp directories and process resources must stay in registered
cleanup closures.

### `dev.ghostflyby.spotless.SpotlessDaemonTarget`

`SpotlessDaemonTarget` is public because providers return it from `resolveTarget(...)`.

It contains the external project path used for daemon ownership and the concrete file path sent to the daemon. The
external project must be one currently reported by the same provider through its current state.

### `dev.ghostflyby.spotless.SpotlessFormattingPreprocessor`

`SpotlessFormattingPreprocessor` is the interface of the public, dynamic
`dev.ghostflyby.spotless.spotlessFormattingPreprocessor` extension point.

Contract notes:

- `EP_NAME` is the public, JVM-static extension-point handle for discovery and dynamic registration.
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

### `Context` and `Result`

The core-owned context interface carries the invocation-scoped PSI target, current request text, and daemon step list.
The result carries transformed text and requested skipped step names. Neither type exposes the
daemon endpoint, HTTP client, daemon lifecycle, or formatter result.

## Internal-Only Surface

The following are intentionally internal and are not part of the public ABI:

- formatter orchestration through `SpotlessProjectService`;
- `SpotlessFormatResult`;
- `SpotlessDaemonClient`;
- `SpotlessDaemonRegistry`;
- `SpotlessProviderCatalog` and `SpotlessDaemonManager`;
- `SpotlessCapabilityCache`;
- Gradle settings/runtime implementation details.

## ABI Decision

This phase intentionally removes the old public `Spotless` formatter service and public `SpotlessFormatResult`.

Only daemon-provider and formatting-preprocessor APIs remain public. This is a breaking change for consumers that called
the old formatter service directly, or used the temporary daemon-control callback, but it keeps supported
extension-point
use cases while allowing lifecycle, cache, daemon state, and formatter orchestration to stay internal.
