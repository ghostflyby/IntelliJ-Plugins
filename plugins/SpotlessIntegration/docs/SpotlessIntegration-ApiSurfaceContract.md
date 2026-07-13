# SpotlessIntegration API Surface Contract

## Scope

This note records the intentionally public API surface after the provider lifecycle refactor.

The plugin keeps a public daemon-provider extension point, but the formatter orchestration,
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
- `startDaemon(project, externalProject)` starts provider-owned daemon resources and returns a `SpotlessDaemonHandle`.

### `dev.ghostflyby.spotless.SpotlessDaemonHandle`

`SpotlessDaemonHandle` is public because provider implementations return it to the core daemon registry.

Contract notes:

- `host` describes the daemon HTTP endpoint.
- `cleanup(reason)` releases provider-owned resources after the core service has attempted the fixed HTTP stop request.
- `cleanup(reason)` must be best-effort and idempotent.

### `dev.ghostflyby.spotless.SpotlessDaemonControl`

`SpotlessDaemonControl` is public because provider-owned process listeners need a stable project-service callback.

Provider implementations should call `project.service<SpotlessDaemonControl>().releaseDaemon(host)` when a daemon
process exits or otherwise becomes unusable.

### `dev.ghostflyby.spotless.SpotlessDaemonHost`

`SpotlessDaemonHost` remains public because it crosses the provider/core boundary.

The concrete `Localhost` and `Unix` variants are part of that contract and therefore stay public.

### `dev.ghostflyby.spotless.SpotlessDaemonTarget`

`SpotlessDaemonTarget` is public because providers return it from `findTarget(...)`.

It contains the external project path used for daemon ownership and the concrete file path sent to the daemon.

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

Only provider/control APIs remain public. This is a breaking change for consumers that called the old formatter service
directly, but it keeps the extension-point use case while allowing lifecycle, cache, and daemon state to stay internal.
