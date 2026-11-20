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
    val section = accessorClasses.firstOrNull()?.catalogSection()
        ?: method.catalogSectionForRootAccessor()
        ?: TypesafeConventionsCatalogSection.LIBRARIES
    val aliasIndex = typesafeConventionsTomlCatalogAliasIndex(tomlFile)
    if (accessorClasses.isEmpty()) {
        when (method.name) {
            METHOD_GET_PLUGINS,
            METHOD_GET_BUNDLES,
            METHOD_GET_VERSIONS,
                -> return aliasIndex.sectionOwner(section)
        }
    }
    val accessorName = method.capitalizedAccessorName()
        ?: return null
    return aliasIndex.findByGeneratedAccessor(section, accessorName)?.entry
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

private fun PsiClass.catalogSection(): TypesafeConventionsCatalogSection? =
    name?.let {
        when {
            it.endsWith(VERSION_ACCESSORS_SUFFIX) -> TypesafeConventionsCatalogSection.VERSIONS
            it.endsWith(BUNDLE_ACCESSORS_SUFFIX) -> TypesafeConventionsCatalogSection.BUNDLES
            it.endsWith(PLUGIN_ACCESSORS_SUFFIX) -> TypesafeConventionsCatalogSection.PLUGINS
            it.endsWith(LIBRARY_ACCESSORS_SUFFIX) -> TypesafeConventionsCatalogSection.LIBRARIES
            else -> null
        }
    }

private fun PsiMethod.catalogSectionForRootAccessor(): TypesafeConventionsCatalogSection? =
    when (name) {
        METHOD_GET_PLUGINS -> TypesafeConventionsCatalogSection.PLUGINS
        METHOD_GET_BUNDLES -> TypesafeConventionsCatalogSection.BUNDLES
        METHOD_GET_VERSIONS -> TypesafeConventionsCatalogSection.VERSIONS
        else -> null
    }

private fun String.trimAccessorSuffix(): String =
    listOf(BUNDLE_ACCESSORS_SUFFIX, LIBRARY_ACCESSORS_SUFFIX, PLUGIN_ACCESSORS_SUFFIX, VERSION_ACCESSORS_SUFFIX)
        .firstOrNull { endsWith(it) }
        ?.let { substringBeforeLast(it) }
        ?: this

private fun String.capitalizeAscii(): String =
    replaceFirstChar { if (it in 'a'..'z') it.uppercaseChar() else it }

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
private const val METHOD_GET_PLUGINS = "getPlugins"
private const val METHOD_GET_VERSIONS = "getVersions"
private const val METHOD_GET_BUNDLES = "getBundles"
