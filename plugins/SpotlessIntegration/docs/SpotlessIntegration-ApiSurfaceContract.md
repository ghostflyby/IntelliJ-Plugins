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
- `state(project)` returns a stable project-lifetime `StateFlow<SpotlessDaemonProviderState>`.
- `resolveTarget(project, file)` is a cheap, single-call route decision. `null`, an exception, or a root absent from the
  current state is a miss and allows the next provider to be considered.
- The first valid target owns the request. Startup, readiness, and operation failures never fall back to another
  provider.
- `startDaemon(context)` acquires one daemon and returns the exact handle created by that context.
- Resources acquired before `launchHandle` remain provider-owned. A successful `launchHandle` return atomically
  transfers ownership to the core; the supplied lifetime block then owns cleanup from `finally`.
- The API intentionally exposes neither a raw `CoroutineScope` nor an IntelliJ `Disposable`.

### `SpotlessDaemonProviderState` and `ExternalProject`

Provider state contains `projects: List<ExternalProject>`. Each external project has a normalized-by-core `root` and an
explicit `generation`.

- Adding a root updates discovery without starting a daemon.
- Removing a root stops an existing daemon for that root.
- Changing one root's generation restarts only an existing daemon for that root.
- Unchanged root/generation pairs cause no daemon operation.
- Providers advance generation whenever inputs affecting target resolution or daemon startup change.

### `SpotlessDaemonTarget`

`SpotlessDaemonTarget(externalProjectRoot, file)` is an invocation-scoped routing result. It remains separate from the
continuous provider state. The root must exist in the same provider's current state; the core normalizes and validates
it before selecting the provider.

### `SpotlessDaemonStartContext`, `SpotlessDaemonHandle`, and `SpotlessDaemonEndpoint`

`SpotlessDaemonStartContext` exposes the project, normalized external-project root, and
`launchHandle(endpoint, lifetime)`.

- `launchHandle` may succeed exactly once and is the ownership-transfer commit point. Duplicate calls, calls after
  detach or state invalidation, and returning a foreign handle fail startup.
- `SpotlessDaemonHandle` is a final composition object. Its endpoint is immediately available and immutable; its
  `Job` represents provider lifetime and cleanup completion without implementing or delegating `Job`/`Deferred`.
- Either side may cancel the lifetime job. `join()` waits until provider `finally` completes and does not propagate the
  provider exception. Registry retains an internal `Deferred<Unit>` only to preserve the original startup failure.
- The core performs the HTTP readiness check after handle launch.
- `SpotlessDaemonEndpoint.Localhost` and `.UnixSocket` contain connection information only. Process handles, temporary
  files, and cleanup callbacks do not cross the provider boundary.

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

This revision replaces `runDaemon`, `SpotlessDaemonRunContext`, and endpoint publication with `startDaemon`,
`SpotlessDaemonStartContext`, and the final composition-based `SpotlessDaemonHandle`. It keeps the earlier removal of
`presentableName`, `SpotlessDaemonLifecycle`, and the nested `SpotlessDaemonProvider.Endpoint`, while retaining stable
provider identity, the top-level endpoint type, and the frontend presentation EP. The FQNs and fields of
`SpotlessDaemonProviderState`, `ExternalProject`, and `SpotlessDaemonTarget` remain unchanged.
