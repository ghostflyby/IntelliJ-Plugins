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
- `startDaemon(project, externalProject, daemonScope)` starts provider-owned daemon resources and returns a
  `SpotlessDaemonHandle`.
- Provider-owned runtime resources must be bound to `daemonScope`; the core registry owns that scope and completes it
  when the daemon is released or when the project service is disposed.
- If a provider observes natural daemon process termination, it should cancel `daemonScope`. The registry observes
  scope completion and removes the daemon entry without using a public project-service callback.

### `dev.ghostflyby.spotless.SpotlessDaemonHandle`

`SpotlessDaemonHandle` is public because provider implementations return it to the core daemon registry.

Contract notes:

- `host` describes the daemon HTTP endpoint.
- Provider cleanup is not a handle callback. Providers bind cleanup to `daemonScope` completion.

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

Only provider APIs remain public. This is a breaking change for consumers that called the old formatter service
directly,
or used the temporary daemon-control callback, but it keeps the extension-point use case while allowing lifecycle,
cache,
and daemon state to stay internal.
