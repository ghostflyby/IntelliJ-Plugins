# SpotlessIntegration API Surface Contract

## Scope

This note records the intentionally public API after the provider lifecycle and formatting-preprocessor refactor. The
daemon-provider and formatting-preprocessor extension points remain public. Coordinator, Registry, formatting
orchestration, runtime state, capability cache, and HTTP client details remain internal. The external SpotlessDaemon
HTTP protocol is owned outside this plugin and is unchanged.

## Public Types Kept Intentionally

### `dev.ghostflyby.spotless.api.SpotlessDaemonProvider`

`SpotlessDaemonProvider` is the interface of the public
`dev.ghostflyby.spotless.spotlessDaemonProvider` extension point.

Contract notes:

- `EP_NAME` is the public, JVM-static extension-point handle. Provider evaluation follows IntelliJ extension-point
  ordering.
- `presentableName` identifies the provider source in project-level UI.
- `state(project)` returns a stable, project-owned `StateFlow<SpotlessDaemonProviderState>`.
- The flow producer must have project lifetime. The core collects it in a provider-session child scope and cancels that
  scope before a dynamically removed provider extension is released.
- `resolveTarget(project, file)` must be cheap. Returning `null`, throwing, or returning a normalized root absent from
  the current provider state lets the core try the next provider.
- The first valid target owns the request. Startup, readiness, and daemon-operation failures are propagated and never
  fall back to later providers.
- `startDaemon(context)` starts provider resources and returns an `Endpoint`; the core owns readiness checks.
- Startup must cooperate with coroutine cancellation. Register every resource through `context.lifecycle` immediately
  after creation so partial startup is cleanable during provider removal, generation changes, manual release, or project
  disposal.

### `dev.ghostflyby.spotless.api.SpotlessDaemonProviderState`

Provider state is an immutable data class containing `projects: List<ExternalProject>`.

Contract notes:

- Each `ExternalProject` contains a root and an explicit generation.
- Roots are normalized by the core. A provider should publish each root once.
- A new root updates provider discovery but does not start a daemon.
- Removing a root stops an existing daemon for that root.
- Changing a root's generation restarts only an existing daemon for that root.
- An unchanged root and generation cause no daemon operation.
- Providers must advance a root's generation whenever inputs affecting target resolution or daemon startup change.
  Provider-private revision fields and equality side channels are not part of this contract.

### `dev.ghostflyby.spotless.api.SpotlessDaemonLifecycle`

`SpotlessDaemonLifecycle` is supplied by the core to each daemon startup.

Contract notes:

- `requestClose(reason)` non-blockingly asks the Registry to detach this daemon after natural process termination.
- `registerCleanup(cleanup)` registers synchronous provider-owned cleanup in exactly-once LIFO order.
- Registering after closure runs that cleanup synchronously before `registerCleanup` returns, closing the race between
  resource creation and detach.
- Cleanup must be prompt and idempotent. Registry release waits only within its shared cleanup deadline.
- Providers must not call project services or mutate Registry state directly.
- The lifecycle intentionally exposes neither a `CoroutineScope` nor a `Job`.

### `dev.ghostflyby.spotless.api.SpotlessDaemonProvider.Endpoint`

`Endpoint` crosses the provider/core boundary. `Localhost` and `UnixSocket` contain connection information only;
provider-private process and temporary-directory resources belong in lifecycle cleanup closures.

### `dev.ghostflyby.spotless.api.SpotlessDaemonTarget`

`SpotlessDaemonTarget` contains `externalProjectRoot` for daemon ownership and `file` for the daemon request. The root
must be present in the same provider's current state. The core normalizes and validates it before startup.

### `dev.ghostflyby.spotless.api.SpotlessFormattingPreprocessor`

`SpotlessFormattingPreprocessor` is the interface of the public, dynamic
`dev.ghostflyby.spotless.spotlessFormattingPreprocessor` extension point.

Contract notes:

- `EP_NAME` is the public, JVM-static extension-point handle.
- `isApplicableTo(psiFile)` runs against the actual target under read access and must be cheap.
- `preprocess(context)` receives current content and daemon steps and may return transformed text plus step names to
  skip. Returning `null` leaves the request unchanged.
- `content` is the authoritative formatter input. `psiFile` supplies invocation context and may not reflect content
  transformed by an earlier preprocessor.
- Implementations must not retain the invocation-scoped `PsiFile` and must follow IntelliJ PSI and Document threading
  rules.
- The core validates returned step names, removes duplicates, and preserves daemon step order.
- Provider and preprocessor logic failures keep graceful fallback behavior. Daemon transport failures propagate and
  invalidate the selected Ready daemon.

### `Context` and `Result`

The preprocessing context carries the invocation-scoped PSI target, current request text, and daemon step list. The
result carries transformed text and requested skipped steps. Neither exposes the daemon endpoint, HTTP client, daemon
lifecycle, or formatter result.

## Internal-Only Surface

The following are intentionally internal and are not part of the public ABI:

- `SpotlessProjectService` formatting orchestration;
- `SpotlessFormatResult`;
- `SpotlessDaemonCoordinator` and its daemon connection;
- `SpotlessDaemonRegistry` and runtime snapshots;
- `SpotlessDaemonClient` and `SpotlessDaemonTransportException`;
- `SpotlessCapabilityCache`;
- Gradle settings and launcher implementation details.

## ABI Decision

This phase intentionally replaces the former provider-specific `SpotlessDaemonProvider.State` equality contract with
`SpotlessDaemonProviderState` and `ExternalProject(root, generation)`. It also renames
`SpotlessDaemonTarget.externalProject` to `externalProjectRoot`. These are allowed breaking Kotlin ABI changes. The
external SpotlessDaemon HTTP paths, parameters, and response meanings remain unchanged.
