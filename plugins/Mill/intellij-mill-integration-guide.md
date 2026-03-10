# IntelliJ Platform Integration Guide: Adding Support for the Mill Build Tool

## 1. Overview

This document describes how to integrate the **Mill build tool** into
the IntelliJ Platform as a first-class build system. The integration is
implemented through IntelliJ's **External System API**, which is also
used by the Gradle and Maven plugins.

The goal of the integration is to provide:

- Project import from Mill builds
- Build and task execution
- Tool window integration
- Dependency and module model synchronization
- Automatic re-import when build files change
- Optional run configurations and build output parsing

The core architecture follows the IntelliJ **External System integration
pattern**.

------------------------------------------------------------------------

# 2. Architecture

A typical External System plugin consists of the following components:

    MillExternalSystemManager
            │
            ├── MillProjectResolver
            │        │
            │        └── DataNode<ProjectData> graph
            │
            ├── MillTaskManager
            │
            ├── Settings
            │        ├── MillSettings
            │        ├── MillLocalSettings
            │        └── MillExecutionSettings
            │
            └── UI Integration
                     ├── Tool Window
                     ├── Import Wizard
                     └── Run Configuration (optional)

------------------------------------------------------------------------

# 3. Required IntelliJ Extension Points

## 3.1 External System Manager

Extension point:

    com.intellij.externalSystemManager

Example plugin.xml entry:

``` xml
<extensions defaultExtensionNs="com.intellij">
  <externalSystemManager
      implementation="org.example.mill.MillExternalSystemManager"/>
</extensions>
```

Implementation:

``` java
public class MillExternalSystemManager
  implements ExternalSystemManager<
        MillSettings,
        MillLocalSettings,
        MillExecutionSettings,
        MillProjectResolver,
        MillTaskManager> {
}
```

Responsibilities:

- Declare the build system identifier (`ProjectSystemId`)
- Provide resolver and task manager implementations
- Connect settings classes

Example system id:

    ProjectSystemId MILL

------------------------------------------------------------------------

# 4. Project Import

## 4.1 Project Resolver

Class:

    ExternalSystemProjectResolver

Implementation:

``` java
public class MillProjectResolver
  implements ExternalSystemProjectResolver<MillExecutionSettings>
```

Responsibilities:

- Execute Mill commands
- Extract project structure
- Build IntelliJ project model

Typical Mill commands used:

    mill resolve _
    mill show module.compileClasspath
    mill show module.sources

------------------------------------------------------------------------

## 4.2 Project Model Mapping

The resolver constructs a **DataNode graph**.

Example structure:

    ProjectData
     ├─ ModuleData (module A)
     │   ├─ ContentRootData
     │   ├─ LibraryDependencyData
     │   └─ ModuleDependencyData
     │
     └─ ModuleData (module B)
         ├─ ContentRootData
         └─ LibraryDependencyData

Typical fields:

### ProjectData

    name
    linkedExternalProjectPath
    externalSystemId

### ModuleData

    moduleName
    moduleFileDirectoryPath
    moduleType

### ContentRootData

    sourceRoots
    testRoots
    resourceRoots
    excludedRoots

### Dependencies

    LibraryDependencyData
    ModuleDependencyData

------------------------------------------------------------------------

# 5. Task Execution

## 5.1 Task Manager

Implementation:

``` java
public class MillTaskManager
  implements ExternalSystemTaskManager<MillExecutionSettings>
```

Responsibilities:

- Execute tasks triggered by IntelliJ
- Forward commands to Mill CLI

Example mapping:

    IDE build     -> mill __.compile
    IDE test      -> mill __.test
    IDE run task  -> mill module.run

Execution flow:

    IDE
     ↓
    ExternalSystemTaskManager
     ↓
    Mill CLI process
     ↓
    stdout/stderr streamed to IDE

------------------------------------------------------------------------

# 6. Settings

## 6.1 Project Settings

    AbstractExternalSystemSettings

Example:

    MillSettings

Stores project-level configuration such as:

    mill executable path
    import options
    JVM parameters

------------------------------------------------------------------------

## 6.2 Local Settings

    AbstractExternalSystemLocalSettings

Example:

    MillLocalSettings

Used for:

- IDE caches
- resolved tasks
- linked project metadata

------------------------------------------------------------------------

## 6.3 Execution Settings

    ExternalSystemExecutionSettings

Example:

    MillExecutionSettings

Used during task execution.

Typical fields:

    mill command path
    environment variables
    vm options

------------------------------------------------------------------------

# 7. Tool Window Integration

Extension point:

    externalSystemViewContributor

Example plugin.xml entry:

``` xml
<extensions defaultExtensionNs="com.intellij">
  <externalSystemViewContributor
      implementation="org.example.mill.MillViewContributor"/>
</extensions>
```

Example UI:

    Mill
     ├─ moduleA
     │   ├─ compile
     │   ├─ test
     │   └─ run
     │
     └─ moduleB
         ├─ compile
         └─ test

------------------------------------------------------------------------

# 8. Import Wizard Integration

Relevant classes:

    AbstractExternalProjectSettingsControl
    ExternalSystemSettingsControl

Displayed during:

    File → New → Project from Existing Sources

------------------------------------------------------------------------

# 9. Automatic Project Reimport

Extension point:

    ExternalSystemAutoImportAware

Monitored files:

    build.sc
    mill.sc

------------------------------------------------------------------------

# 10. Build Output Parsing

Approaches:

    ExternalSystemTaskNotificationListener
    BuildOutputParser

Used to surface:

    compile errors
    warnings
    test failures

------------------------------------------------------------------------

# 11. Run Configuration Support

Extension point:

    com.intellij.configurationType

Example:

    MillRunConfiguration

Allows:

    Run → Mill Task

------------------------------------------------------------------------

# 12. File Recognition (Optional)

Register:

    FileType
    Language
    ParserDefinition

Example:

    build.sc

------------------------------------------------------------------------

# 13. Minimal Implementation Set

    externalSystemManager
    ExternalSystemProjectResolver
    ExternalSystemTaskManager
    settings classes
    externalSystemViewContributor

Example structure:

    MillExternalSystemManager
    MillProjectResolver
    MillTaskManager

    MillSettings
    MillLocalSettings
    MillExecutionSettings

    MillViewContributor

------------------------------------------------------------------------

# 14. Reference Implementations

Gradle plugin:

    intellij-community/plugins/gradle

Key classes:

    GradleManager
    GradleProjectResolver
    GradleTaskManager

Maven plugin:

    intellij-community/plugins/maven

------------------------------------------------------------------------

# 15. Implementation Strategy

Recommended order:

1. ExternalSystemManager
2. ProjectResolver
3. TaskManager
4. Settings classes
5. Tool window integration
6. Auto-import
7. Run configuration
8. Build output parsing

------------------------------------------------------------------------

# 16. Summary

Mill integration into IntelliJ relies on the **External System API**.
The essential components are:

    ExternalSystemManager
    ProjectResolver
    TaskManager
    Settings
    ToolWindow integration
