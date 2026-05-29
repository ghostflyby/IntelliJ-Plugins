/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import io.ktor.resources.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

internal data class ParsedWorkspaceUrl(
    val instanceKey: String,
    val pathAndQuery: String,
)

internal fun parseWorkspaceUrl(uri: String): ParsedWorkspaceUrl? {
    val schemeEnd = uri.indexOf("://")
    if (schemeEnd < 0) return null
    val afterScheme = uri.substring(schemeEnd + 3)
    val firstSlash = afterScheme.indexOf('/')
    if (firstSlash < 0) return null
    val instanceKey = afterScheme.substring(0, firstSlash)
    if (instanceKey.isBlank()) return null
    return ParsedWorkspaceUrl(
        instanceKey = instanceKey,
        pathAndQuery = afterScheme.substring(firstSlash),
    )
}

internal data class ResourceClassInfo(
    val pathSegments: List<PathSegmentInfo>,
    val queryParams: List<QueryParamInfo>,
    val tailParamName: String?,
    val parentName: String?,
) {
    val pathParamNames: Set<String> = pathSegments.mapNotNull { it.paramName }.toSet()

    companion object {
        private val PARAM_NAME_RE = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

        fun from(descriptor: SerialDescriptor): ResourceClassInfo {
            val resourceAnn = descriptor.annotations.filterIsInstance<Resource>().firstOrNull()
                ?: throw IllegalArgumentException("${descriptor.serialName} is not annotated with @Resource")
            val parentElement = findParentElement(descriptor)
            val parentInfo = parentElement?.let { from(descriptor.getElementDescriptor(it)) }
            val path = resourceAnn.path.trimStart('/')
            val ownPathSegments = parsePathSegments(path)
            val pathSegments = parentInfo?.pathSegments.orEmpty() + ownPathSegments
            val pathParamNames = pathSegments.mapNotNull { it.paramName }.toSet()
            val tailParamName = pathSegments.firstOrNull { it.isTail }?.paramName
            require(pathSegments.count { it.isTail } <= 1) {
                "Only one tail path parameter is allowed in resource path: ${descriptor.serialName}"
            }
            require(pathSegments.indexOfFirst { it.isTail }.let { it < 0 || it == pathSegments.lastIndex }) {
                "Tail path parameter must be the last segment: ${descriptor.serialName}"
            }

            val queryParams = (0 until descriptor.elementsCount).mapNotNull { i ->
                val name = descriptor.getElementName(i)
                if (i == parentElement) return@mapNotNull null
                if (name in pathParamNames) return@mapNotNull null
                QueryParamInfo(
                    name = name,
                    isOptional = descriptor.isElementOptional(i),
                )
            }

            return ResourceClassInfo(
                pathSegments = pathSegments,
                queryParams = queryParams,
                tailParamName = tailParamName,
                parentName = parentElement?.let { descriptor.getElementName(it) },
            )
        }

        private fun findParentElement(descriptor: SerialDescriptor): Int? {
            val candidates = (0 until descriptor.elementsCount).filter { i ->
                descriptor.getElementDescriptor(i).annotations.any { it is Resource }
            }
            require(candidates.size <= 1) {
                "Only one parent resource is supported for ${descriptor.serialName}"
            }
            return candidates.firstOrNull()
        }

        private fun parsePathSegments(path: String): List<PathSegmentInfo> {
            if (path.isEmpty()) return emptyList()
            val parts = path.split('/')
            return parts.mapIndexed { index, part ->
                when {
                    part.endsWith("...}") && part.startsWith("{") -> {
                        val name = part.substring(1, part.length - 4)
                        require(name.isNotBlank())
                        require(PARAM_NAME_RE.matches(name))
                        require(index == parts.lastIndex) { "Tail path parameter must be the last segment: $path" }
                        PathSegmentInfo(text = part, name = part, isParameter = true, paramName = name, isTail = true)
                    }
                    part.startsWith("{") && part.endsWith("}") -> {
                        val name = part.substring(1, part.length - 1)
                        require(name.isNotBlank())
                        require(PARAM_NAME_RE.matches(name))
                        PathSegmentInfo(text = part, name = part, isParameter = true, paramName = name, isTail = false)
                    }
                    else -> {
                        require(part.isNotBlank())
                        PathSegmentInfo(text = part, name = part, isParameter = false, paramName = null, isTail = false)
                    }
                }
            }
        }
    }
}

internal data class PathSegmentInfo(
    val text: String,
    val name: String,
    val isParameter: Boolean,
    val paramName: String?,
    val isTail: Boolean,
)

internal data class QueryParamInfo(
    val name: String,
    val isOptional: Boolean,
)

internal class WorkspaceResourceUriFormat : StringFormat {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String {
        val info = ResourceClassInfo.from(serializer.descriptor)
        val encoder = UrlBuildingEncoder(info)
        serializer.serialize(encoder, value)
        return encoder.result
    }

    override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T {
        val info = ResourceClassInfo.from(deserializer.descriptor)
        val params = tryMatch(string, info) ?: throw IllegalArgumentException("URI does not match route: $string")
        return decodeFromParams(params, info, deserializer)
    }

    /**
     * Try to match a workspace URI against a [ResourceClassInfo].
     * Returns captured path + query parameters as a map, or null if the URI does not match.
     */
    internal fun tryMatch(url: String, info: ResourceClassInfo): Map<String, String>? {
        val parsed = parseWorkspaceUrl(url) ?: return null
        val pathAndQuery = parsed.pathAndQuery.removePrefix("/")
        val queryCandidate = splitPathAndQuery(pathAndQuery, info)
        val pathOnly = queryCandidate.path
        val pathParts = pathOnly.split("/")
        var segIdx = 0
        val params = mutableMapOf<String, String>()
        params["instanceKey"] = parsed.instanceKey

        for (seg in info.pathSegments) {
            when {
                seg.isTail -> {
                    params[seg.paramName!!] = pathParts.drop(segIdx).joinToString("/")
                    segIdx = pathParts.size
                }
                seg.isParameter -> {
                    if (segIdx >= pathParts.size) return null
                    params[seg.paramName!!] = pathParts[segIdx]
                    segIdx++
                }
                else -> {
                    if (segIdx >= pathParts.size || pathParts[segIdx] != seg.text) return null
                    segIdx++
                }
            }
        }
        if (segIdx != pathParts.size) return null
        if (queryCandidate.queryString.isNotEmpty()) {
            queryCandidate.queryString.split("&").filter { it.isNotBlank() }.forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq >= 0) params[pair.substring(0, eq)] = pair.substring(eq + 1)
                else params[pair] = ""
            }
        }
        return params
    }

    internal fun <T> decodeFromParams(
        params: Map<String, String>,
        info: ResourceClassInfo,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val decoder = UrlParsingDecoder(params, info)
        return deserializer.deserialize(decoder)
    }

    fun templateUri(descriptor: SerialDescriptor): String {
        val info = ResourceClassInfo.from(descriptor)
        return info.templateUri()
    }

    // -- Encoder --

    private class UrlBuildingEncoder(
        private val info: ResourceClassInfo,
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : Encoder, CompositeEncoder {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        val result: String
            get() {
                val pathParts = info.pathSegments.map { seg ->
                    when {
                        seg.isTail -> values[seg.paramName] ?: ""
                        seg.isParameter -> values[seg.paramName] ?: ""
                        else -> seg.text
                    }
                }
                val queryParts = info.queryParams.mapNotNull { qp ->
                    values[qp.name]?.let { v ->
                        if (v.isNotEmpty()) "${qp.name}=$v" else qp.name
                    }
                }
                val path = pathParts.joinToString("/")
                val query = if (queryParts.isNotEmpty()) "?${queryParts.joinToString("&")}" else ""
                return "ij-workspace://{instanceKey}/$path$query"
            }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = this
        override fun endStructure(descriptor: SerialDescriptor) {}
        override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) { values[descriptor.getElementName(index)] = value }
        override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) { values[descriptor.getElementName(index)] = value.toString() }
        override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) { values[descriptor.getElementName(index)] = value.toString() }
        override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) { values[descriptor.getElementName(index)] = value.toString() }
        override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) { values[descriptor.getElementName(index)] = value.toString() }
        override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) { values[descriptor.getElementName(index)] = value.toString() }
        override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) { values[descriptor.getElementName(index)] = value.toString() }
        override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) { values[descriptor.getElementName(index)] = value.toString() }
        override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) { values[descriptor.getElementName(index)] = value.toString() }
        override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T,
        ) {
            if (descriptor.getElementName(index) != info.parentName) unsupported()
            serializer.serialize(UrlBuildingEncoder(ResourceClassInfo.from(serializer.descriptor), values), value)
        }
        @OptIn(ExperimentalSerializationApi::class)
        override fun <T : Any> encodeNullableSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T?,
        ) {
            if (value == null) return
            values[descriptor.getElementName(index)] = value.toString()
        }
        override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder = this
        override fun encodeBoolean(value: Boolean) = unsupported()
        override fun encodeByte(value: Byte) = unsupported()
        override fun encodeShort(value: Short) = unsupported()
        override fun encodeInt(value: Int) = unsupported()
        override fun encodeLong(value: Long) = unsupported()
        override fun encodeFloat(value: Float) = unsupported()
        override fun encodeDouble(value: Double) = unsupported()
        override fun encodeChar(value: Char) = unsupported()
        override fun encodeString(value: String) = unsupported()
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = unsupported()
        @OptIn(ExperimentalSerializationApi::class)
        override fun encodeNull() = unsupported()
        override fun encodeInline(descriptor: SerialDescriptor): Encoder = this
        private fun unsupported(): Nothing = error("UrlBuildingEncoder only supports structured encoding")
    }

    // -- Decoder --

    private class UrlParsingDecoder(
        private val params: Map<String, String>,
        private val info: ResourceClassInfo,
    ) : Decoder, CompositeDecoder {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        private var elementIndex = 0

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = this
        override fun endStructure(descriptor: SerialDescriptor) {}
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            while (elementIndex < descriptor.elementsCount) {
                val name = descriptor.getElementName(elementIndex)
                val isPathParam = name in info.pathParamNames
                if (name == info.parentName || isPathParam || params.containsKey(name)) return elementIndex++
                elementIndex++
            }
            return CompositeDecoder.DECODE_DONE
        }
        override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String = params[descriptor.getElementName(index)] ?: ""
        override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int = decodeStringElement(descriptor, index).toIntOrNull() ?: 0
        override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean {
            val raw = decodeStringElement(descriptor, index)
            if (raw.isEmpty()) return true  // query param present with no value
            return raw.toBooleanStrictOrNull() ?: false
        }
        override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte = decodeStringElement(descriptor, index).toByteOrNull() ?: 0
        override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short = decodeStringElement(descriptor, index).toShortOrNull() ?: 0
        override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long = decodeStringElement(descriptor, index).toLongOrNull() ?: 0L
        override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float = decodeStringElement(descriptor, index).toFloatOrNull() ?: 0f
        override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double = decodeStringElement(descriptor, index).toDoubleOrNull() ?: 0.0
        override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char = decodeStringElement(descriptor, index).firstOrNull() ?: '\u0000'
        @OptIn(ExperimentalSerializationApi::class)
        @Suppress("UNCHECKED_CAST")
        override fun <T> decodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>,
            previousValue: T?,
        ): T {
            if (descriptor.getElementName(index) == info.parentName) {
                return deserializer.deserialize(
                    UrlParsingDecoder(
                        params = params,
                        info = ResourceClassInfo.from(deserializer.descriptor),
                    ),
                )
            }
            return decodeElementValue(descriptor, index, deserializer.descriptor) as T
        }

        @OptIn(ExperimentalSerializationApi::class)
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> decodeNullableSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T?>,
            previousValue: T?,
        ): T? {
            val name = descriptor.getElementName(index)
            if (!params.containsKey(name)) return null
            return decodeElementValue(descriptor, index, deserializer.descriptor) as T?
        }
        override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder = this
        override fun decodeBoolean(): Boolean = unsupported()
        override fun decodeByte(): Byte = unsupported()
        override fun decodeShort(): Short = unsupported()
        override fun decodeInt(): Int = unsupported()
        override fun decodeLong(): Long = unsupported()
        override fun decodeFloat(): Float = unsupported()
        override fun decodeDouble(): Double = unsupported()
        override fun decodeChar(): Char = unsupported()
        override fun decodeString(): String = unsupported()
        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = unsupported()
        @OptIn(ExperimentalSerializationApi::class)
        override fun decodeNull(): Nothing = unsupported()
        @OptIn(ExperimentalSerializationApi::class)
        override fun decodeNotNullMark(): Boolean = true
        override fun decodeInline(descriptor: SerialDescriptor): Decoder = this
        private fun decodeElementValue(
            descriptor: SerialDescriptor,
            index: Int,
            valueDescriptor: SerialDescriptor,
        ): Any {
            return when (valueDescriptor.kind) {
                PrimitiveKind.STRING -> decodeStringElement(descriptor, index)
                PrimitiveKind.INT -> decodeIntElement(descriptor, index)
                PrimitiveKind.BOOLEAN -> decodeBooleanElement(descriptor, index)
                PrimitiveKind.BYTE -> decodeByteElement(descriptor, index)
                PrimitiveKind.SHORT -> decodeShortElement(descriptor, index)
                PrimitiveKind.LONG -> decodeLongElement(descriptor, index)
                PrimitiveKind.FLOAT -> decodeFloatElement(descriptor, index)
                PrimitiveKind.DOUBLE -> decodeDoubleElement(descriptor, index)
                PrimitiveKind.CHAR -> decodeCharElement(descriptor, index)
                else -> unsupported()
            }
        }
        private fun unsupported(): Nothing = error("UrlParsingDecoder only supports structured decoding")
    }

    private fun splitPathAndQuery(
        pathAndQuery: String,
        info: ResourceClassInfo,
    ): PathAndQuery {
        val questionMarks = pathAndQuery.indices.filter { pathAndQuery[it] == '?' }
        for (questionMark in questionMarks.asReversed()) {
            val queryString = pathAndQuery.substring(questionMark + 1)
            if (matchesDeclaredQuery(queryString, info)) {
                return PathAndQuery(
                    path = pathAndQuery.substring(0, questionMark),
                    queryString = queryString,
                )
            }
        }
        return PathAndQuery(path = pathAndQuery, queryString = "")
    }

    private fun matchesDeclaredQuery(
        queryString: String,
        info: ResourceClassInfo,
    ): Boolean {
        if (queryString.isEmpty()) return false
        val queryParamNames = info.queryParams.map { it.name }.toSet()
        if (queryParamNames.isEmpty()) return false
        return queryString.split("&").filter { it.isNotBlank() }.any { pair ->
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            key in queryParamNames
        }
    }

    private data class PathAndQuery(
        val path: String,
        val queryString: String,
    )
}
