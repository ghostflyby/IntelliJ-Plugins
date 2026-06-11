package dev.ghostflyby.mcp.rest

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.resources.href
import io.ktor.resources.serialization.ResourcesFormat
import io.ktor.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val TestMarkdownContentType: ContentType = ContentType("text", "markdown").withCharset(Charsets.UTF_8)
private const val ApiPrefix: String = "/api/v1"
private val TestResourcesFormat: ResourcesFormat = ResourcesFormat()

internal fun HttpResponse.responseContentType(): ContentType? {
    return headers[HttpHeaders.ContentType]?.let(ContentType::parse)
}

internal suspend fun HttpClient.firstWorkspaceRootId(projectKey: String, json: Json): String {
    return workspaceRootId(projectKey, json, index = 0)
}

internal suspend fun HttpClient.workspaceRootId(projectKey: String, json: Json, index: Int): String {
    val roots = get(apiUrl(Api.Project.Roots(Api.Project(projectKey)))) {
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
    val roots = get(apiUrl(Api.Project.Roots(Api.Project(projectKey)))) {
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

internal suspend fun HttpClient.rootPathUrl(
    projectKey: String,
    json: Json,
    relativePath: String,
    meta: Boolean? = null,
    content: Boolean? = null,
    exists: Boolean? = null,
    structure: Boolean? = null,
    force: Boolean? = null,
): String {
    val rootId = firstWorkspaceRootId(projectKey, json)
    return rootPathUrl(projectKey, rootId, relativePath, meta, content, exists, structure, force)
}

internal suspend fun HttpClient.rootPathUrl(
    projectKey: String,
    json: Json,
    rootIndex: Int,
    relativePath: String,
    meta: Boolean? = null,
    content: Boolean? = null,
    exists: Boolean? = null,
    structure: Boolean? = null,
    force: Boolean? = null,
): String {
    val rootId = workspaceRootId(projectKey, json, rootIndex)
    return rootPathUrl(projectKey, rootId, relativePath, meta, content, exists, structure, force)
}

internal suspend fun HttpClient.rootPathUrlByRootUrl(
    projectKey: String,
    json: Json,
    rootUrl: String,
    relativePath: String,
    meta: Boolean? = null,
    content: Boolean? = null,
    exists: Boolean? = null,
    structure: Boolean? = null,
    force: Boolean? = null,
): String {
    val rootId = workspaceRootIdByUrl(projectKey, json, rootUrl)
    return rootPathUrl(projectKey, rootId, relativePath, meta, content, exists, structure, force)
}

internal fun rootUrl(
    projectKey: String,
    rootId: String,
    meta: Boolean? = null,
    content: Boolean? = null,
    exists: Boolean? = null,
    structure: Boolean? = null,
    force: Boolean? = null,
): String {
    return apiUrl(root(projectKey, rootId), queryParameters(meta, content, exists, structure, force))
}

internal fun rootPathUrl(
    projectKey: String,
    rootId: String,
    relativePath: String,
    meta: Boolean? = null,
    content: Boolean? = null,
    exists: Boolean? = null,
    structure: Boolean? = null,
    force: Boolean? = null,
): String {
    return apiUrl(
        Api.Project.Root.File(
            parent = root(projectKey, rootId),
            relativePath = relativePath.toResourcePathSegments(),
        ),
        queryParameters(meta, content, exists, structure, force),
    )
}

internal fun globPathUrl(projectKey: String, rootId: String, relativePath: String, glob: List<String> = emptyList(), limit: Int = 0): String {
    return apiUrl(
        Api.Project.GlobEntry.Glob(
            parent = Api.Project.GlobEntry(
                parent = Api.Project(projectKey),
                rootId = rootId,
                limit = limit,
                glob = glob,
            ),
            relativePath = relativePath.toResourcePathSegments(),
        ),
        Parameters.build {
            glob.forEach { append("glob", it) }
            if (limit > 0) append("limit", limit.toString())
        },
    )
}

internal inline fun <reified T : Any> apiUrl(resource: T, query: Parameters = Parameters.Empty): String {
    val builder = URLBuilder(ApiPrefix + href(TestResourcesFormat, resource).substringBefore('?'))
    builder.parameters.appendAll(query)
    return builder.build().fullPath
}

private fun root(
    projectKey: String,
    rootId: String,
): Api.Project.Root {
    return Api.Project.Root(
        parent = Api.Project(projectKey),
        rootId = rootId,
    )
}

private fun queryParameters(
    meta: Boolean?,
    content: Boolean?,
    exists: Boolean?,
    structure: Boolean?,
    force: Boolean?,
): Parameters {
    return Parameters.build {
        meta?.let { append("meta", it.toString()) }
        content?.let { append("content", it.toString()) }
        exists?.let { append("exists", it.toString()) }
        structure?.let { append("structure", it.toString()) }
        force?.let { append("force", it.toString()) }
    }
}

private fun String.toResourcePathSegments(): List<String> = split('/').filter { it.isNotEmpty() }
