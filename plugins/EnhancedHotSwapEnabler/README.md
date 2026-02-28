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
3. Bundles and configures HotSwapAgent (`org.hotswapagent:hotswap-agent`, version managed in version catalog).
4. Configurations for features.
5. TODO: Extension points for other plugins to add custom java agents.

## Third-party License Notice

This plugin distribution includes HotSwapAgent (`GPL-2.0`).
Source code is available at: <https://github.com/HotswapProjects/HotswapAgent>
Detailed notice is available in `THIRD_PARTY_NOTICES.md` inside plugin resources.

<!-- Plugin description end -->
