# REST API Improvement Notes

Status: Superseded.

Earlier route-shape notes in this file explored root-scoped file routes and
typed-resource migration details. Those notes are no longer the active design.

The implemented REST file model is documented in
`rest-api-session.md`:

- create a session with `POST /api/v1/sessions`
- bind the session to a `pathPrefix`
- pass `X-Ghostflyby-Workspace-Session-Id` on file operations
- use short relative-path routes such as `/files`, `/glob`, `/search/text`, and
  `/navigation`

Project and root routes remain metadata/diagnostic routes only. They are not
file path locators.
