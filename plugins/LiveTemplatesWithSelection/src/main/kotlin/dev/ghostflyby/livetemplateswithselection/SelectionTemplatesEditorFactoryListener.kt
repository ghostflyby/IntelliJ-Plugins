package dev.ghostflyby.livetemplateswithselection
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.util.Key
import kotlin.text.contentEquals

@Service
internal class PluginDisposable : Disposable.Default

internal class SelectionTemplatesEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) =
        event.editor.run {
            if (isViewer) return
            val disposable = service<PluginDisposable>()
            document.addDocumentListener(MyDocumentListener, disposable)
            selectionModel.addSelectionListener(MySelectionListener, disposable)
        }

}

private val previousSelectionKey = Key<String>("previousSelection")
private val replacedSelectionKey = Key<String>("replacedSelection")


private var Document.previousSelection by previousSelectionKey
internal var Document.replacedSelection by replacedSelectionKey

private object MySelectionListener : SelectionListener {
    override fun selectionChanged(e: SelectionEvent) = e.editor.run {
        if (!selectionModel.hasSelection()) {
            document.replacedSelection = null
            return
        }
        document.previousSelection = selectionModel.selectedText
    }
}

private object MyDocumentListener : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
        val doc = event.document


        if (doc.previousSelection.contentEquals(event.oldFragment)) {
            doc.replacedSelection = doc.previousSelection
        }
    }
}

