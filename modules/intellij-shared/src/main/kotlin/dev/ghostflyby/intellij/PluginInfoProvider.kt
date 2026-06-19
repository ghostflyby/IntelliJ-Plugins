/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij

import org.w3c.dom.Element
import java.io.InputStream
import java.lang.ref.WeakReference
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

public class PluginInfoProvider private constructor(
    private val classLoader: WeakReference<ClassLoader>,
) {
    public constructor(anchorClass: Class<*>) : this(WeakReference(anchorClass.classLoader))

    private val info: ParsedPluginInfo by lazy(LazyThreadSafetyMode.PUBLICATION) {
        readPluginInfo(classLoader.get()?.apply { classLoader.clear() }!!)
    }

    public val id: String
        get() = info.id

    public val name: String
        get() = info.name

    public val version: String
        get() = info.version
}

private data class ParsedPluginInfo(
    val id: String,
    val name: String,
    val version: String,
)

private fun readPluginInfo(classLoader: ClassLoader): ParsedPluginInfo {
    val stream = classLoader.getResourceAsStream(PLUGIN_XML_RESOURCE)
        ?: error("Cannot find $PLUGIN_XML_RESOURCE from plugin class loader.")
    return stream.use(::readPluginInfo)
}

private fun readPluginInfo(stream: InputStream): ParsedPluginInfo {
    val root = DocumentBuilderFactory.newInstance()
        .apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        .newDocumentBuilder()
        .parse(stream)
        .documentElement

    return ParsedPluginInfo(
        id = root.requiredDirectChildText("id"),
        name = root.requiredDirectChildText("name"),
        version = root.requiredDirectChildText("version"),
    )
}

private fun Element.requiredDirectChildText(tagName: String): String {
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element && child.tagName == tagName) {
            return child.textContent.trim().takeIf { it.isNotEmpty() }
                ?: error("plugin.xml <$tagName> is blank.")
        }
    }
    error("plugin.xml is missing required <$tagName> element.")
}

private const val PLUGIN_XML_RESOURCE = "META-INF/plugin.xml"
