package dev.ghostflyby.mcp.rest

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun HttpClient.firstWorkspaceRootId(projectKey: String, json: Json): String {
    val roots = get("/api/v1/projects/$projectKey/file-roots")
    return json.parseToJsonElement(roots.bodyAsText())
        .jsonArray
        .first()
        .jsonObject["id"]
        ?.jsonPrimitive
        ?.content
        ?: error("missing workspace root id")
}
