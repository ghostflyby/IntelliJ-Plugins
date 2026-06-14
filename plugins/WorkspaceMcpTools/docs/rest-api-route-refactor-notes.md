# REST API Route Refactor — On-the-fly Observations

**Date:** 2026-06-12
**Context:** Implementing R1 (roots/files path separation) through the REST API itself.

## Confirmed Facts

### Fact 1: F1 (saveDocument) is partially fixed

- `FilePatchRoutes.kt` line 267: `fileDocumentManager.saveDocument(doc)` — EXISTS, PATCH writes flush to VFS
- `FileWriteRoutes.kt`: `commitDocument()` is called but `saveDocument()` is NOT — PUT/POST/DELETE do not flush
- This explains why `PUT .../RestResources.kt` returned 200 but didn't write to disk (VFS Document was updated, PSI was
  committed, but VFS->disk flush didn't happen)
- Impact: PUT is unreliable for large file rewrites; PATCH works reliably

### Fact 2: REST PATCH with fresh context works

After re-reading the file via REST GET and using exact context from git diff, all PATCH requests succeeded:

- `FileRoutes.kt`: `resource.meta` → `resource.parent.meta` (3 hunks, 1 request)
- `FileWriteRoutes.kt`: `resource.force` → `resource.parent.force` (3 hunks, 1 request)
- `FilePatchRoutes.kt`: `resource.force` → `resource.parent.force` (1 hunk)
- `FileRootTestSupport.kt`: route renames and helper refactors (3 requests)
- Rate: 100% first-try success

### Fact 3: Build succeeds

`compileKotlin + compileTestKotlin` = BUILD SUCCESSFUL. Zero errors (only pre-existing warnings).

## Summary

| Files changed          | Method                      | Status                         |
|------------------------|-----------------------------|--------------------------------|
| RestResources.kt       | REST PUT + manual save      | Added FilesEntry, cleaned Root |
| FileRoutes.kt          | apply_patch + 2x REST PATCH | Migrated, compiles             |
| FileWriteRoutes.kt     | apply_patch + 1x REST PATCH | Migrated, compiles             |
| FilePatchRoutes.kt     | apply_patch + 1x REST PATCH | Migrated, compiles             |
| FileRootTestSupport.kt | 3x REST PATCH               | Migrated, compiles             |
