# Gradle MCP Tools

<!-- Plugin description -->
MCP toolset for IntelliJ Gradle integration.

It can:

- list linked Gradle projects
- list Gradle tasks and task details
- run arbitrary Gradle tasks via the Gradle tool window backend
- sync linked Gradle projects
- cancel active Gradle execute tasks

<!-- Plugin description end -->

## Available Tools

- `list_linked_gradle_projects`
    - Returns: `{ projects: string[] }`
- `list_gradle_projects_detail`
    - Returns: `{ projects: GradleProjectDetail[] }`
- `list_gradle_tasks`
    - Returns: `{ tasks: string[] }`
- `get_gradle_task_detail`
    - Returns: `{ taskDetails: GradleTaskDetail[] }`
- `run_gradle_tasks`
    - Returns: `RunConfigurationResult` (`exitCode`, `timedOut`, `output`)
- `sync_gradle_projects`
    - Returns: `RunConfigurationResult` (`exitCode`, `timedOut`, `output`)
- `cancel_gradle_task`
    - Returns: `RunConfigurationResult` (`exitCode`, `timedOut`, `output`)

## Notes

- `externalProjectPath` supports linked project path matching (exact match preferred).
- `run_gradle_tasks` timeout attempts to cancel the running external Gradle task.
- List-like results are wrapped in serializable boundary objects to avoid top-level generic list issues.
