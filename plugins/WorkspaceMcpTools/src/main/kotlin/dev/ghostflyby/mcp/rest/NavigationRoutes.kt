package dev.ghostflyby.mcp.rest

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument
import dev.ghostflyby.mcp.filecontent.resolveProjectFileAccess
import dev.ghostflyby.mcp.rest.markdown.TextBody
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class NavTarget(
    val fileUrl: String,
    val encodedFileUrl: String,
    val lineNumber: Int,
    val column: Int,
)

@Serializable
private data class NavGoto(
    val path: String,
    val result: NavTarget,
) {
    val operation = "goto"
}

@Serializable
private data class NavUsages(
    val path: String,
    val results: List<NavTarget>,
    val truncated: Boolean = false,
) {
    val operation = "usages"
}

@Serializable
private data class NavDocumentation(
    val path: String,
    val name: String,
    val documentation: String,
) {
    val operation = "documentation"
}

// -- Parser --

private val NAV_OP = Regex("""^\*\*\* (Goto|Usages|Documentation):""")

private enum class NavOp { Goto, Usages, Documentation }

private data class NavSection(
    val op: NavOp,
    val oldLine: String,
    val newLine: String,
)

private fun splitNavigationSections(body: String): List<NavSection> {
    val lines = body.lines()
    val result = mutableListOf<NavSection>()
    var i = 0
    while (i < lines.size) {
        val match = NAV_OP.find(lines[i])
        if (match != null) {
            val op = when (match.groupValues[1]) {
                "Goto" -> NavOp.Goto
                "Usages" -> NavOp.Usages
                else -> NavOp.Documentation
            }
            var oldLine = ""
            var newLine = ""
            i++
            while (i < lines.size && !lines[i].startsWith("***")) {
                val raw = lines[i]
                if (raw.startsWith("-")) oldLine = raw.substring(1)
                if (raw.startsWith("+")) newLine = raw.substring(1)
                i++
            }
            if (oldLine.isNotEmpty() && newLine.isNotEmpty()) {
                result += NavSection(op, oldLine, newLine)
            }
        } else {
            i++
        }
    }
    return result
}

// -- Route --

internal fun Route.navigationRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    val sessions: RestSessionService = service()

    post<Api.NavigationPath> { resource: Api.NavigationPath ->
        val target =
            when (val resolved = call.resolveFileRouteTarget(sessions, resolver, resource.path.toRoutePath())) {
                is RestFileRouteTarget.ProjectFile -> resolved.target
                is RestFileRouteTarget.VirtualFileReadOnly -> {
                    handleNavigation(
                        call = call,
                        project = resolved.project,
                        file = resolved.file,
                        filePath = resolved.file.url,
                    )
                    return@post
                }

                null -> null
            }
                ?: return@post
        handleSessionNavigation(
            call,
            target,
        )
    }
}

private fun normalizeSelectedLine(documentText: CharSequence, line: String): String {
    if (documentText.indexOf(line) >= 0) return line
    return line.removePrefix(" ").takeIf { it != line && documentText.indexOf(it) >= 0 } ?: line
}

internal suspend fun handleSessionNavigation(
    call: io.ktor.server.application.ApplicationCall,
    target: RestSessionRouteTarget,
) {
    val access = resolveProjectFileAccess(target.project, target.root, target.relativePath)
    val file = access.file
    if (file == null) {
        call.respond(HttpStatusCode.NotFound, RestError("File not found: ${target.relativePath}"))
        return
    }
    handleNavigation(call, target.project, file, target.relativePath)
}

private suspend fun handleNavigation(
    call: io.ktor.server.application.ApplicationCall,
    project: Project,
    file: VirtualFile,
    filePath: String,
) {
    val body = call.receiveText()
    val sections = splitNavigationSections(body)
    if (sections.isEmpty()) {
        call.respond(HttpStatusCode.BadRequest, RestError("No valid navigation sections"))
        return
    }
    val gotos = mutableListOf<NavGoto>()
    val usages = mutableListOf<NavUsages>()
    val docs = mutableListOf<NavDocumentation>()
    val failed = mutableListOf<String>()
    sections.forEach { section ->
        try {
            val range = resolveSelection(project, file, filePath, section.oldLine, section.newLine)
            when (section.op) {
                NavOp.Goto -> gotos += executeGoto(filePath, range)
                NavOp.Usages -> usages += executeUsages(filePath, range)
                NavOp.Documentation -> docs += executeDocumentation(filePath, range)
            }
        } catch (e: Exception) {
            failed += "$filePath: ${e.message}"
        }
    }
    call.respond(NavigationResponse(gotos, usages, docs, failed))
}

// -- Selection resolution --

private data class SelectionRange(
    val psiFile: PsiFile,
    val targetElement: PsiElement,
    val selectionStart: Int,
)

private suspend fun resolveSelection(
    project: Project,
    file: VirtualFile,
    filePath: String,
    oldLine: String,
    newLine: String,
): SelectionRange {
    return readAction {
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: throw NoSuchElementException("No PSI file for: $filePath")
        val doc = getOrCreateDocument(file)
            ?: throw NoSuchElementException("No document for: $filePath")
        val normalizedOldLine = normalizeSelectedLine(doc.immutableCharSequence, oldLine)
        val normalizedNewLine = normalizeSelectedLine(doc.immutableCharSequence, newLine)
        val oldIdx = doc.immutableCharSequence.indexOf(normalizedOldLine)
        if (oldIdx < 0) throw NoSuchElementException("Old line not found in document")
        val changeStart = normalizedOldLine.commonPrefixWith(normalizedNewLine).length
        val selStart = oldIdx + changeStart
        val target =
            psiFile.findElementAt(selStart) ?: throw NoSuchElementException("No PSI element at offset $selStart")
        SelectionRange(psiFile, target, selStart)
    }
}

// -- Goto --

private suspend fun executeGoto(
    filePath: String,
    range: SelectionRange,
): NavGoto {
    return readAction {
        val results = mutableListOf<NavTarget>()

        val project = range.psiFile.project
        // path 1: GotoDeclarationHandler
        val gotoDoc = range.psiFile.virtualFile?.let(::getOrCreateDocument)
        if (gotoDoc != null) {
            try {
                val editor = ImaginaryEditor(project, gotoDoc)
                @Suppress("CAST_NEVER_SUCCEEDS")
                for (handler in (GotoDeclarationHandler.EP_NAME as ExtensionPointName<GotoDeclarationHandler>).extensionList) {
                    try {
                        val targets =
                            handler.getGotoDeclarationTargets(range.targetElement, range.selectionStart, editor)
                                ?: continue
                        targets.mapNotNull { toNavTarget(it) }.forEach { results += it }
                    } catch (_: NotImplementedError) {
                    } catch (_: UnsupportedOperationException) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        // path 2: PsiReference.resolve()
        val ref = range.psiFile.findReferenceAt(range.selectionStart)
        val resolved = ref?.resolve()
        toNavTarget(resolved)?.let { results += it }

        val unique = results.distinctBy { "${it.fileUrl}:${it.lineNumber}:${it.column}" }
        NavGoto(
            path = filePath,
            result = unique.firstOrNull() ?: NavTarget(
                fileUrl = filePath,
                encodedFileUrl = encodeRoutePathSegment(filePath),
                lineNumber = 1,
                column = 1,
            ),
        )
    }
}

// -- Usages --

private suspend fun executeUsages(
    filePath: String,
    range: SelectionRange,
): NavUsages {
    return readAction {
        val ref = range.psiFile.findReferenceAt(range.selectionStart)?.resolve()
        val searchTarget = ref?.navigationElement ?: range.targetElement
        val maxResults = 100
        @Suppress("CAST_NEVER_SUCCEEDS")
        val handler = (FindUsagesHandlerFactory.EP_NAME as ExtensionPointName<FindUsagesHandlerFactory>)
            .getExtensionList(range.psiFile.project)
            .firstNotNullOfOrNull { factory ->
                if (factory.canFindUsages(searchTarget)) {
                    factory.createFindUsagesHandler(
                        searchTarget,
                        FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS,
                    )
                } else {
                    null
                }
            }

        if (handler != null) {
            val options = handler.getFindUsagesOptions(null)
            val usages = mutableListOf<UsageInfo>()
            handler.processElementUsages(
                searchTarget,
                Processor { usage ->
                    usages += usage
                    usages.size < maxResults
                },
                options,
            )
            val results = usages.mapNotNull { toNavTarget(it) }
            return@readAction NavUsages(
                path = filePath,
                results = results.distinctBy { "${it.fileUrl}:${it.lineNumber}:${it.column}" },
                truncated = results.size >= maxResults,
            )
        }
        // Fallback: no handler (private/internal symbols)
        val searchScope = GlobalSearchScope.projectScope(range.psiFile.project)
        val usages = mutableListOf<UsageInfo>()
        ReferencesSearch.search(searchTarget, searchScope).forEach(
            Processor { reference ->
                usages += UsageInfo(reference)
                usages.size < maxResults
            },
        )
        val results = usages.mapNotNull { toNavTarget(it) }
        return@readAction NavUsages(
            path = filePath,
            results = results.distinctBy { "${it.fileUrl}:${it.lineNumber}:${it.column}" },
            truncated = results.size >= maxResults,
        )
    }
}

// -- Documentation --

private suspend fun executeDocumentation(
    filePath: String,
    range: SelectionRange,
): NavDocumentation {
    return readAction {
        val ref = range.psiFile.findReferenceAt(range.selectionStart)?.resolve()
        val docTarget = ref ?: range.targetElement
        val provider = LanguageDocumentation.INSTANCE.forLanguage(docTarget.language)
        val doc = provider?.generateDoc(docTarget, range.targetElement)
            ?: provider?.generateHoverDoc(docTarget, range.targetElement) ?: ""
        val name = (docTarget as? PsiNamedElement)?.name ?: docTarget.text.take(80)
        NavDocumentation(path = filePath, name = name, documentation = doc)
    }
}

// -- Converters --

private fun toNavTarget(element: PsiElement?): NavTarget? {
    val file = element?.containingFile?.virtualFile ?: return null
    val offset = element.textOffset
    return toNavTarget(file, offset)
}

private fun toNavTarget(reference: PsiReference): NavTarget? {
    val element = reference.element
    val file = element.containingFile?.virtualFile ?: return null
    val offset = element.textRange.startOffset + reference.rangeInElement.startOffset
    return toNavTarget(file, offset)
}

private fun toNavTarget(usage: UsageInfo): NavTarget? {
    val file = usage.virtualFile ?: return null
    val offset = usage.navigationRange?.startOffset ?: return null
    return toNavTarget(file, offset)
}

private fun toNavTarget(file: VirtualFile, offset: Int): NavTarget? {
    val doc = getOrCreateDocument(file) ?: return null
    val safeOffset = offset.coerceIn(0, doc.textLength)
    val line = doc.getLineNumber(safeOffset) + 1
    val lineStart = doc.getLineStartOffset(line - 1)
    val column = offset - lineStart + 1
    val fileUrl = file.url
    return NavTarget(fileUrl, encodeRoutePathSegment(fileUrl), line, column)
}

@Serializable
private data class NavigationResponse(
    val applied: List<NavGoto> = emptyList(),
    val appliedUsages: List<NavUsages> = emptyList(),
    val appliedDocs: List<NavDocumentation> = emptyList(),
    val failed: List<String> = emptyList(),
) : TextBody {
    override fun renderTextBody(): String = buildString {
        applied.forEach { entry ->
            appendLine("goto: ${entry.path}")
            appendLine("  → ${entry.result.fileUrl}:${entry.result.lineNumber}:${entry.result.column}")
        }
        appliedUsages.forEach { entry ->
            appendLine("usages: ${entry.path}")
            entry.results.forEach { r ->
                appendLine("  → ${r.fileUrl}:${r.lineNumber}:${r.column}")
            }
            if (entry.truncated) appendLine("  (truncated)")
        }
        appliedDocs.forEach { entry ->
            appendLine("documentation: ${entry.path}")
            appendLine("  name: ${entry.name}")
            if (entry.documentation.isNotEmpty()) appendLine("  ${entry.documentation}")
        }
        failed.forEach { appendLine("FAILED: $it") }
    }
}
