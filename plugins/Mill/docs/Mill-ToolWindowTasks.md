# Mill Tool Window Tasks

## Summary

The Mill plugin exposes a dedicated task tree in the `Mill` tool window while
still running tasks through IntelliJ's external-system execution pipeline.

## What Changed

- The project resolver stores imported Mill tasks under a Mill-specific
  `MillTasksData` node.
- The Mill tool window renders tasks by splitting the original task name on
  `.` and building a nested path tree from the raw segments.
- No synthetic task groups or display-name rewriting are applied.
- Mill registers an external-system task run configuration type so both the
  custom `Run` action and double-click execution can create an execution
  environment for Mill tasks.

## Why

- Keep the original Mill task names visible instead of inventing Gradle-like
  groups.
- Make long Mill task names easier to scan by using their natural dotted path.
- Preserve task execution on top of IntelliJ's standard external-system run
  configuration flow.
