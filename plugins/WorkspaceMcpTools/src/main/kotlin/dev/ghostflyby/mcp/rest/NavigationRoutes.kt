package dev.ghostflyby.mcp.rest

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
private data class NavTarget(
    val fileUrl: String,
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

private val NAV_OP = Regex("""^\*\*\* (Goto|Usages|Documentation)""")

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
                if (raw.startsWith("- ")) oldLine = raw.substring(2)
                if (raw.startsWith("+ ")) newLine = raw.substring(2)
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

    post<Api.Project.NavigationPath> { resource: Api.Project.NavigationPath ->
        val body = call.receiveText()
        val sections = splitNavigationSections(body)
        if (sections.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No valid navigation sections"))
            return@post
        }
        val projectKey = resource.parent.projectKey
        when (val resolved = resolver.resolve(projectKey = projectKey)) {
            is WorkspaceProjectResolution.Resolved -> {
                val project = resolved.project
                val gotos = mutableListOf<NavGoto>()
                val usages = mutableListOf<NavUsages>()
                val docs = mutableListOf<NavDocumentation>()
                val failed = mutableListOf<String>()
                val filePath = resource.relativePath.toRoutePath()
                sections.forEach { section ->
                    try {
                val filePath = resource.relativePath.toRoutePath()
                val range = resolveSelection(project, section.oldLine, section.newLine, filePath)
                        when (section.op) {
                            NavOp.Goto -> gotos += executeGoto(filePath, range)
                            NavOp.Usages -> usages += executeUsages(filePath, range)
                            NavOp.Documentation -> docs += executeDocumentation(filePath, range)
                        }
                    } catch (e: Exception) {
                        failed += "$filePath: ${e.message}"
                    }
                }
                call.respond(
                    mapOf(
                        "applied" to (gotos + usages + docs),
                        "failed" to failed,
                    ),
                )
            }

            is WorkspaceProjectResolution.Unresolved ->
                call.respond(HttpStatusCode.NotFound, mapOf("error" to resolved.message))
        }
    }
}

// -- Selection resolution --

private data class SelectionRange(
    val psiFile: PsiFile,
    val targetElement: PsiElement,
    val selectionStart: Int,
)

private suspend fun resolveSelection(
    project: Project,
    oldLine: String,
    newLine: String,
    filePath: String,
): SelectionRange {
    return readAction {
        val vf = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
            .findFileByNioPath(Path.of(requireNotNull(project.basePath), filePath))
            ?: throw NoSuchElementException("File not found: $filePath")
        val psiFile = PsiManager.getInstance(project).findFile(vf)
            ?: throw NoSuchElementException("No PSI file for: $filePath")
        val doc = FileDocumentManager.getInstance().getDocument(vf)
            ?: throw NoSuchElementException("No document for: $filePath")
        val oldIdx = doc.immutableCharSequence.indexOf(oldLine)
        if (oldIdx < 0) throw NoSuchElementException("Old line not found in document")
        val changeStart = oldLine.commonPrefixWith(newLine).length
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
        val gotoDoc = PsiDocumentManager.getInstance(project).getDocument(range.psiFile)
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
        NavGoto(path = filePath, result = unique.firstOrNull() ?: NavTarget(filePath, 1, 1))
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
        @Suppress("CAST_NEVER_SUCCEEDS") val handler =
            (FindUsagesHandlerFactory.EP_NAME as ExtensionPointName<FindUsagesHandlerFactory>).extensionList
                .firstNotNullOfOrNull { factory ->
                    if (factory.canFindUsages(searchTarget))
                        factory.createFindUsagesHandler(
                            searchTarget,
                            FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS,
                        )
                    else null
                } ?: return@readAction NavUsages(path = filePath, results = emptyList())

        val options = handler.getFindUsagesOptions(null)
        val usages = mutableListOf<UsageInfo>()
        val maxResults = 100
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

private fun toNavTarget(usage: UsageInfo): NavTarget? {
    val file = usage.virtualFile ?: return null
    val offset = usage.navigationRange?.startOffset ?: return null
    return toNavTarget(file, offset)
}

private fun toNavTarget(file: VirtualFile, offset: Int): NavTarget? {
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
    val safeOffset = offset.coerceIn(0, doc.textLength)
    val line = doc.getLineNumber(safeOffset) + 1
    val lineStart = doc.getLineStartOffset(line - 1)
    val column = offset - lineStart + 1
    return NavTarget(file.url, line, column)
}
