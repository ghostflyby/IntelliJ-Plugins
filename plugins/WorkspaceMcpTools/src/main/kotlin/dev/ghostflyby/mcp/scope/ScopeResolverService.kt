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

import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder
import com.intellij.psi.search.scope.packageSet.PackageSetFactory
import com.intellij.psi.search.scope.packageSet.ParsingException
import java.security.MessageDigest

@Service(Service.Level.PROJECT)
internal class ScopeResolverService {
    data class ResolvedScope(
        val scope: SearchScope,
        val displayName: String,
        val scopeShape: ScopeShape,
        val diagnostics: List<String>,
    )

    private data class StackScope(
        val scope: SearchScope?,
    )

    suspend fun validatePattern(patternText: String): ScopePatternValidationResultDto {
        val normalized = runCatching {
            val set = readAction { PackageSetFactory.getInstance().compile(patternText) }
            set.text
        }.getOrElse { error ->
            val message = (error as? ParsingException)?.message ?: error.message ?: "Invalid scope pattern"
            return ScopePatternValidationResultDto(
                valid = false,
                diagnostics = listOf(message),
            )
        }
        return ScopePatternValidationResultDto(
            valid = true,
            normalizedPatternText = normalized,
        )
    }

    suspend fun compileProgramDescriptor(
        project: Project,
        request: ScopeResolveRequestDto,
    ): ScopeProgramDescriptorDto {
        val diagnostics = mutableListOf<String>()
        val normalizedAtoms = normalizeAtoms(project, request, diagnostics)
        val normalizedRequest = request.copy(atoms = normalizedAtoms)
        val resolved = resolveProgram(project, normalizedRequest)
        return ScopeProgramDescriptorDto(
            atoms = normalizedAtoms,
            tokens = request.tokens,
            displayName = resolved.displayName,
            scopeShape = resolved.scopeShape,
            diagnostics = (diagnostics + resolved.diagnostics).distinct(),
        )
    }

    suspend fun resolveDescriptor(
        project: Project,
        descriptor: ScopeProgramDescriptorDto,
        allowUiInteractiveScopes: Boolean,
    ): ResolvedScope {
        if (descriptor.version !in 1..2) {
            mcpFail("Unsupported scope descriptor version ${descriptor.version}.")
        }
        val request = ScopeResolveRequestDto(
            atoms = descriptor.atoms,
            tokens = descriptor.tokens,
            strict = true,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
            nonStrictDefaultFailureMode = ScopeAtomFailureMode.EMPTY_SCOPE,
        )
        return resolveProgram(project, request)
    }

    suspend fun resolveProgram(
        project: Project,
        request: ScopeResolveRequestDto,
    ): ResolvedScope {
        if (request.tokens.isEmpty()) {
            mcpFail("Scope program tokens must not be empty.")
        }
        val atomById = linkedMapOf<String, ScopeAtomDto>()
        for (atom in request.atoms) {
            if (atom.atomId in atomById) {
                mcpFail("Duplicate atomId '${atom.atomId}' in request.")
            }
            atomById[atom.atomId] = atom
        }

        val diagnostics = mutableListOf<String>()
        val stack = mutableListOf<StackScope>()
        for ((index, token) in request.tokens.withIndex()) {
            when (token.op) {
                ScopeProgramOp.PUSH_ATOM -> {
                    val atomId = token.atomId ?: mcpFail("Token[$index] PUSH_ATOM requires atomId.")
                    val atom = atomById[atomId] ?: mcpFail("Token[$index] references unknown atomId '$atomId'.")
                    val scope = runCatching {
                        resolveAtom(project, atom, request.allowUiInteractiveScopes)
                    }.getOrElse { error ->
                        val message = error.message ?: "Failed to resolve atom '$atomId'."
                        val nonStrictFallbackMode = atom.onResolveFailure ?: request.nonStrictDefaultFailureMode
                        when {
                            request.strict -> mcpFail(message)
                            nonStrictFallbackMode == ScopeAtomFailureMode.FAIL -> mcpFail(message)
                            nonStrictFallbackMode == ScopeAtomFailureMode.SKIP -> {
                                diagnostics += "$message Fallback mode=SKIP."
                                null
                            }

                            else -> {
                                diagnostics += "$message Fallback mode=EMPTY_SCOPE."
                                GlobalSearchScope.EMPTY_SCOPE
                            }
                        }
                    }
                    stack += StackScope(scope)
                }

                ScopeProgramOp.AND -> {
                    ensureStackSize(stack, index, token.op, 2)
                    val right = stack.removeLast()
                    val left = stack.removeLast()
                    stack += when {
                        left.scope == null && right.scope == null -> StackScope(null)
                        left.scope == null -> right
                        right.scope == null -> left
                        else -> StackScope(intersectScopes(left.scope, right.scope, index))
                    }
                }

                ScopeProgramOp.OR -> {
                    ensureStackSize(stack, index, token.op, 2)
                    val right = stack.removeLast()
                    val left = stack.removeLast()
                    stack += when {
                        left.scope == null && right.scope == null -> StackScope(null)
                        left.scope == null -> right
                        right.scope == null -> left
                        else -> StackScope(unionScopes(left.scope, right.scope, index))
                    }
                }

                ScopeProgramOp.NOT -> {
                    ensureStackSize(stack, index, token.op, 1)
                    val operand = stack.removeLast()
                    when (val operandScope = operand.scope) {
                        null -> {
                            diagnostics += "Token[$index] NOT operand skipped; result remains unresolved."
                            stack += StackScope(null)
                        }

                        !is GlobalSearchScope -> {
                            if (request.strict || request.nonStrictDefaultFailureMode == ScopeAtomFailureMode.FAIL) {
                                mcpFail("Token[$index] NOT requires a GlobalSearchScope operand.")
                            }
                            diagnostics += "Token[$index] NOT operand is not global scope; replaced with EMPTY scope."
                            stack += StackScope(GlobalSearchScope.EMPTY_SCOPE)
                        }

                        else -> {
                            stack += StackScope(GlobalSearchScope.notScope(operandScope))
                        }
                    }
                }
            }
        }

        if (stack.size != 1) {
            mcpFail("Scope program is invalid: final stack size must be 1, but was ${stack.size}.")
        }
        val resolved = stack.single().scope ?: mcpFail(
            "Scope program resolved to empty expression after fallback processing. " +
                "Provide at least one resolvable atom or use EMPTY_SCOPE fallback.",
        )
        return ResolvedScope(
            scope = resolved,
            displayName = readAction { resolved.displayName },
            scopeShape = scopeShapeOf(resolved),
            diagnostics = diagnostics,
        )
    }

    private suspend fun normalizeAtoms(
        project: Project,
        request: ScopeResolveRequestDto,
        diagnostics: MutableList<String>,
    ): List<ScopeAtomDto> {
        val seenIds = hashSetOf<String>()
        return request.atoms.map { atom ->
            if (!seenIds.add(atom.atomId)) {
                failOrDiagnose(request.strict, diagnostics, "Duplicate atomId '${atom.atomId}' in request.")
            }
            normalizeAtom(project, atom, request.allowUiInteractiveScopes, request.strict, diagnostics)
        }
    }

    private suspend fun normalizeAtom(
        project: Project,
        atom: ScopeAtomDto,
        allowUiInteractiveScopes: Boolean,
        strict: Boolean,
        diagnostics: MutableList<String>,
    ): ScopeAtomDto {
        if (!atom.scopeRefId.isNullOrBlank()) {
            val item = ScopeCatalogService.getInstance(project).findCatalogItem(
                project = project,
                scopeRefId = atom.scopeRefId,
                includeInteractiveScopes = allowUiInteractiveScopes,
            )
            if (item == null) {
                failOrDiagnose(strict, diagnostics, "Unknown scopeRefId '${atom.scopeRefId}'.")
                return atom
            }
            return atom.copy(
                kind = item.kind,
                scopeRefId = item.scopeRefId,
                standardScopeId = item.serializationId ?: atom.standardScopeId,
                moduleName = item.moduleName ?: atom.moduleName,
                moduleFlavor = item.moduleFlavor ?: atom.moduleFlavor,
                namedScopeName = item.namedScopeName ?: atom.namedScopeName,
                namedScopeHolderId = item.namedScopeHolderId ?: atom.namedScopeHolderId,
                providerScopeId = item.providerScopeId ?: atom.providerScopeId,
                fileUrls = atom.fileUrls.distinct().sorted(),
            )
        }

        return when (atom.kind) {
            ScopeAtomKind.STANDARD -> {
                val standardScopeId = atom.standardScopeId
                    ?: mcpFail("Atom '${atom.atomId}' kind STANDARD requires standardScopeId.")
                atom.copy(scopeRefId = ScopeCatalogService.standardRefId(standardScopeId))
            }

            ScopeAtomKind.MODULE -> {
                val moduleName = atom.moduleName ?: mcpFail("Atom '${atom.atomId}' kind MODULE requires moduleName.")
                val flavor = atom.moduleFlavor ?: mcpFail("Atom '${atom.atomId}' kind MODULE requires moduleFlavor.")
                atom.copy(scopeRefId = ScopeCatalogService.moduleRefId(moduleName, flavor))
            }

            ScopeAtomKind.NAMED_SCOPE -> {
                val scopeName = atom.namedScopeName ?: mcpFail("Atom '${atom.atomId}' kind NAMED_SCOPE requires namedScopeName.")
                if (!atom.namedScopeHolderId.isNullOrBlank()) {
                    atom.copy(scopeRefId = ScopeCatalogService.namedRefId(atom.namedScopeHolderId, scopeName))
                } else {
                    val matchingHolderIds = readAction {
                        NamedScopesHolder.getAllNamedScopeHolders(project)
                            .filter { holder -> holder.getScope(scopeName) != null }
                            .map { holder -> holder.javaClass.name }
                    }
                    when (matchingHolderIds.size) {
                        0 -> {
                            failOrDiagnose(strict, diagnostics, "Named scope '$scopeName' not found.")
                            atom
                        }

                        1 -> {
                            val holderId = matchingHolderIds.single()
                            atom.copy(
                                namedScopeHolderId = holderId,
                                scopeRefId = ScopeCatalogService.namedRefId(holderId, scopeName),
                            )
                        }

                        else -> {
                            failOrDiagnose(
                                strict,
                                diagnostics,
                                "Named scope '$scopeName' is ambiguous across holders ${matchingHolderIds.joinToString()}; specify namedScopeHolderId.",
                            )
                            atom
                        }
                    }
                }
            }

            ScopeAtomKind.PATTERN -> {
                val rawPattern = atom.patternText ?: mcpFail("Atom '${atom.atomId}' kind PATTERN requires patternText.")
                val normalizedPattern = runCatching {
                    readAction { PackageSetFactory.getInstance().compile(rawPattern).text }
                }.getOrElse { error ->
                    val message = (error as? ParsingException)?.message ?: error.message ?: "Invalid pattern in atom '${atom.atomId}'."
                    failOrDiagnose(strict, diagnostics, message)
                    rawPattern
                }
                atom.copy(
                    patternText = normalizedPattern,
                    scopeRefId = atom.scopeRefId ?: "pattern:${shortHash(normalizedPattern)}",
                )
            }

            ScopeAtomKind.DIRECTORY -> {
                val directoryUrl = atom.directoryUrl ?: mcpFail("Atom '${atom.atomId}' kind DIRECTORY requires directoryUrl.")
                atom.copy(scopeRefId = atom.scopeRefId ?: "directory:$directoryUrl")
            }

            ScopeAtomKind.FILES -> {
                val normalizedUrls = atom.fileUrls.distinct().sorted()
                if (normalizedUrls.isEmpty()) {
                    mcpFail("Atom '${atom.atomId}' kind FILES requires non-empty fileUrls.")
                }
                atom.copy(
                    fileUrls = normalizedUrls,
                    scopeRefId = atom.scopeRefId ?: "files:${shortHash(normalizedUrls.joinToString("\n"))}",
                )
            }

            ScopeAtomKind.PROVIDER_SCOPE -> {
                if (atom.scopeRefId.isNullOrBlank()) {
                    mcpFail("Atom '${atom.atomId}' kind PROVIDER_SCOPE requires scopeRefId.")
                }
                atom
            }
        }
    }

    private suspend fun resolveAtom(
        project: Project,
        atom: ScopeAtomDto,
        allowUiInteractiveScopes: Boolean,
    ): SearchScope {
        if (!atom.scopeRefId.isNullOrBlank()) {
            return ScopeCatalogService.getInstance(project).resolveByRef(
                project = project,
                scopeRefId = atom.scopeRefId,
                allowUiInteractiveScopes = allowUiInteractiveScopes,
            ) ?: mcpFail("Unknown scopeRefId '${atom.scopeRefId}'.")
        }

        return when (atom.kind) {
            ScopeAtomKind.STANDARD -> {
                val standardScopeId = atom.standardScopeId
                    ?: mcpFail("Atom '${atom.atomId}' kind STANDARD requires standardScopeId.")
                ScopeCatalogService.getInstance(project).findStandardScope(
                    project = project,
                    standardScopeId = standardScopeId,
                    allowUiInteractiveScopes = allowUiInteractiveScopes,
                ) ?: mcpFail("Standard scope '$standardScopeId' cannot be resolved in this context.")
            }

            ScopeAtomKind.MODULE -> resolveModuleAtom(project, atom)
            ScopeAtomKind.NAMED_SCOPE -> resolveNamedScopeAtom(project, atom)
            ScopeAtomKind.PATTERN -> resolvePatternAtom(project, atom)
            ScopeAtomKind.DIRECTORY -> resolveDirectoryAtom(project, atom)
            ScopeAtomKind.FILES -> resolveFilesAtom(project, atom)
            ScopeAtomKind.PROVIDER_SCOPE -> {
                mcpFail("Atom '${atom.atomId}' kind PROVIDER_SCOPE requires scopeRefId.")
            }
        }
    }

    private suspend fun resolveModuleAtom(project: Project, atom: ScopeAtomDto): SearchScope {
        val moduleName = atom.moduleName ?: mcpFail("Atom '${atom.atomId}' requires moduleName.")
        val flavor = atom.moduleFlavor ?: mcpFail("Atom '${atom.atomId}' requires moduleFlavor.")
        val module = readAction { ModuleManager.getInstance(project).findModuleByName(moduleName) }
            ?: mcpFail("Module '$moduleName' not found.")
        return readAction {
            when (flavor) {
                ModuleScopeFlavor.MODULE -> module.moduleScope
                ModuleScopeFlavor.MODULE_WITH_DEPENDENCIES -> module.moduleWithDependenciesScope
                ModuleScopeFlavor.MODULE_WITH_LIBRARIES -> module.moduleWithLibrariesScope
                ModuleScopeFlavor.MODULE_WITH_DEPENDENCIES_AND_LIBRARIES -> module.getModuleWithDependenciesAndLibrariesScope(true)
            }
        }
    }

    private suspend fun resolveNamedScopeAtom(project: Project, atom: ScopeAtomDto): SearchScope {
        val scopeName = atom.namedScopeName ?: mcpFail("Atom '${atom.atomId}' requires namedScopeName.")
        val holderId = atom.namedScopeHolderId
        val namedScope = readAction {
            if (holderId.isNullOrBlank()) {
                NamedScopesHolder.getScope(project, scopeName)
            } else {
                val holder = NamedScopesHolder.getAllNamedScopeHolders(project).firstOrNull { it.javaClass.name == holderId }
                holder?.getScope(scopeName)
            }
        } ?: mcpFail("Named scope '$scopeName' not found.")

        if (namedScope.value == null) {
            mcpFail("Named scope '$scopeName' has null PackageSet and cannot be resolved.")
        }
        return readAction { GlobalSearchScopesCore.filterScope(project, namedScope) }
    }

    private suspend fun resolvePatternAtom(project: Project, atom: ScopeAtomDto): SearchScope {
        val patternText = atom.patternText ?: mcpFail("Atom '${atom.atomId}' requires patternText.")
        val packageSet = readAction {
            PackageSetFactory.getInstance().compile(patternText)
        }
        val namedScope = NamedScope.UnnamedScope(packageSet)
        return readAction { GlobalSearchScopesCore.filterScope(project, namedScope) }
    }

    private suspend fun resolveDirectoryAtom(project: Project, atom: ScopeAtomDto): SearchScope {
        val directoryUrl = atom.directoryUrl ?: mcpFail("Atom '${atom.atomId}' requires directoryUrl.")
        val directory = readAction { VirtualFileManager.getInstance().findFileByUrl(directoryUrl) }
            ?: mcpFail("Directory URL '$directoryUrl' not found.")
        if (!directory.isDirectory) {
            mcpFail("URL '$directoryUrl' is not a directory.")
        }
        return readAction {
            GlobalSearchScopesCore.directoryScope(project, directory, atom.directoryWithSubdirectories)
        }
    }

    private suspend fun resolveFilesAtom(project: Project, atom: ScopeAtomDto): SearchScope {
        if (atom.fileUrls.isEmpty()) {
            mcpFail("Atom '${atom.atomId}' kind FILES requires non-empty fileUrls.")
        }
        val files = readAction {
            atom.fileUrls.map { url ->
                VirtualFileManager.getInstance().findFileByUrl(url)
                    ?: mcpFail("File URL '$url' not found.")
            }
        }
        return readAction { GlobalSearchScope.filesScope(project, files) }
    }

    private fun ensureStackSize(
        stack: MutableList<StackScope>,
        tokenIndex: Int,
        op: ScopeProgramOp,
        expected: Int,
    ) {
        if (stack.size < expected) {
            mcpFail("Token[$tokenIndex] $op requires $expected stack items, but stack size is ${stack.size}.")
        }
    }

    private fun intersectScopes(
        left: SearchScope,
        right: SearchScope,
        tokenIndex: Int,
    ): SearchScope {
        return when {
            left is GlobalSearchScope && right is GlobalSearchScope -> left.intersectWith(right)
            left is GlobalSearchScope && right is LocalSearchScope -> left.intersectWith(right)
            left is LocalSearchScope && right is GlobalSearchScope -> right.intersectWith(left)
            left is LocalSearchScope && right is LocalSearchScope -> left.intersectWith(right)
            else -> mcpFail(
                "Token[$tokenIndex] AND is unsupported for scope types " +
                    "'${left.javaClass.name}' and '${right.javaClass.name}'.",
            )
        }
    }

    private fun unionScopes(
        left: SearchScope,
        right: SearchScope,
        tokenIndex: Int,
    ): SearchScope {
        return when {
            left is GlobalSearchScope && right is GlobalSearchScope -> left.uniteWith(right)
            left is GlobalSearchScope && right is LocalSearchScope -> left.union(right)
            left is LocalSearchScope && right is GlobalSearchScope -> right.union(left)
            left is LocalSearchScope && right is LocalSearchScope -> left.union(right)
            else -> mcpFail(
                "Token[$tokenIndex] OR is unsupported for scope types " +
                    "'${left.javaClass.name}' and '${right.javaClass.name}'.",
            )
        }
    }

    private fun scopeShapeOf(scope: SearchScope): ScopeShape {
        return when (scope) {
            is GlobalSearchScope -> ScopeShape.GLOBAL
            is LocalSearchScope -> ScopeShape.LOCAL
            else -> ScopeShape.MIXED
        }
    }

    private fun failOrDiagnose(strict: Boolean, diagnostics: MutableList<String>, message: String) {
        if (strict) {
            mcpFail(message)
        }
        diagnostics += message
    }

    private fun shortHash(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }.take(12)
    }

    companion object {
        fun getInstance(project: Project): ScopeResolverService = project.service<ScopeResolverService>()
    }
}
