/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parents
import org.jetbrains.plugins.groovy.lang.psi.GrReferenceElement
import org.jetbrains.plugins.groovy.lang.resolve.api.GroovyPropertyBase
import org.toml.lang.psi.TomlHeaderOwner
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlKeyValueOwner
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.ext.name

internal class TypesafeConventionsGroovyCatalogGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        sourceElement ?: return null
        val reference = sourceElement.parentOfType<GrReferenceElement<*>>(withSelf = true)
            ?: return null
        return when (val resolved = reference.resolve()) {
            is GroovyPropertyBase -> {
                val tomlFile = findTypesafeConventionsCatalogTomlFile(sourceElement, resolved.name)
                    ?: return null
                arrayOf(tomlFile)
            }

            is PsiMethod -> {
                if (!isInVersionCatalogAccessor(resolved)) {
                    return null
                }
                val actualMethod = findFinishingAccessorMethod(sourceElement) ?: resolved
                findOriginInTypesafeConventionsTomlFile(actualMethod, sourceElement)
                    ?.let { arrayOf(it) }
            }

            else -> null
        }
    }
}

private fun findFinishingAccessorMethod(element: PsiElement): PsiMethod? {
    var topElement: PsiMethod? = null
    for (ancestor in element.parents(withSelf = true)) {
        if (ancestor !is GrReferenceElement<*>) {
            continue
        }
        val resolved = ancestor.resolve()
        if (resolved is PsiMethod && isInVersionCatalogAccessor(resolved)) {
            topElement = resolved
        }
    }
    return topElement
}

private fun findOriginInTypesafeConventionsTomlFile(method: PsiMethod, context: PsiElement): PsiElement? {
    val containingClasses = method.containingClasses()
    val catalogAccessorClass = containingClasses.firstOrNull()
        ?: return null
    val catalogName = catalogAccessorClass.name
        ?.removePrefix(LIBRARIES_FOR_PREFIX)
        ?.substringBefore(IN_PLUGINS_BLOCK_SUFFIX)
        ?: return null
    val tomlFile = sequenceOf(catalogName.replaceFirstChar(Char::lowercaseChar), catalogName)
        .firstNotNullOfOrNull { findTypesafeConventionsCatalogTomlFile(context, it) }
        ?: return null
    val accessorClasses = containingClasses.drop(1)
    val tableName = accessorClasses.firstOrNull()?.tomlTableName()
        ?: method.tomlTableNameForRootAccessor()
        ?: TOML_TABLE_LIBRARIES
    if (accessorClasses.isEmpty()) {
        when (method.name) {
            METHOD_GET_PLUGINS,
            METHOD_GET_BUNDLES,
            METHOD_GET_VERSIONS,
                -> return tomlFile.findTomlTable(tableName)
        }
    }
    val accessorName = method.capitalizedAccessorName()
        ?: return null
    val table = tomlFile.findTomlTable(tableName)
        ?: return null
    return table.entries.firstOrNull { entry ->
        entry.key.segments.firstOrNull()?.name?.toAccessorName() == accessorName
    }
}

private fun PsiMethod.containingClasses(): List<PsiClass> {
    val classes = mutableListOf<PsiClass>()
    var current = containingClass ?: return emptyList()
    classes += current
    while (current.containingClass != null) {
        current = current.containingClass!!
        classes += current
    }
    return classes.asReversed()
}

private fun PsiMethod.capitalizedAccessorName(): String? {
    val propertyName = PropertyUtilBase.getPropertyName(this)
        ?: return null
    val methodFinalPart = propertyName.capitalizeAscii()
    val classPrefix = containingClass
        ?.name
        ?.takeUnless { it.startsWith(LIBRARIES_FOR_PREFIX) }
        ?.trimAccessorSuffix()
        .orEmpty()
    return classPrefix + methodFinalPart
}

private fun PsiClass.tomlTableName(): String? =
    name?.let {
        when {
            it.endsWith(VERSION_ACCESSORS_SUFFIX) -> TOML_TABLE_VERSIONS
            it.endsWith(BUNDLE_ACCESSORS_SUFFIX) -> TOML_TABLE_BUNDLES
            it.endsWith(PLUGIN_ACCESSORS_SUFFIX) -> TOML_TABLE_PLUGINS
            it.endsWith(LIBRARY_ACCESSORS_SUFFIX) -> TOML_TABLE_LIBRARIES
            else -> null
        }
    }

private fun PsiMethod.tomlTableNameForRootAccessor(): String? =
    when (name) {
        METHOD_GET_PLUGINS -> TOML_TABLE_PLUGINS
        METHOD_GET_BUNDLES -> TOML_TABLE_BUNDLES
        METHOD_GET_VERSIONS -> TOML_TABLE_VERSIONS
        else -> null
    }

private fun String.trimAccessorSuffix(): String =
    listOf(BUNDLE_ACCESSORS_SUFFIX, LIBRARY_ACCESSORS_SUFFIX, PLUGIN_ACCESSORS_SUFFIX, VERSION_ACCESSORS_SUFFIX)
        .firstOrNull { endsWith(it) }
        ?.let { substringBeforeLast(it) }
        ?: this

private fun String.toAccessorName(): String =
    split("-", "_").joinToString("") { it.capitalizeAscii() }

private fun String.capitalizeAscii(): String =
    replaceFirstChar { if (it in 'a'..'z') it.uppercaseChar() else it }

private fun org.toml.lang.psi.TomlFile.findTomlTable(name: String): TomlKeyValueOwner? {
    for (element in children) {
        if (element is TomlHeaderOwner && element.header.key?.name == name && element is TomlKeyValueOwner) {
            return element
        }
        if (element is TomlKeyValue && element.key.text == name && element.value is TomlKeyValueOwner) {
            return element.value as TomlKeyValueOwner
        }
        if (element is TomlTable && element.header.key?.name == name) {
            return element
        }
    }
    return null
}

/**
 * Local copy of Gradle's version catalog accessor shape check.
 *
 * The platform helper lives in a file annotated with `@ApiStatus.Internal`, so
 * this plugin keeps the tiny generated-accessor name check here instead.
 */
private fun isInVersionCatalogAccessor(method: PsiMethod): Boolean {
    val topClass = method.topContainingClass()
        ?: return false
    return topClass.name?.startsWith(LIBRARIES_FOR_PREFIX) != false
}

private fun PsiMethod.topContainingClass(): PsiClass? {
    var topClass = containingClass
        ?: return null
    while (topClass.containingClass != null) {
        topClass = topClass.containingClass!!
    }
    return topClass
}

private const val BUNDLE_ACCESSORS_SUFFIX = "BundleAccessors"
private const val LIBRARY_ACCESSORS_SUFFIX = "LibraryAccessors"
private const val PLUGIN_ACCESSORS_SUFFIX = "PluginAccessors"
private const val VERSION_ACCESSORS_SUFFIX = "VersionAccessors"
private const val LIBRARIES_FOR_PREFIX = "LibrariesFor"
private const val IN_PLUGINS_BLOCK_SUFFIX = "InPluginsBlock"
private const val TOML_TABLE_VERSIONS = "versions"
private const val TOML_TABLE_LIBRARIES = "libraries"
private const val TOML_TABLE_BUNDLES = "bundles"
private const val TOML_TABLE_PLUGINS = "plugins"
private const val METHOD_GET_PLUGINS = "getPlugins"
private const val METHOD_GET_VERSIONS = "getVersions"
private const val METHOD_GET_BUNDLES = "getBundles"
