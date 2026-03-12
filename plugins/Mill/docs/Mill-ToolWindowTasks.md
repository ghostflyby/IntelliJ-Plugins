# Mill Tool Window Tasks

## Summary

The Mill plugin exposes tasks through the standard IntelliJ External System
task pipeline so the `Mill` tool window behaves like `Gradle` for task
execution.

## What Changed

- The project resolver attaches imported Mill tasks directly as
  `ProjectKeys.TASK` children of the external project node.
- The Mill external-system view contributor only customizes task display names;
  task tree rendering is delegated to IntelliJ's built-in `TasksNode` /
  `TaskNode` implementation.
- Mill registers an external-system task run configuration type so
  `ExternalSystem.RunTask` can create an execution environment for Mill tasks.

## Why

- Match Gradle's execution path instead of maintaining a parallel custom task
  tree.
- Preserve built-in double-click and context-menu task execution.
- Keep task execution routed through IntelliJ's standard external-system run
  configuration flow.
