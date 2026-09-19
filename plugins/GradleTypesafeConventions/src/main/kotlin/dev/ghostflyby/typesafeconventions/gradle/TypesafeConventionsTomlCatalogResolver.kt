/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
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
    val generatedAccessorName: String,
    val entry: TomlKeyValue,
    val segments: List<TomlKeySegment>,
)

internal class TypesafeConventionsTomlCatalogAliasIndex private constructor(
    aliases: List<TypesafeConventionsTomlCatalogAlias>,
    private val sectionOwners: Map<TypesafeConventionsCatalogSection, PsiElement>,
    private val sectionNameSegments: Map<TypesafeConventionsCatalogSection, TomlKeySegment>,
    private val sectionsBySectionNameSegment: Map<TomlKeySegment, TypesafeConventionsCatalogSection>,
) {
    private val aliasesByKey = aliases.associateBy { alias -> alias.section to alias.normalizedAliasPath }
    private val aliasesByEntry = aliases.associateBy(TypesafeConventionsTomlCatalogAlias::entry)
    private val aliasesByGeneratedAccessor = buildMap {
        aliases.forEach { alias ->
            putIfAbsent(alias.section to alias.generatedAccessorName, alias)
        }
    }

    fun find(
        section: TypesafeConventionsCatalogSection,
        aliasPath: String,
    ): TypesafeConventionsTomlCatalogAlias? =
        aliasesByKey[section to aliasPath.normalizedTypesafeConventionsCatalogKey()]

    fun find(entry: TomlKeyValue): TypesafeConventionsTomlCatalogAlias? =
        aliasesByEntry[entry]

    fun findByGeneratedAccessor(
        section: TypesafeConventionsCatalogSection,
        accessorName: String,
    ): TypesafeConventionsTomlCatalogAlias? =
        aliasesByGeneratedAccessor[section to accessorName]

    fun sectionOwner(section: TypesafeConventionsCatalogSection): PsiElement? =
        sectionOwners[section]

    /**
     * The key segment naming a section, in whichever shape the catalog declares it. Find Usages is invoked on
     * this segment when the caret sits on a section name.
     */
    fun sectionNameSegment(section: TypesafeConventionsCatalogSection): TomlKeySegment? =
        sectionNameSegments[section]

    /**
     * The section a key segment declares: a standard table header (`[bundles]`), the first segment of a
     * top-level dotted key (`bundles.foo = ...`), or an inline table key (`bundles = { ... }`).
     * Alias segments are not section names.
     */
    fun sectionForSectionNameSegment(segment: TomlKeySegment): TypesafeConventionsCatalogSection? =
        sectionsBySectionNameSegment[segment]

    /**
     * The section a key owner declares. Only standard tables own their section directly; dotted keys and
     * inline tables record the declaring key segment as the owner instead.
     */
    fun sectionForSectionOwner(owner: PsiElement): TypesafeConventionsCatalogSection? =
        sectionOwners.entries.firstOrNull { (_, sectionOwner) -> sectionOwner === owner }?.key

    internal companion object {
        fun create(tomlFile: TomlFile): TypesafeConventionsTomlCatalogAliasIndex {
            val sectionOwners = linkedMapOf<TypesafeConventionsCatalogSection, PsiElement>()
            val sectionNameSegmentsBySection = linkedMapOf<TypesafeConventionsCatalogSection, TomlKeySegment>()
            val sectionsBySectionNameSegment = linkedMapOf<TomlKeySegment, TypesafeConventionsCatalogSection>()
            fun recordSectionNameSegment(segment: TomlKeySegment, section: TypesafeConventionsCatalogSection) {
                sectionNameSegmentsBySection.putIfAbsent(section, segment)
                sectionsBySectionNameSegment.putIfAbsent(segment, section)
            }

            val aliases = buildList {
                for (element in tomlFile.children) {
                    if (element is TomlHeaderOwner) {
                        val headerKey = element.header.key
                        val section = headerKey?.text.typesafeConventionsCatalogSection()
                        val owner = element as? TomlKeyValueOwner
                        if (section != null && owner != null) {
                            sectionOwners.putIfAbsent(section, owner)
                            headerKey?.segments?.singleOrNull()?.let { segment ->
                                recordSectionNameSegment(segment, section)
                            }
                            owner.entries.forEach { entry -> addAlias(section, entry, entry.key.segments) }
                        }
                    }
                    if (element is TomlKeyValue) {
                        val segments = element.key.segments
                        val section = segments.firstOrNull()?.name.typesafeConventionsCatalogSection()
                        if (section != null && segments.size > 1) {
                            val sectionNameSegment = segments.first()
                            sectionOwners.putIfAbsent(section, sectionNameSegment)
                            recordSectionNameSegment(sectionNameSegment, section)
                            addAlias(section, element, segments.drop(1))
                        }

                        val inlineTable = element.value as? TomlInlineTable
                        val inlineSection = element.key.text.typesafeConventionsCatalogSection()
                        if (inlineTable != null && inlineSection != null) {
                            sectionOwners.putIfAbsent(inlineSection, inlineTable)
                            segments.singleOrNull()?.let { segment ->
                                recordSectionNameSegment(segment, inlineSection)
                            }
                            inlineTable.entries.forEach { entry ->
                                addAlias(inlineSection, entry, entry.key.segments)
                            }
                        }
                    }
                }
            }
            return TypesafeConventionsTomlCatalogAliasIndex(
                aliases = aliases,
                sectionOwners = sectionOwners,
                sectionNameSegments = sectionNameSegmentsBySection,
                sectionsBySectionNameSegment = sectionsBySectionNameSegment,
            )
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
                    generatedAccessorName = segmentNames.joinToString("") { it.toGeneratedAccessorName() },
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
internal fun findTypesafeConventionsTomlCatalogAlias(
    entry: TomlKeyValue,
): TypesafeConventionsTomlCatalogAlias? {
    val tomlFile = entry.containingFile as? TomlFile ?: return null
    return typesafeConventionsTomlCatalogAliasIndex(tomlFile).find(entry)
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

private fun String.toGeneratedAccessorName(): String =
    split('-', '_')
        .filter(String::isNotEmpty)
        .joinToString("") { part ->
            part.replaceFirstChar { if (it in 'a'..'z') it.uppercaseChar() else it }
        }
