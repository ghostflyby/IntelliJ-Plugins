package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

@TestApplication
internal class WorkspaceGlobQueryTest {
    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "glob-query-test")
    private val sourceRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path.of("globRoot")),
    )
    private lateinit var root: VirtualFile
    private lateinit var foo: VirtualFile
    private lateinit var bar: VirtualFile
    private lateinit var text: VirtualFile
    private lateinit var controller: VirtualFile

    @BeforeEach
    fun refreshFixtureContent() {
        val rootPath = projectPathFixture.get().resolve("globRoot")
        rootPath.createDirectories()
        rootPath.resolve("Foo.kt").writeText("class Foo")
        rootPath.resolve("Bar.kt").writeText("class Bar")
        rootPath.resolve("Note.txt").writeText("note")
        rootPath.resolve("UserController").writeText("controller")
        root = sourceRootFixture.get().virtualFile
        root.refresh(false, true)
        foo = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath.resolve("Foo.kt")))
        bar = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath.resolve("Bar.kt")))
        text = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath.resolve("Note.txt")))
        controller = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath.resolve("UserController")),
        )
    }

    @Test
    fun `literal file name uses filename index candidates first`() = runBlocking {
        val lookup = RecordingGlobCandidateLookup(
            nameFiles = listOf(foo),
            typeFiles = listOf(foo, bar),
        )

        val result = readAction { readGlobPaths(root, listOf("**/Foo.kt"), project, lookup) }

        assertEquals(listOf("Foo.kt"), result)
        assertEquals(1, lookup.nameCalls)
        assertEquals(0, lookup.typeCalls)
        assertEquals(0, lookup.walkCalls)
    }

    @Test
    fun `extension pattern uses file type index candidates`() = runBlocking {
        val lookup = RecordingGlobCandidateLookup(
            nameFiles = emptyList(),
            typeFiles = listOf(text),
        )

        val result = readAction { readGlobPaths(root, listOf("**/*.txt"), project, lookup) }

        assertEquals(listOf("Note.txt"), result)
        assertEquals(0, lookup.nameCalls)
        assertEquals(1, lookup.typeCalls)
        assertEquals(0, lookup.walkCalls)
    }

    @Test
    fun `unindexed pattern falls back to VFS walk`() = runBlocking {
        val lookup = RecordingGlobCandidateLookup(
            nameFiles = emptyList(),
            typeFiles = emptyList(),
            walkFiles = listOf(foo, bar, text, controller),
        )

        val result = readAction { readGlobPaths(root, listOf("**/*Controller"), project, lookup) }

        assertEquals(listOf("UserController"), result)
        assertEquals(0, lookup.nameCalls)
        assertEquals(0, lookup.typeCalls)
        assertEquals(1, lookup.walkCalls)
    }

    @Test
    fun `indexed candidates are still filtered by glob matcher`() = runBlocking {
        val lookup = RecordingGlobCandidateLookup(
            nameFiles = emptyList(),
            typeFiles = listOf(text, foo),
        )

        val result = readAction { readGlobPaths(root, listOf("**/*.txt"), project, lookup) }

        assertEquals(listOf("Note.txt"), result)
        assertEquals(0, lookup.nameCalls)
        assertEquals(1, lookup.typeCalls)
        assertEquals(0, lookup.walkCalls)
    }

    private class RecordingGlobCandidateLookup(
        private val nameFiles: Collection<VirtualFile>,
        private val typeFiles: Collection<VirtualFile>,
        private val walkFiles: Collection<VirtualFile> = emptyList(),
    ) : GlobCandidateLookup {
        var nameCalls: Int = 0
        var typeCalls: Int = 0
        var walkCalls: Int = 0

        override fun filesByName(
            project: Project,
            fileName: String,
            scope: GlobalSearchScope,
        ): Collection<VirtualFile> {
            nameCalls++
            return nameFiles
        }

        override fun filesByType(fileType: FileType, scope: GlobalSearchScope): Collection<VirtualFile> {
            typeCalls++
            return typeFiles
        }

        override fun walkFiles(root: VirtualFile): Collection<VirtualFile> {
            walkCalls++
            return walkFiles
        }
    }
}
