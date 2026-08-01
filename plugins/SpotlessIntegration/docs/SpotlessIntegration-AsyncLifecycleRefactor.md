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
entry is either `Starting` or `Ready` and references one `DaemonExecution` with its own `SupervisorJob`, execution
scope, shared startup task, and optional observed provider handle.

The execution job is a child of the Registry `SupervisorJob` and owns startup/readiness only. The provider lifetime job
uses a provider-owned stable scope and is observed separately. Neither task belongs to the first request; callers only
await the shared startup task, so canceling one caller does not cancel daemon startup or lifetime.

Startup is:

```text
provider startDaemon(context)
-> atomically register and observe the returned handle
-> race HTTP readiness (60 seconds) against lifetime completion
-> atomically publish Ready if the same execution and generation are current
```

Returning a handle is the ownership-transfer point. The provider must return an already-started, non-lazy job whose
completion includes cleanup. Registry installs its completion observer and registers the handle in a short
non-cancellable section. A handle returned after detach is stopped and cancelled without restoring logical state.
Provider failure and lifetime completion are rejected before Ready publication. Completion from an old execution
conditionally detaches only an entry still pointing to the same execution and observed handle.

## Bidirectional Termination

Provider-initiated termination is the handle lifetime job completing normally, exceptionally, or through provider-side
cancellation. Provider `finally` performs process-side cleanup; Registry removes the matching entry, invalidates
capability cache, and publishes runtime state without sending `/stop`. The public handle exposes only `Job`; Registry
uses a private one-shot termination signal populated by `Job.invokeOnCompletion` so a failure during startup retains its
original cause. Coroutine stack-trace recovery may copy the throwable instance while retaining its type, message, and
original cause.

Core-initiated termination is:

```text
Mutex: detach entry -> invalidate cache -> publish snapshot
Outside Mutex: best-effort HTTP /stop when endpoint exists
-> cancel the provider lifetime job and execution job
-> join both jobs under the same deadline
```

Readiness failure, generation change, transport failure, manual stop/restart, provider removal, and project disposal all
use the core path. A `Starting` execution with a returned handle still receives `/stop`; one without a handle cancels
startup and relies on provider pre-return cleanup. A provider that ignores cancellation and returns late is cleaned up
best-effort without reattaching. `/stop`-driven process exit and subsequent cancellation are idempotent because every
completion handler checks execution and handle identity. Replacement always creates a new immutable handle after the old
execution cleanup path; endpoints are never replaced inside a handle.

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
