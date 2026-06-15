package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.CommandProcessorEx
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend inline fun <T> runUndoable(
    project: Project,
    name: String,
    block: () -> T,
): T {
    val token = withContext(Dispatchers.EDT) {
        val cmd = CommandProcessor.getInstance() as CommandProcessorEx
        cmd.startCommand(project, name, null, UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
    } ?: error("startCommand returned null")
    try {
        return block()
    } finally {
        withContext(Dispatchers.EDT) {
            val cmd = CommandProcessor.getInstance() as CommandProcessorEx
            cmd.finishCommand(token, null)
        }
    }
}
