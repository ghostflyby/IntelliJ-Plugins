# SpotlessIntegration Async Lifecycle Refactor

The project formatting path is coroutine-native and does not block the UI thread. PSI discovery runs in read actions,
daemon HTTP operations run from background coroutines, and synchronous formatting capability checks use a cache with
bounded asynchronous refreshes.

Daemon execution is split into three internal layers:

- `SpotlessProviderCatalog` owns dynamic provider discovery, provider state-flow lifetimes, normalized external-project
  roots, target validation, and provider-scoped command cancellation.
- `SpotlessDaemonRegistry` owns concurrent startup, provider replacement, endpoint shutdown, and exactly-once LIFO
  cleanup. It publishes an immutable runtime state flow containing only provider identity, root, and starting/running
  state. Dynamic provider removal disables the provider identity and uses a bounded synchronous detach so stale targets
  cannot recreate daemons or retain provider classes across plugin unload.
- `SpotlessDaemonManager` is a thin coordinator. It combines catalog and registry flows into the UI snapshot and exposes
  provider- or external-project-scoped control operations to the project service.

Status-bar availability is observed by a project activity. The domain manager publishes state only; the activity is the
sole boundary that accesses IntelliJ's status-bar widget manager, and it is disabled in headless and unit-test
environments. The widget popup uses the platform action-popup renderer: providers are separator headings, while each
external project exposes its own restart and stop inline actions.

The public daemon-provider extension point exposes presentation, a project-scoped state flow, target resolution, and
coroutine-aware daemon startup. Provider state is the only detected-project and startup-configuration source. The
catalog uses the initial state only for discovery, then stops and restarts currently active provider daemons after each
subsequent distinct state emission. The public state interface exposes only detected external projects; immutable
provider implementations define equality over their own target and startup inputs. Gradle keeps a private,
non-persistent synchronization generation in that implementation, so every completed re-sync replaces active Gradle
daemons after their previous cleanup finishes. Capability refresh jobs are cancelled when their daemon root is
invalidated, preventing an in-flight stale probe from recreating a daemon or repopulating a released cache entry. The
external SpotlessDaemon HTTP API remains outside this plugin's compatibility contract.
