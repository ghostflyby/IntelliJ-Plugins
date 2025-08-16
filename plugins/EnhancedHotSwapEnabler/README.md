<!-- Plugin description -->

# How to use

Select a JDK supporting dcevm (JetBrainsRuntime bundled with IntelliJ e.g.) for RunConfigurations.

Start the RunConfiguration with debug mode.

# features

1. Enables `-XX:EnhancedClassRedefinition` in RunConfigurations when supported JDKs are used.
2. Supports gradle tasks with `JavaExec` type.
3. TODO: Auto download and configure the HotSwapAgent.
4. TODO: Configurations for features.
5. TODO: Extension points for other plugins to add custom java agents.
<!-- Plugin description end -->
