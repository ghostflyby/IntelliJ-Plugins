# Navigation

Load `negotiation-and-discovery.md` first if `BASE`, `PROJECT_KEY`, or `ROOT_ID` are not known.

## Route

```text
POST /api/v1/projects/{projectKey}/navigation/{rootId}/{relativePath...}
Content-Type: text/x-patch
```

`rootId` and tailcard path locate the source file. The body uses the same `@@` hunk format
as `/files/` PATCH to select a text target.

## Operations

The body selects the operation via prefix:

```
*** Goto:
@@ hunk
```

| Prefix | Action | Result |
|--------|--------|--------|
| `*** Goto:` | Go to declaration/definition | Single target `{fileUrl, lineNumber, column}` |
| `*** Usages:` | Find all usages/implementations/overrides/inheritors | Multiple targets with `truncated` flag |
| `*** Documentation:` | Read element documentation | Element name + documentation text |

## Selection via Diff

Same `@@` hunk format as `/files/` PATCH. The `-` line identifies the target text, the `+` line
replaces the target identifier with `X` markers:

```
*** Goto:
@@
-        userRepo.findById(request.id)
+        userRepo.XXXXXXXX(request.id)
```

Multiple blocks in one request are supported.

## Examples

```bash
# Go to definition
curl -i -X POST \
  -H 'Content-Type: text/x-patch' \
  --data-binary @- \
  "$BASE/projects/$PROJECT_KEY/navigation/$ROOT_ID/src/App.kt" <<'EOF'
*** Goto:
@@
-        userRepo.findById(request.id)
+        userRepo.XXXXXXXX(request.id)
EOF

# Find all usages
curl -i -X POST \
  -H 'Content-Type: text/x-patch' \
  --data-binary @- \
  "$BASE/projects/$PROJECT_KEY/navigation/$ROOT_ID/src/UserService.kt" <<'EOF'
*** Usages:
@@
-    fun handleDelete(userId: String)
+    fun XXXXXXXXXXXX(userId: String)
EOF
```

## Response

Default Markdown format renders results as text with `goto:`/`usages:`/`documentation:`
headers and `fileUrl:line:column` targets. Use `Accept: application/json` for structured output
with `{applied, failed}` containing `{operation, path, result/results}`.
