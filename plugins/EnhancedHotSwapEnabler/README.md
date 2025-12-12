# Enhanced Hotswap Enabler

[![Version](https://img.shields.io/jetbrains/plugin/v/28214.svg)](https://plugins.jetbrains.com/plugin/28214)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/28214)](https://plugins.jetbrains.com/plugin/28214)

<!-- Plugin description -->

## How to use

Select a JDK supporting dcevm (JetBrainsRuntime bundled with IntelliJ e.g.) for RunConfigurations.

Start the RunConfiguration with debug mode.

## features

1. Enables `-XX:EnhancedClassRedefinition` in RunConfigurations when supported JDKs are used.
2. Supports Gradle tasks with `JavaExec` type.
3. Auto download and configure the HotSwapAgent.
4. Configurations for features.
5. TODO: Extension points for other plugins to add custom java agents.

<!-- Plugin description end -->
