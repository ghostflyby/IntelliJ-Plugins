# SpotlessIntegration Async Lifecycle Refactor

Spotless daemon control has two internal owners:

- `SpotlessDaemonCoordinator` owns ordered provider discovery, stable IDs, dynamic extension sessions, provider-state
  collection, target selection, commands, and the combined status snapshot.
- `SpotlessDaemonRegistry` owns daemon executions, `Starting`/`Ready` transitions, readiness, transport invalidation,
  HTTP stop, cancellation, deadlines, and runtime snapshots.

## Provider Sessions And Selection

A `ProviderSession` contains the provider, its child scope, stable ID, and current immutable snapshot. The scope job is
the attachment state. Coordinator also keeps a unique ID-to-session index so commands and UI state do not retain backend
provider objects.

Provider IDs and targets follow IntelliJ EP order. Duplicate IDs keep the first contribution; removal promotes the next
one. Invalid target results are misses. Once the first valid target is selected, startup or runtime failure does not
fall back.

Dynamic provider removal removes the session from selection, cancels its state collector, synchronously releases its
Registry entries under one two-second deadline, publishes provider state, and then returns to the EP removal boundary.

## Daemon Execution

Registry keys are `(ProviderSession, normalizedRoot)`, so different providers may own daemons for the same root. Each
entry is either `Starting` or `Ready` and references one `DaemonExecution` containing the endpoint signal, provider
lifetime job, and shared startup task.

Both jobs belong to the Registry `SupervisorJob`, not to the first request. Callers only await the shared startup task;
canceling one caller does not cancel daemon startup.

Startup is:

```text
launch provider runDaemon
-> await exactly one published endpoint
-> HTTP readiness (60 seconds)
-> atomically publish Ready if the same execution is still current
```

Provider return, provider failure, duplicate publication, and cancellation are signaled before Ready publication is
allowed. Completion from an old execution conditionally detaches only an entry still pointing to that execution.

## Bidirectional Termination

Provider-initiated termination is `runDaemon` returning or throwing. Provider `finally` performs process-side cleanup;
Registry removes the matching entry, invalidates capability cache, and publishes runtime state without sending
`/stop`.

Core-initiated termination is:

```text
Mutex: detach entry -> invalidate cache -> publish snapshot
Outside Mutex: best-effort HTTP /stop when endpoint exists
-> cancel provider lifetime
-> wait for provider finally and startup completion
```

Readiness failure, generation change, transport failure, manual stop/restart, provider removal, and project disposal all
use the core path. A `Starting` execution whose endpoint is already published still receives `/stop`; one without an
endpoint is canceled directly. `/stop`-driven process exit and subsequent cancellation are idempotent because every
completion handler checks execution identity.

Normal release uses one five-second deadline. Dynamic provider removal uses one two-second deadline. HTTP stop,
cancellation, provider `finally`, and joins consume the same remaining time. Logical state stays detached after timeout;
provider cleanup running in `NonCancellable` may continue best-effort.

## Formatting And UI

Formatting follows `resolveTarget -> Coordinator.withDaemon -> steps/format`. No per-request health check is performed.
Only transport exceptions evict the exact Ready entry. HTTP response errors remain `SpotlessFormatResult.Error`;
startup, readiness, and transport failures propagate through the existing async error channel.

Coordinator status contains provider IDs, roots, and `Starting`/`Ready` state only. The frontend presentation EP
resolves display names dynamically with ID fallback. Widget activity remains the sole status-bar platform boundary and
is disabled in headless/unit-test environments.

Current packaging remains monolithic. Future split-mode ownership is backend for provider/runtime/Gradle integration,
frontend for presentation and UI, and shared for future internal status/command DTOs. No RPC or split-mode descriptor is
introduced by this refactor.
