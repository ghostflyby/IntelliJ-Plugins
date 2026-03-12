# Mill Tool Window Tasks

## Summary

The Mill plugin now exposes a dedicated external-system task tree instead of
relying on the generic IDE `TASK` contributor directly.

## What Changed

- The project resolver now stores imported Mill tasks under a Mill-specific
  `MillTasksData` node.
- The Mill external-system view contributor renders `Tasks -> <root project>`,
  then groups tasks by Mill module path and task group.
- Task leaves still use IntelliJ external-system task execution actions, so
  double-click and context-menu execution continue to flow through
  `ExternalSystem.RunTask` and `MillTaskManager`.

## Why

- Avoid duplicate task trees from combining the built-in IDE contributor with a
  Mill-specific contributor.
- Make the Mill tool window closer to Gradle's project-oriented task browsing.
- Keep task execution behavior on top of the existing External System API
  instead of introducing a parallel action path.
