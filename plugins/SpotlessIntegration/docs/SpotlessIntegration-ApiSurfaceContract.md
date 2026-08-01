# SpotlessIntegration API Surface Contract

## Scope

The daemon-provider, frontend-presentation, and formatting-preprocessor extension points are intentionally public.
Coordinator, Registry, runtime snapshots, commands, capability caching, and HTTP client details remain internal. The
external SpotlessDaemon HTTP protocol is unchanged.

This is the final intentional breaking revision of the provider ABI before future split-mode module movement. Split mode
is not enabled in this phase, and no RPC contract is introduced.

## Provider API

### `dev.ghostflyby.spotless.api.SpotlessDaemonProvider`

`SpotlessDaemonProvider` is the interface of the dynamic
`dev.ghostflyby.spotless.spotlessDaemonProvider` extension point.

- `id` is a stable, non-empty, reverse-domain-style ASCII identifier. Provider instances with the same ID are resolved
  in IntelliJ extension-point order; the first is active, and the next becomes active if the first is removed.
- `state(project)` returns a stable project-lifetime `StateFlow<SpotlessDaemonProvider.State>`.
- `resolveTarget(project, file)` is a cheap, single-call route decision. `null`, an exception, or a root absent from the
  current state is a miss and allows the next provider to be considered.
- The first valid target owns the request. Startup, readiness, and operation failures never fall back to another
  provider.
- `startDaemon(context)` acquires one daemon and returns a provider-created handle. Returning the handle transfers
  ownership to the core.
- Resources remain provider-owned until `startDaemon` returns. Before-return failure or cancellation must clean them
  directly; afterward the returned lifetime job owns cleanup through its `finally` path.
- The returned job must already be started, must use a stable provider-owned scope rather than the current startup
  invocation, and must not complete before all provider resources are released.
- The API intentionally exposes neither a core `CoroutineScope` nor an IntelliJ `Disposable`.

### `SpotlessDaemonProvider.State` and `.ExternalProject`

Provider state contains `projects: List<SpotlessDaemonProvider.ExternalProject>`. Each external project has a
normalized-by-core `root` and an explicit `generation`.

- Adding a root updates discovery without starting a daemon.
- Removing a root stops an existing daemon for that root.
- Changing one root's generation restarts only an existing daemon for that root.
- Unchanged root/generation pairs cause no daemon operation.
- Providers advance generation whenever inputs affecting target resolution or daemon startup change.

### `SpotlessDaemonProvider.Target`

`SpotlessDaemonProvider.Target(externalProjectRoot, file)` is an invocation-scoped routing result. It remains separate
from the continuous provider state. The root must exist in the same provider's current state; the core normalizes and
validates it before selecting the provider.

### `SpotlessDaemonProvider.StartContext`, `.Handle`, and `.Endpoint`

`SpotlessDaemonProvider.StartContext` exposes only the project and normalized external-project root.

- `SpotlessDaemonProvider.Handle` has a public constructor so providers can return an endpoint with their own lifetime
  job. The endpoint is immediately available and immutable.
- The final composition object does not implement or delegate `Job`/`Deferred`; its `Job` represents provider lifetime
  and cleanup completion.
- Either side may cancel the lifetime job. `join()` waits until provider `finally` completes and does not propagate the
  provider exception. Registry observes `invokeOnCompletion` to preserve the original startup failure.
- The core performs the HTTP readiness check after the provider returns the handle. A lazy lifetime job is rejected.
- `SpotlessDaemonProvider.Endpoint.Localhost` and `.UnixSocket` contain connection information only. Process handles,
  temporary files, and cleanup callbacks do not cross the provider boundary.

## Frontend Presentation API

`dev.ghostflyby.spotless.api.frontend.SpotlessDaemonProviderPresentation` is the interface of the dynamic
`dev.ghostflyby.spotless.spotlessDaemonProviderPresentation` extension point.

- `providerId` associates presentation with a backend provider without exposing the provider instance to UI state.
- `presentableName` is dynamic frontend text and is annotated with `@Nls`.
- Multiple presentations for one ID use the first EP contribution.
- Missing, blank, or failing presentation falls back to the provider ID.
- The FQN and ABI are fixed so this type can move to a frontend module without changing third-party implementations.

## Formatting Preprocessor API

`SpotlessFormattingPreprocessor` remains the public interface of the dynamic
`dev.ghostflyby.spotless.spotlessFormattingPreprocessor` extension point.

- `isApplicableTo(psiFile)` runs against the actual target under read access and must be cheap.
- `preprocess(context)` may transform content and select daemon steps to skip; returning `null` leaves the request
  unchanged.
- Provider and preprocessor logic failures retain graceful fallback behavior. Daemon transport failures propagate and
  invalidate only the selected Ready daemon.
- Implementations must not retain invocation-scoped PSI and must follow IntelliJ PSI/Document threading rules.

## Ownership Boundary

Future split-mode ownership is fixed as follows:

- Backend: provider EP, formatting service, preprocessor EP, Coordinator, Registry, HTTP client, Gradle resolver/data
  service/launcher.
- Frontend: presentation EP, widget, popup, actions, configurable, and UI activity.
- Shared: future internal status/command DTOs. No public DTO or RPC API is added in this phase.

`SpotlessProjectService`, `SpotlessFormatResult`, Coordinator/Registry state, provider sessions, endpoints in active
commands, capability cache, and Gradle process types are internal implementation details.

## ABI Decision

This revision replaces `runDaemon`, endpoint publication, and the core-owned `launchHandle` factory with
`startDaemon`, input-only `SpotlessDaemonProvider.StartContext`, and the provider-created composition
`SpotlessDaemonProvider.Handle`. Provider-owned state, routing, startup, handle, and endpoint contracts are nested under
`SpotlessDaemonProvider`; formatting
preprocessor invocation types remain nested under `SpotlessFormattingPreprocessor`. A public model becomes top-level
only when multiple public extension contracts or a future shared boundary own it. This keeps provider identity and the
frontend presentation EP independent without publishing apparently general-purpose daemon models.
