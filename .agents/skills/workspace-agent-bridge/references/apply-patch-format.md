# Apply Patch Format

Load this reference only when the OpenAI Responses API `apply_patch` format is
unknown, or when a `/files` PATCH request fails because the patch body format is
wrong.

The REST `/files` PATCH route auto-detects this format when the body starts with
`*** `. The body is raw patch text, not JSON, and must not be wrapped in Markdown
code fences.

## Basic Shapes

Every patch starts with `*** Begin Patch` and ends with `*** End Patch`.

Update a file:

```patch
*** Begin Patch
*** Update File: path/to/file
@@
-old line
+new line
*** End Patch
```

In WorkspaceMcpTools REST, delete sections use IntelliJ safe-delete refactoring:
referenced files fail without `force=true` and return a references table.

Add a file:

```patch
*** Begin Patch
*** Add File: path/to/file
+content line
+another content line
*** End Patch
```

Delete a file:

```patch
*** Begin Patch
*** Delete File: path/to/file
*** End Patch
```

## Line Prefixes

| Prefix | Meaning                      |
|--------|------------------------------|
| space  | Context line kept unchanged. |
| `-`    | Remove this line.            |
| `+`    | Add this line.               |

Every hunk body line must start with `+`, `-`, or a space.

For added files, every content line, including blank lines, starts with `+`.

## Rules

- Use repository-relative paths in patch headers.
- Keep patches small and based on the current file content.
- Do not send JSON, shell heredocs, or Markdown fences as the patch body.
- For REST PATCH examples that pipe stdin through `curl`, the heredoc belongs to
  the shell command, not to the patch format itself.
- If a patch fails, reread the target file, add stable context lines, and retry
  with a smaller patch.

## Optional Operations

Move or rename while updating:

```patch
*** Begin Patch
*** Update File: old/path.txt
*** Move to: new/path.txt
@@
-old text
+new text
*** End Patch
```

In WorkspaceMcpTools REST, `*** Move to:` uses IntelliJ move refactoring and, when
the basename changes, rename refactoring.

Mark end-of-file context explicitly when needed:

```patch
*** Begin Patch
*** Update File: path/to/file
@@
 unchanged
-old final line
+new final line
*** End of File
*** End Patch
```
