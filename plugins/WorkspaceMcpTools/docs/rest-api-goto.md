# Patch-Based IntelliJ Navigation REST API

## Route

```text
POST /api/v1/navigation/{path...}
X-Ghostflyby-Workspace-Session-Id: <sessionId>
Content-Type: text/x-patch
```

The session binds the request to a path prefix. The tailcard path locates the
source file and can be relative to that prefix or a URL-encoded full VFS URL.

## Operations

The body selects the operation via prefix:

```text
*** Goto:
@@ hunk
```

| Prefix | Action | Result |
|--------|--------|--------|
| `*** Goto:` | Go to declaration/definition | Single target `{fileUrl, lineNumber, column}` |
| `*** Usages:` | Find usages/implementations/overrides/inheritors | Multiple targets with `truncated` flag |
| `*** Documentation:` | Read element documentation | Element name + documentation text |

## Selection via Diff

The `-` line identifies existing target text, and the `+` line replaces the
target identifier with `X` markers:

```text
*** Goto:
@@
-        userRepo.findById(request.id)
+        userRepo.XXXXXXXX(request.id)
```

Multiple blocks in one request are supported.

## Examples

```bash
curl -i -X POST \
  -H "X-Ghostflyby-Workspace-Session-Id: $SESSION_ID" \
  -H 'Content-Type: text/x-patch' \
  --data-binary @- \
  "$BASE/navigation/src/App.kt" <<'EOF'
*** Goto:
@@
-        userRepo.findById(request.id)
+        userRepo.XXXXXXXX(request.id)
EOF
```

Default Markdown format renders results as text with
`goto:`/`usages:`/`documentation:` headers and `fileUrl:line:column` targets.
Use `Accept: application/json` for structured output with `{applied, failed}`.
