/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId
import org.w3c.dom.Element
import java.io.InputStream
import java.lang.ref.WeakReference
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

public class PluginInfoProvider private constructor(
    private val classLoader: WeakReference<ClassLoader>,
) {
    public constructor(anchorClass: Class<*>) : this(WeakReference(anchorClass.classLoader))

    public constructor(classLoader: ClassLoader) : this(WeakReference(classLoader))

    public val pluginDescriptor: IdeaPluginDescriptor by lazy(LazyThreadSafetyMode.PUBLICATION) {
        readPluginInfo(classLoader.get()?.apply { classLoader.clear() }!!)
    }

    public val id: String
        get() = pluginDescriptor.pluginId.idString

    public val name: String
        get() = pluginDescriptor.name

    public val version: String
        get() = pluginDescriptor.version
}

private fun readPluginInfo(classLoader: ClassLoader): IdeaPluginDescriptor {
    val stream = classLoader.getResourceAsStream(PLUGIN_XML_RESOURCE)
        ?: error("Cannot find $PLUGIN_XML_RESOURCE from plugin class loader.")
    return stream.use(::readPluginDescriptor)
}


private fun readPluginDescriptor(stream: InputStream): IdeaPluginDescriptor {
    val root = DocumentBuilderFactory.newInstance()
        .apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        .newDocumentBuilder()
        .parse(stream)
        .documentElement

        val id = root.requiredDirectChildText("id")
    return PluginManager.getInstance().findEnabledPlugin(PluginId(id))!!
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
