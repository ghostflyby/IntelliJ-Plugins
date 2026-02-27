/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.scope

import com.intellij.ide.scratch.ScratchesNamedScope
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.*
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder
import java.util.*

@Service(Service.Level.PROJECT)
internal class ScopeCatalogService {
    data class ScopeCatalogRecord(
        val item: ScopeCatalogItemDto,
        val scope: SearchScope,
    )

    data class ScopeCatalogSnapshot(
        val records: Map<String, ScopeCatalogRecord>,
        val diagnostics: List<String>,
    )

    suspend fun listCatalog(project: Project, includeInteractiveScopes: Boolean): ScopeCatalogResultDto {
        val snapshot = collectRecords(project)
        val items = snapshot.records.values
            .map { it.item }
            .filter { includeInteractiveScopes || !it.requiresUserInput }
        return ScopeCatalogResultDto(items = items, diagnostics = snapshot.diagnostics)
    }

    suspend fun resolveByRef(project: Project, scopeRefId: String, allowUiInteractiveScopes: Boolean): SearchScope? {
        val records = collectRecords(project).records
        val record = records[scopeRefId] ?: return null
        if (record.item.requiresUserInput && !allowUiInteractiveScopes) {
            return null
        }
        return record.scope
    }

    suspend fun findCatalogItem(
        project: Project,
        scopeRefId: String,
        includeInteractiveScopes: Boolean,
    ): ScopeCatalogItemDto? {
        val record = collectRecords(project).records[scopeRefId] ?: return null
        if (record.item.requiresUserInput && !includeInteractiveScopes) {
            return null
        }
        return record.item
    }

    suspend fun findStandardScope(
        project: Project,
        standardScopeId: String,
        allowUiInteractiveScopes: Boolean,
    ): SearchScope? {
        val direct = directStandardScope(project, standardScopeId)
        if (direct != null) {
            return direct
        }
        return resolveByRef(
            project = project,
            scopeRefId = standardRefId(standardScopeId),
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
    }

    private suspend fun collectRecords(project: Project): ScopeCatalogSnapshot {
        val records = linkedMapOf<String, ScopeCatalogRecord>()
        val diagnostics = mutableListOf<String>()
        addPredefinedScopes(project, records)
        addProviderScopes(project, records, diagnostics)
        addNamedScopes(project, records)
        addModuleScopes(project, records)
        return ScopeCatalogSnapshot(records = records, diagnostics = diagnostics.distinct())
    }

    private suspend fun addPredefinedScopes(
        project: Project,
        out: MutableMap<String, ScopeCatalogRecord>,
    ) {
        val scopes = PredefinedSearchScopeProvider.getInstance().getPredefinedScopesSuspend(
            project = project,
            dataContext = null,
            suggestSearchInLibs = true,
            prevSearchFiles = false,
            currentSelection = false,
            usageView = false,
            showEmptyScopes = true,
        )
        for (scope in scopes) {
            val displayName = scope.displayName
            val serializationId = serializationIdOrNull(project, displayName)
            val requiresUserInput = requiresUserInputScope(scope, serializationId)
            val unstable = isUnstableScope(serializationId)
            val kind = if (serializationId != null) ScopeAtomKind.STANDARD else ScopeAtomKind.PROVIDER_SCOPE
            val refId = if (serializationId != null) {
                standardRefId(serializationId)
            } else {
                providerRefId(
                    providerId = PREDEFINED_PROVIDER_ID,
                    displayName = displayName,
                    scopeClassName = scope.javaClass.name,
                )
            }
            val item = ScopeCatalogItemDto(
                scopeRefId = refId,
                displayName = displayName,
                kind = kind,
                scopeShape = scopeShapeOf(scope),
                serializationId = serializationId,
                requiresUserInput = requiresUserInput,
                unstable = unstable,
                providerScopeId = if (kind == ScopeAtomKind.PROVIDER_SCOPE) PREDEFINED_PROVIDER_ID else null,
            )
            out.putIfAbsent(refId, ScopeCatalogRecord(item = item, scope = scope))
        }
    }

    private suspend fun addProviderScopes(
        project: Project,
        out: MutableMap<String, ScopeCatalogRecord>,
        diagnostics: MutableList<String>,
    ) {
        val dataContext = SimpleDataContext.getProjectContext(project)
        val providers: List<SearchScopeProvider> = readAction {
            SearchScopeProvider.EP_NAME.extensionList.map { it as SearchScopeProvider }
        }
        for (provider in providers) {
            val providerId = provider.javaClass.name
            val collection = collectProviderScopes(provider, project, dataContext)
            diagnostics += collection.diagnostics
            val scopes = collection.scopes
            for (scope in scopes) {
                val displayName = scope.displayName
                val refId = providerRefId(providerId, displayName, scope.javaClass.name)
                val item = ScopeCatalogItemDto(
                    scopeRefId = refId,
                    displayName = displayName,
                    kind = ScopeAtomKind.PROVIDER_SCOPE,
                    scopeShape = scopeShapeOf(scope),
                    requiresUserInput = scope is LocalSearchScope,
                    unstable = true,
                    providerScopeId = providerId,
                )
                out.putIfAbsent(refId, ScopeCatalogRecord(item = item, scope = scope))
            }
        }
    }

    private suspend fun addNamedScopes(
        project: Project,
        out: MutableMap<String, ScopeCatalogRecord>,
    ) {
        val holders = readAction { NamedScopesHolder.getAllNamedScopeHolders(project) }
        for (holder in holders) {
            val holderId = holder.javaClass.name
            val namedScopes = readAction { holder.scopes }
            for (namedScope in namedScopes) {
                if (namedScope.value == null) {
                    continue
                }
                val scope = readAction { GlobalSearchScopesCore.filterScope(project, namedScope) }
                val refId = namedRefId(holderId, namedScope.scopeId)
                val item = ScopeCatalogItemDto(
                    scopeRefId = refId,
                    displayName = namedScope.presentableName,
                    kind = ScopeAtomKind.NAMED_SCOPE,
                    scopeShape = ScopeShape.GLOBAL,
                    namedScopeName = namedScope.scopeId,
                    namedScopeHolderId = holderId,
                )
                out.putIfAbsent(refId, ScopeCatalogRecord(item = item, scope = scope))
            }
        }
    }

    private suspend fun addModuleScopes(
        project: Project,
        out: MutableMap<String, ScopeCatalogRecord>,
    ) {
        val modules = readAction { ModuleManager.getInstance(project).modules.toList() }
            .sortedBy { it.name.lowercase(Locale.US) }
        for (module in modules) {
            for (flavor in ModuleScopeFlavor.entries) {
                val scope = readAction {
                    flavor.scopeFor(module)
                }
                val refId = moduleRefId(module.name, flavor)
                val item = ScopeCatalogItemDto(
                    scopeRefId = refId,
                    displayName = "${module.name} (${flavor.name})",
                    kind = ScopeAtomKind.MODULE,
                    scopeShape = ScopeShape.GLOBAL,
                    moduleName = module.name,
                    moduleFlavor = flavor,
                )
                out.putIfAbsent(refId, ScopeCatalogRecord(item = item, scope = scope))
            }
        }
    }

    private fun serializationIdOrNull(project: Project, displayName: String): String? {
        val mapping = standardScopeDisplayNameToId(project)
        return mapping[displayName]
    }

    private fun requiresUserInputScope(scope: SearchScope, serializationId: String?): Boolean {
        if (serializationId == CURRENT_FILE_SCOPE_ID) {
            return true
        }
        return scope is LocalSearchScope
    }

    private fun isUnstableScope(serializationId: String?): Boolean {
        return serializationId == CURRENT_FILE_SCOPE_ID ||
            serializationId == OPEN_FILES_SCOPE_ID ||
            serializationId == RECENTLY_CHANGED_FILES_SCOPE_ID ||
            serializationId == RECENTLY_VIEWED_FILES_SCOPE_ID
    }

    private fun directStandardScope(project: Project, standardScopeId: String): SearchScope? {
        return when (standardScopeId) {
            ALL_PLACES_SCOPE_ID -> GlobalSearchScope.everythingScope(project)
            PROJECT_AND_LIBRARIES_SCOPE_ID -> GlobalSearchScope.allScope(project)
            PROJECT_FILES_SCOPE_ID -> GlobalSearchScope.projectScope(project)
            PROJECT_PRODUCTION_FILES_SCOPE_ID -> GlobalSearchScopesCore.projectProductionScope(project)
            PROJECT_TEST_FILES_SCOPE_ID -> GlobalSearchScopesCore.projectTestScope(project)
            OPEN_FILES_SCOPE_ID -> GlobalSearchScopes.openFilesScope(project)
            else -> null
        }
    }

    private fun collectProviderScopesFromMethod(
        providerId: String,
        provider: SearchScopeProvider,
        methodName: String,
        project: Project,
        dataContext: DataContext,
        diagnostics: MutableList<String>,
    ): List<SearchScope> {
        val method = provider.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 2
        } ?: return emptyList()
        val result = runCatching {
            method.invoke(provider, project, dataContext)
        }.getOrElse { error ->
            diagnostics += "Provider '$providerId' method '$methodName' invocation failed: ${error.message ?: error.javaClass.name}."
            return emptyList()
        } ?: return emptyList()
        val values = (result as? Iterable<*>)?.toList()
        if (values == null) {
            diagnostics += "Provider '$providerId' method '$methodName' returned non-iterable type '${result.javaClass.name}'."
            return emptyList()
        }
        val scopes = values.filterIsInstance<SearchScope>()
        if (scopes.size != values.size) {
            diagnostics += "Provider '$providerId' method '$methodName' returned non-SearchScope elements; ignored invalid entries."
        }
        return scopes
    }

    private fun collectProviderScopes(
        provider: SearchScopeProvider,
        project: Project,
        dataContext: DataContext,
    ): ProviderScopeCollectionResult {
        val providerId = provider.javaClass.name
        val diagnostics = mutableListOf<String>()
        val scopes = linkedSetOf<SearchScope>()
        val methods = provider.javaClass.methods
        val methodNames = listOf("getGeneralSearchScopes", "getSearchScopes")
        var foundCompatibleMethod = false

        for (methodName in methodNames) {
            val methodExists = methods.any { it.name == methodName && it.parameterCount == 2 }
            if (methodExists) {
                foundCompatibleMethod = true
            }
            scopes += collectProviderScopesFromMethod(
                providerId = providerId,
                provider = provider,
                methodName = methodName,
                project = project,
                dataContext = dataContext,
                diagnostics = diagnostics,
            )
        }

        if (!foundCompatibleMethod) {
            diagnostics += "Provider '$providerId' has no compatible scope method. " +
                "Expected getGeneralSearchScopes(Project, DataContext) or getSearchScopes(Project, DataContext)."
        }

        return ProviderScopeCollectionResult(
            scopes = scopes.toList(),
            diagnostics = diagnostics,
        )
    }

    private data class ProviderScopeCollectionResult(
        val scopes: List<SearchScope>,
        val diagnostics: List<String>,
    )

    private fun standardScopeDisplayNameToId(project: Project): Map<String, String> {
        return mapOf(
            EverythingGlobalScope.getNameText() to ALL_PLACES_SCOPE_ID,
            ProjectAndLibrariesScope.getNameText() to PROJECT_AND_LIBRARIES_SCOPE_ID,
            ProjectScope.getProjectFilesScopeName() to PROJECT_FILES_SCOPE_ID,
            GlobalSearchScopesCore.getProjectProductionFilesScopeName() to PROJECT_PRODUCTION_FILES_SCOPE_ID,
            GlobalSearchScopesCore.getProjectTestFilesScopeName() to PROJECT_TEST_FILES_SCOPE_ID,
            ScratchesNamedScope.scratchesAndConsoles() to SCRATCHES_AND_CONSOLES_SCOPE_ID,
            GlobalSearchScopes.openFilesScope(project).displayName to OPEN_FILES_SCOPE_ID,
            PredefinedSearchScopeProviderImpl.getRecentlyViewedFilesScopeName() to RECENTLY_VIEWED_FILES_SCOPE_ID,
            PredefinedSearchScopeProviderImpl.getRecentlyChangedFilesScopeName() to RECENTLY_CHANGED_FILES_SCOPE_ID,
            PredefinedSearchScopeProviderImpl.getCurrentFileScopeName() to CURRENT_FILE_SCOPE_ID,
        )
    }

    companion object {
        private const val PREDEFINED_PROVIDER_ID = "predefined"
        private const val ALL_PLACES_SCOPE_ID = "All Places"
        private const val PROJECT_AND_LIBRARIES_SCOPE_ID = "Project and Libraries"
        private const val PROJECT_FILES_SCOPE_ID = "Project Files"
        private const val PROJECT_PRODUCTION_FILES_SCOPE_ID = "Project Production Files"
        private const val PROJECT_TEST_FILES_SCOPE_ID = "Project Test Files"
        private const val SCRATCHES_AND_CONSOLES_SCOPE_ID = "Scratches and Consoles"
        private const val RECENTLY_VIEWED_FILES_SCOPE_ID = "Recently Viewed Files"
        private const val RECENTLY_CHANGED_FILES_SCOPE_ID = "Recently Changed Files"
        private const val OPEN_FILES_SCOPE_ID = "Open Files"
        private const val CURRENT_FILE_SCOPE_ID = "Current File"

        fun standardRefId(scopeId: String): String = "standard:$scopeId"
        fun moduleRefId(moduleName: String, flavor: ModuleScopeFlavor): String = "module:$moduleName:${flavor.name}"
        fun namedRefId(holderId: String, scopeId: String): String = "named:$holderId:$scopeId"
        fun providerRefId(providerId: String, displayName: String, scopeClassName: String): String {
            val digest = sha256ShortHash("$providerId|$displayName|$scopeClassName", length = 12)
            return "provider:$providerId:$digest"
        }

        fun getInstance(project: Project): ScopeCatalogService = project.service<ScopeCatalogService>()
    }
}
