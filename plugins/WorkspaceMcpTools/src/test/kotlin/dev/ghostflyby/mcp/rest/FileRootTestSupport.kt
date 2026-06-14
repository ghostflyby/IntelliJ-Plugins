package dev.ghostflyby.mcp.rest

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.resources.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val TestMarkdownContentType: ContentType = ContentType("text", "markdown").withCharset(Charsets.UTF_8)
private const val ApiPrefix: String = "/api/v1"
private val TestResourcesFormat: ResourcesFormat = ResourcesFormat()

internal fun HttpResponse.responseContentType(): ContentType? {
    return headers[HttpHeaders.ContentType]?.let(ContentType::parse)
}

internal suspend fun HttpClient.createRestSessionId(pathPrefix: String, json: Json): String {
    val response = post(apiUrl(Api.Sessions())) {
        contentType(ContentType.Application.Json)
        accept(ContentType.Application.Json)
        setBody("""{"pathPrefix": "$pathPrefix"}""")
    }
    return json.parseToJsonElement(response.bodyAsText())
        .jsonObject["sessionId"]
        ?.jsonPrimitive
        ?.content
        ?: error("missing REST session id")
}

internal suspend fun HttpClient.withRestSession(pathPrefix: String, json: Json): HttpClient {
    val sessionId = createRestSessionId(pathPrefix, json)
    return config {
        defaultRequest {
            header(RestSessionHeader, sessionId)
        }
    }
}

internal fun HttpClient.rootPathUrl(
    relativePath: String,
    meta: Boolean? = null,
    content: Boolean? = null,
    exists: Boolean? = null,
    structure: Boolean? = null,
    force: Boolean? = null,
    startLine: Int? = null,
    endLine: Int? = null,
    maxLines: Int? = null,
    aroundLine: Int? = null,
    radius: Int? = null,
): String {
    return dev.ghostflyby.mcp.rest.rootPathUrl(
        relativePath,
        meta,
        content,
        exists,
        structure,
        force,
        startLine,
        endLine,
        maxLines,
        aroundLine,
        radius,
    )
}

internal fun HttpClient.rootPathUrlByRootUrl(
    relativePath: String,
    meta: Boolean? = null,
    content: Boolean? = null,
    exists: Boolean? = null,
    structure: Boolean? = null,
    force: Boolean? = null,
    startLine: Int? = null,
    endLine: Int? = null,
    maxLines: Int? = null,
    aroundLine: Int? = null,
    radius: Int? = null,
): String {
    return rootPathUrl(
        relativePath,
        meta,
        content,
        exists,
        structure,
        force,
        startLine,
        endLine,
        maxLines,
        aroundLine,
        radius,
    )
}

internal fun rootUrl(
    projectKey: String,
    rootId: String,
    meta: Boolean? = null,
    content: Boolean? = null,
    exists: Boolean? = null,
    structure: Boolean? = null,
    force: Boolean? = null,
    startLine: Int? = null,
    endLine: Int? = null,
    maxLines: Int? = null,
    aroundLine: Int? = null,
    radius: Int? = null,
): String {
    return apiUrl(
        Api.Project.Root(Api.Project(projectKey), rootId),
        queryParameters(meta, content, exists, structure, force, startLine, endLine, maxLines, aroundLine, radius),
    )
}

internal fun rootPathUrl(
    relativePath: String,
    meta: Boolean? = null,
    content: Boolean? = null,
    exists: Boolean? = null,
    structure: Boolean? = null,
    force: Boolean? = null,
    startLine: Int? = null,
    endLine: Int? = null,
    maxLines: Int? = null,
    aroundLine: Int? = null,
    radius: Int? = null,
): String {
    return apiUrl(
        Api.FilesEntry.File(
            parent = fileEntry(),
            path = relativePath.toResourcePathSegments(),
        ),
        queryParameters(meta, content, exists, structure, force, startLine, endLine, maxLines, aroundLine, radius),
    )
}

internal fun globPathUrl(
    relativePath: String,
    glob: List<String> = emptyList(),
    limit: Int = 0,
): String {
    return apiUrl(
        Api.GlobEntry.Glob(
            parent = Api.GlobEntry(
                limit = limit,
                glob = glob,
            ),
            path = relativePath.toResourcePathSegments(),
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

private fun fileEntry(): Api.FilesEntry = Api.FilesEntry()

private fun queryParameters(
    meta: Boolean?,
    content: Boolean?,
    exists: Boolean?,
    structure: Boolean?,
    force: Boolean?,
    startLine: Int? = null,
    endLine: Int? = null,
    maxLines: Int? = null,
    aroundLine: Int? = null,
    radius: Int? = null,
): Parameters {
    return Parameters.build {
        meta?.let { append("meta", it.toString()) }
        content?.let { append("content", it.toString()) }
        exists?.let { append("exists", it.toString()) }
        structure?.let { append("structure", it.toString()) }
        force?.let { append("force", it.toString()) }
        startLine?.let { append("startLine", it.toString()) }
        endLine?.let { append("endLine", it.toString()) }
        maxLines?.let { append("maxLines", it.toString()) }
        aroundLine?.let { append("aroundLine", it.toString()) }
        radius?.let { append("radius", it.toString()) }
    }
}

private fun String.toResourcePathSegments(): List<String> = split('/').filter { it.isNotEmpty() }

internal fun searchTextUrl(
    relativePath: String = "",
    query: String = "",
    regex: Boolean = false,
    caseSensitive: Boolean = true,
    wholeWord: Boolean = false,
    context: List<String> = listOf("string", "comment", "other"),
    fileFilter: String? = null,
    limit: Int = 100,
): String {
    return apiUrl(
        Api.SearchTextEntry.SearchText(
            parent = Api.SearchTextEntry(
                query = query,
                regex = regex,
                caseSensitive = caseSensitive,
                wholeWord = wholeWord,
                context = context,
                fileFilter = fileFilter,
                limit = limit,
            ),
            path = relativePath.toResourcePathSegments(),
        ),
        Parameters.build {
            append("query", query)
            append("regex", regex.toString())
            append("caseSensitive", caseSensitive.toString())
            append("wholeWord", wholeWord.toString())
            context.forEach { append("context", it) }
            fileFilter?.let { append("fileFilter", it) }
            append("limit", limit.toString())
        },
    )
}

internal fun searchSymbolsUrl(
    query: String = "",
    libraries: Boolean = false,
    kind: String? = null,
    limit: Int = 50,
    timeoutMillis: Int = 20_000,
): String {
    return apiUrl(
        Api.SearchSymbolsEntry(
            query = query,
            libraries = libraries,
            kind = kind,
            limit = limit,
            timeoutMillis = timeoutMillis,
        ),
        Parameters.build {
            append("query", query)
            append("libraries", libraries.toString())
            kind?.let { append("kind", it) }
            append("limit", limit.toString())
            append("timeoutMillis", timeoutMillis.toString())
        },
    )
}

internal fun searchFilesUrl(
    query: String = "",
    limit: Int = 50,
    timeoutMillis: Int = 20_000,
): String {
    return apiUrl(
        Api.SearchFilesEntry(
            query = query,
            limit = limit,
            timeoutMillis = timeoutMillis,
        ),
        Parameters.build {
            append("query", query)
            append("limit", limit.toString())
            append("timeoutMillis", timeoutMillis.toString())
        },
    )
}

internal fun navigationUrl(
    relativePath: String,
): String {
    return apiUrl(
        Api.NavigationPath(
            path = relativePath.toResourcePathSegments(),
        ),
    )
}
