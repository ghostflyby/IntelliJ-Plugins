/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
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

internal data class TypesafeConventionsTomlCatalogAlias(
    val section: TypesafeConventionsCatalogSection,
    val normalizedAliasPath: String,
    val entry: TomlKeyValue,
    val segments: List<TomlKeySegment>,
)

internal class TypesafeConventionsTomlCatalogAliasIndex private constructor(
    aliases: List<TypesafeConventionsTomlCatalogAlias>,
) {
    private val aliasesByKey = aliases.associateBy { alias -> alias.section to alias.normalizedAliasPath }
    private val aliasesByEntry = aliases.groupBy(TypesafeConventionsTomlCatalogAlias::entry)

    fun find(
        section: TypesafeConventionsCatalogSection,
        aliasPath: String,
    ): TypesafeConventionsTomlCatalogAlias? =
        aliasesByKey[section to aliasPath.normalizedTypesafeConventionsCatalogKey()]

    fun find(
        entry: TomlKeyValue,
        section: TypesafeConventionsCatalogSection,
    ): TypesafeConventionsTomlCatalogAlias? =
        aliasesByEntry[entry].orEmpty().firstOrNull { alias -> alias.section == section }

    fun find(entry: TomlKeyValue): TypesafeConventionsTomlCatalogAlias? =
        aliasesByEntry[entry].orEmpty().firstOrNull()

    internal companion object {
        fun create(tomlFile: TomlFile): TypesafeConventionsTomlCatalogAliasIndex {
            val aliases = buildList {
                for (element in tomlFile.children) {
                    if (element is TomlHeaderOwner) {
                        val section = element.header.key?.text.typesafeConventionsCatalogSection()
                        val owner = element as? TomlKeyValueOwner
                        if (section != null && owner != null) {
                            owner.entries.forEach { entry -> addAlias(section, entry, entry.key.segments) }
                        }
                    }
                    if (element is TomlKeyValue) {
                        val segments = element.key.segments
                        val section = segments.firstOrNull()?.name.typesafeConventionsCatalogSection()
                        if (section != null && segments.size > 1) {
                            addAlias(section, element, segments.drop(1))
                        }

                        val inlineTable = element.value as? TomlInlineTable
                        val inlineSection = element.key.text.typesafeConventionsCatalogSection()
                        if (inlineTable != null && inlineSection != null) {
                            inlineTable.entries.forEach { entry ->
                                addAlias(inlineSection, entry, entry.key.segments)
                            }
                        }
                    }
                }
            }
            return TypesafeConventionsTomlCatalogAliasIndex(aliases)
        }

        private fun MutableList<TypesafeConventionsTomlCatalogAlias>.addAlias(
            section: TypesafeConventionsCatalogSection,
            entry: TomlKeyValue,
            segments: List<TomlKeySegment>,
        ) {
            val segmentNames = segments.mapNotNull(TomlKeySegment::getName)
            if (segmentNames.size != segments.size || segmentNames.isEmpty()) {
                return
            }
            val aliasPath = segmentNames.joinToString(".")
            add(
                TypesafeConventionsTomlCatalogAlias(
                    section = section,
                    normalizedAliasPath = aliasPath.normalizedTypesafeConventionsCatalogKey(),
                    entry = entry,
                    segments = segments,
                ),
            )
        }
    }
}

private val TYPESAFE_CONVENTIONS_TOML_ALIAS_INDEX_KEY =
    Key.create<CachedValue<TypesafeConventionsTomlCatalogAliasIndex>>(
        "typesafe.conventions.toml.catalog.alias.index",
    )

@RequiresReadLock
internal fun typesafeConventionsTomlCatalogAliasIndex(
    tomlFile: TomlFile,
): TypesafeConventionsTomlCatalogAliasIndex =
    CachedValuesManager.getManager(tomlFile.project).getCachedValue(
        tomlFile,
        TYPESAFE_CONVENTIONS_TOML_ALIAS_INDEX_KEY,
        {
            CachedValueProvider.Result.create(
                TypesafeConventionsTomlCatalogAliasIndex.create(tomlFile),
                tomlFile,
            )
        },
        false,
    )

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
    return typesafeConventionsTomlCatalogAliasIndex(tomlFile).find(section, aliasPath)?.entry
}

@RequiresReadLock
internal fun findTypesafeConventionsCatalogSection(entry: TomlKeyValue): TypesafeConventionsCatalogSection? {
    val tomlFile = entry.containingFile as? TomlFile ?: return null
    return typesafeConventionsTomlCatalogAliasIndex(tomlFile).find(entry)?.section
}

@RequiresReadLock
internal fun findTypesafeConventionsCatalogAliasSegments(
    entry: TomlKeyValue,
    section: TypesafeConventionsCatalogSection,
): List<TomlKeySegment> {
    val tomlFile = entry.containingFile as? TomlFile ?: return emptyList()
    return typesafeConventionsTomlCatalogAliasIndex(tomlFile).find(entry, section)?.segments.orEmpty()
}

internal fun typesafeConventionsCatalogKeysMatch(keyText: String?, reference: String): Boolean {
    keyText ?: return false
    return keyText.length == reference.length &&
            keyText.normalizedTypesafeConventionsCatalogKey() ==
            reference.normalizedTypesafeConventionsCatalogKey()
}

private fun String?.typesafeConventionsCatalogSection(): TypesafeConventionsCatalogSection? =
    this?.let { keyText ->
        TypesafeConventionsCatalogSection.entries.firstOrNull { section ->
            typesafeConventionsCatalogKeysMatch(keyText, section.tomlName)
        }
    }

private fun String.normalizedTypesafeConventionsCatalogKey(): String =
    buildString(length) {
        for (index in this@normalizedTypesafeConventionsCatalogKey.indices) {
            append(this@normalizedTypesafeConventionsCatalogKey.normalizedCatalogCharacterAt(index))
        }
    }

private fun String.isAfterCatalogDelimiter(index: Int): Boolean =
    index > 0 && this[index - 1].normalizeCatalogCharacter() == '.'

private fun String.normalizedCatalogCharacterAt(index: Int): Char {
    val character = this[index].normalizeCatalogCharacter()
    return if (isAfterCatalogDelimiter(index)) character.lowercaseChar() else character
}

private fun Char.normalizeCatalogCharacter(): Char =
    if (this == '-' || this == '_') '.' else this
