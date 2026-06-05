package dev.ghostflyby.mcp.rest

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val TestMarkdownContentType: ContentType = ContentType("text", "markdown").withCharset(Charsets.UTF_8)

internal fun HttpResponse.responseContentType(): ContentType? {
    return headers[HttpHeaders.ContentType]?.let(ContentType::parse)
}

internal suspend fun HttpClient.firstWorkspaceRootId(projectKey: String, json: Json): String {
    return workspaceRootId(projectKey, json, index = 0)
}

internal suspend fun HttpClient.workspaceRootId(projectKey: String, json: Json, index: Int): String {
    val roots = get("/api/v1/projects/$projectKey/roots") {
        accept(ContentType.Application.Json)
    }
    return json.parseToJsonElement(roots.bodyAsText())
        .jsonArray
        .getOrNull(index)
        ?.jsonObject
        ?.get("id")
        ?.jsonPrimitive
        ?.content
        ?: error("missing workspace root id at index $index")
}

internal suspend fun HttpClient.workspaceRootIdByUrl(projectKey: String, json: Json, rootUrl: String): String {
    val roots = get("/api/v1/projects/$projectKey/roots") {
        accept(ContentType.Application.Json)
    }
    return json.parseToJsonElement(roots.bodyAsText())
        .jsonArray
        .firstOrNull { root ->
            root.jsonObject["url"]?.jsonPrimitive?.content == rootUrl
        }
        ?.jsonObject
        ?.get("id")
        ?.jsonPrimitive
        ?.content
        ?: error("missing workspace root id for $rootUrl")
}

internal suspend fun HttpClient.rootPathUrl(projectKey: String, json: Json, relativePath: String): String {
    val rootId = firstWorkspaceRootId(projectKey, json)
    return rootPathUrl(projectKey, rootId, relativePath)
}

internal suspend fun HttpClient.rootPathUrl(
    projectKey: String,
    json: Json,
    rootIndex: Int,
    relativePath: String,
): String {
    val rootId = workspaceRootId(projectKey, json, rootIndex)
    return rootPathUrl(projectKey, rootId, relativePath)
}

internal suspend fun HttpClient.rootPathUrlByRootUrl(
    projectKey: String,
    json: Json,
    rootUrl: String,
    relativePath: String,
): String {
    val rootId = workspaceRootIdByUrl(projectKey, json, rootUrl)
    return rootPathUrl(projectKey, rootId, relativePath)
}

internal fun rootUrl(projectKey: String, rootId: String): String {
    return "/api/v1/projects/$projectKey/roots/$rootId"
}

internal fun rootPathUrl(projectKey: String, rootId: String, relativePath: String): String {
    return "${rootUrl(projectKey, rootId)}/$relativePath"
}

internal fun globPathUrl(projectKey: String, rootId: String, relativePath: String): String {
    return "/api/v1/projects/$projectKey/glob/$rootId/$relativePath"
}
