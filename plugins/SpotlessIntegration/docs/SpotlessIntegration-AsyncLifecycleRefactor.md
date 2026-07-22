# SpotlessIntegration Async Lifecycle Refactor

The project formatting path is coroutine-native and does not block the UI thread. PSI discovery runs in read actions,
daemon HTTP operations run from background coroutines, and synchronous formatting capability checks use a cache with
bounded asynchronous refreshes.

Daemon execution is split into two internal layers:

- `SpotlessDaemonCoordinator` owns ordered provider discovery, dynamic extension lifetimes, `ProviderSession` creation,
  provider-state collection, normalized external-project roots, target validation, provider commands, and the combined
  status snapshot consumed by the project service and widget.
- `SpotlessDaemonRegistry` owns the daemon state machine, shared startup tasks, readiness, transport invalidation,
  capability-cache invalidation, endpoint shutdown, and provider cleanup.

Each provider instance has one `ProviderSession` containing only the provider, its child `CoroutineScope`, and its
current immutable snapshot. The scope job is the attachment state. Dynamic extension removal first removes the session
from provider selection, then cancels its scope, synchronously releases its registry entries within one two-second
session deadline, publishes the provider snapshot, and returns to IntelliJ extension removal.

Providers are evaluated in IntelliJ extension-point order. A `null` target, a target whose normalized root is absent
from the provider state, or a target-resolution exception is treated as a miss and evaluation continues. The first valid
target owns the request. Startup, readiness, preprocessing transport, and formatting transport failures do not fall back
to later providers. The built-in Gradle provider has an explicit extension id and `order="last"` so other integrations
can take precedence.

Provider state uses explicit per-root generations. Adding a root updates discovery without starting a daemon. Removing a
root stops its existing daemon. Changing one root's generation restarts only an existing `Starting` or `Ready` entry for
that root; unchanged roots are untouched. Gradle sync and daemon-configuration changes advance the generation of every
currently detected linked Gradle root. When no root is detected, an otherwise equivalent empty state remains equal.

Registry keys contain provider-session identity and a normalized `Path`, so different providers can own daemon entries
for the same external-project root. Entries have only two states: `Starting` and `Ready`. Startup is created in a
registry-owned `SupervisorJob`, follows `startDaemon -> awaitReady(60s) -> Ready`, and is shared by concurrent callers.
Canceling one caller cancels only its await. Provider removal, root release, generation reconciliation, transport
failure, natural process termination, and project disposal detach the registry entry.

Detach is atomic under the registry mutex: remove the entry, invalidate the root capability cache, and publish a new
immutable runtime snapshot. Cleanup then runs outside the mutex. Batch cleanup is parallel across daemon entries, while
each daemon performs bounded HTTP stop, exactly-once LIFO provider cleanup, and startup-task join against one shared
deadline. Normal releases use five seconds; dynamic provider removal uses two seconds. A cleanup that exceeds the
deadline continues best-effort after the already-detached state has been published, without blocking extension removal.

`SpotlessProjectService` resolves a target and executes formatting through a Coordinator-owned daemon connection. It
does not access Registry or a raw endpoint. The connection performs `steps` and `format` on the selected Ready daemon.
Transport failures evict that exact Ready entry and propagate to the existing asynchronous error channel; provider or
preprocessor logic errors retain their previous graceful fallback behavior. `SpotlessFormatResult.Error` remains
reserved for daemon HTTP response results.

Status-bar availability is observed by a project activity. The Coordinator publishes domain state only; the activity is
the sole boundary that accesses IntelliJ's status-bar widget manager and is disabled in headless and unit-test
environments. Runtime presentation uses `Starting`, `Ready`, and `Not running`. The external SpotlessDaemon HTTP paths,
parameters, and response semantics are unchanged.
