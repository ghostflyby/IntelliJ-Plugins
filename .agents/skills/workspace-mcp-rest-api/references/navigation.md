# Navigation

Load `negotiation-and-discovery.md` first if `BASE` or `SESSION_ID` are not known.

## Route

```text
POST /api/v1/navigation/{path...}
X-Ghostflyby-Workspace-Session-Id: <sessionId>
Content-Type: text/x-patch
```

The tailcard path locates the source file. It can be relative to the session
`pathPrefix` or a URL-encoded full VFS URL.
The body uses the same `@@` hunk format as `/files` PATCH to select a text
target.

## Operations

```text
*** Goto:
@@ hunk
```

| Prefix | Action | Result |
|--------|--------|--------|
| `*** Goto:` | Go to declaration/definition | Single target `{fileUrl, lineNumber, column}` |
| `*** Usages:` | Find usages/implementations/overrides/inheritors | Multiple targets with `truncated` flag |
| `*** Documentation:` | Read element documentation | Element name + documentation text |

## Example

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

Example body:

```text
goto: src/App.kt
  → file:///workspace/src/UserRepository.kt:42:9
usages: src/App.kt
  → file:///workspace/src/App.kt:18:18
documentation: src/App.kt
  name: findById
  Finds a user by id.
```
