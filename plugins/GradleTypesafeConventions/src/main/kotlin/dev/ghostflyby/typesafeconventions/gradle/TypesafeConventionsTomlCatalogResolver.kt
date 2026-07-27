/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.toml.lang.psi.*

internal enum class TypesafeConventionsCatalogSection(val tomlName: String) {
    LIBRARIES("libraries"),
    VERSIONS("versions"),
    BUNDLES("bundles"),
    PLUGINS("plugins"),
    ;

    companion object {
        fun fromAccessorPrefix(prefix: String): TypesafeConventionsCatalogSection? =
            entries.firstOrNull { it != LIBRARIES && it.tomlName == prefix }
    }
}

@RequiresReadLock
internal fun findTypesafeConventionsCatalogEntry(
    tomlFile: TomlFile,
    declarationPath: String,
): TomlKeyValue? {
    val prefix = declarationPath.substringBefore('.', missingDelimiterValue = declarationPath)
    val section = TypesafeConventionsCatalogSection.fromAccessorPrefix(prefix)
        ?: TypesafeConventionsCatalogSection.LIBRARIES
    val aliasPath = if (section == TypesafeConventionsCatalogSection.LIBRARIES) {
        declarationPath
    } else {
        declarationPath.substringAfter('.', missingDelimiterValue = "")
    }
    return findTypesafeConventionsCatalogEntry(tomlFile, section, aliasPath)
}

@RequiresReadLock
internal fun findTypesafeConventionsCatalogEntry(
    tomlFile: TomlFile,
    section: TypesafeConventionsCatalogSection,
    aliasPath: String,
): TomlKeyValue? {
    if (aliasPath.isEmpty()) {
        return null
    }

    for (element in tomlFile.children) {
        if (element is TomlHeaderOwner && typesafeConventionsCatalogKeysMatch(
                element.header.key?.text,
                section.tomlName,
            )
        ) {
            val owner = element as? TomlKeyValueOwner ?: continue
            findAlias(owner, aliasPath)?.let { return it }
        }
        if (element is TomlKeyValue) {
            if (typesafeConventionsCatalogKeysMatch(element.key.text, "${section.tomlName}.$aliasPath")) {
                return element
            }
            val inlineTable = element.value as? TomlInlineTable
            if (inlineTable != null && typesafeConventionsCatalogKeysMatch(element.key.text, section.tomlName)) {
                findAlias(inlineTable, aliasPath)?.let { return it }
            }
        }
    }
    return null
}

@RequiresReadLock
internal fun findTypesafeConventionsCatalogSection(entry: TomlKeyValue): TypesafeConventionsCatalogSection? {
    for (section in TypesafeConventionsCatalogSection.entries) {
        if (findTypesafeConventionsCatalogAliasSegments(entry, section).isNotEmpty()) {
            return section
        }
    }
    return null
}

@RequiresReadLock
internal fun findTypesafeConventionsCatalogAliasSegments(
    entry: TomlKeyValue,
    section: TypesafeConventionsCatalogSection,
): List<TomlKeySegment> {
    val tomlFile = entry.containingFile as? TomlFile ?: return emptyList()
    val allSegments = entry.key.segments
    val candidates = buildList {
        add(allSegments)
        if (allSegments.size > 1 && allSegments.first().name == section.tomlName) {
            add(allSegments.drop(1))
        }
    }
    return candidates.firstOrNull { segments ->
        val names = segments.mapNotNull { it.name }
        names.size == segments.size &&
                findTypesafeConventionsCatalogEntry(tomlFile, section, names.joinToString(".")) === entry
    }.orEmpty()
}

@RequiresReadLock
private fun findAlias(owner: TomlKeyValueOwner, aliasPath: String): TomlKeyValue? {
    return owner.entries.firstOrNull { entry ->
        typesafeConventionsCatalogKeysMatch(entry.key.text, aliasPath)
    }
}

internal fun typesafeConventionsCatalogKeysMatch(keyText: String?, reference: String): Boolean {
    keyText ?: return false
    if (keyText.length != reference.length) {
        return false
    }
    for (index in keyText.indices) {
        val keyCharacter = keyText.normalizedCatalogCharacterAt(index)
        val referenceCharacter = reference.normalizedCatalogCharacterAt(index)
        if (keyCharacter != referenceCharacter) {
            return false
        }
    }
    return true
}

private fun String.isAfterCatalogDelimiter(index: Int): Boolean =
    index > 0 && this[index - 1].normalizeCatalogCharacter() == '.'

private fun String.normalizedCatalogCharacterAt(index: Int): Char {
    val character = this[index].normalizeCatalogCharacter()
    return if (isAfterCatalogDelimiter(index)) character.lowercaseChar() else character
}

private fun Char.normalizeCatalogCharacter(): Char =
    if (this == '-' || this == '_') '.' else this
