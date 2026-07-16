/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestApplication
internal class SpotlessProjectServiceTest {
    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val sourceRootFixture = projectFixture
        .moduleFixture(name = "spotless-test")
        .sourceRootFixture(
            pathFixture = projectFixture.pathInProjectFixture(Path.of("src")),
        )
    private val spotlessServiceFixture = projectFixture.spotlessServiceFixture()
    private val spotlessHarness by spotlessServiceFixture
    private val spotless: SpotlessProjectService
        get() = spotlessHarness.service
    private val providerParentDisposableFixture = disposableFixture()
    private val providerParentDisposable by providerParentDisposableFixture
    private val preprocessorParentDisposableFixture = disposableFixture()
    private val preprocessorParentDisposable by preprocessorParentDisposableFixture

    @Test
    suspend fun `can format and format use spotless daemon responses`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { query ->
                    if (query.contains("dryrun=")) {
                        DaemonResponse(HttpStatusCode.OK)
                    } else {
                        DaemonResponse(HttpStatusCode.OK, "formatted-content")
                    }
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("Test.kt", "unformatted-content")

        assertFalse(spotless.canFormatSync(virtualFile))
        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })
        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(
            SpotlessFormatResult.Dirty("formatted-content"),
            spotless.format(virtualFile, "unformatted-content"),
        )
        assertTrue(spotless.canFormatSync(virtualFile))
        assertEquals(1, provider.startCount)
    }

    @Test
    suspend fun `provider lookup continues when an earlier provider does not cover the file`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val skippedProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25251),
            targetResolver = { null },
        )
        val selectedProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.daemonProvidersLookup = { listOf(skippedProvider, selectedProvider) }
        val virtualFile = createProjectFile("ProviderFallback.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(0, skippedProvider.startCount)
        assertEquals(1, selectedProvider.startCount)
    }

    @Test
    suspend fun `formatting preprocessor receives the PSI target and skips matching daemon steps`() {
        val stepPaths = mutableListOf<String?>()
        val formatBodies = mutableListOf<String>()
        val formatSkipSteps = mutableListOf<List<String>>()
        val applicableFiles = mutableListOf<String>()
        val preprocessor = TestFormattingPreprocessor(
            isApplicable = { psiFile ->
                applicableFiles += psiFile.name
                psiFile.name == "ImportSteps.java"
            },
            process = {
                SpotlessFormattingPreprocessResult(
                    content = "optimized-imports",
                    skippedSteps = setOf("forbidWildcardImports", "expandWildcardImports"),
                )
            },
        )
        maskPreprocessors(preprocessor)
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = { request ->
                    stepPaths += request.url.parameters["path"]
                    DaemonResponse(HttpStatusCode.OK, "expandWildcardImports\nforbidWildcardImports\n")
                },
                format = { DaemonResponse(HttpStatusCode.OK, "formatted-content") },
                onFormat = { request ->
                    formatBodies += request.bodyText()
                    formatSkipSteps += request.url.parameters.getAll("skipStep").orEmpty()
                },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("ImportSteps.java", "class ImportSteps {}")

        assertEquals(
            SpotlessFormatResult.Dirty("formatted-content"),
            spotless.format(virtualFile, "original-content"),
        )
        assertEquals(listOf("ImportSteps.java"), applicableFiles)
        assertEquals(1, preprocessor.processCount)
        assertEquals(listOf(virtualFile.virtualFile.path), stepPaths)
        assertEquals(listOf("optimized-imports"), formatBodies)
        assertEquals(
            listOf(listOf("expandWildcardImports", "forbidWildcardImports")),
            formatSkipSteps,
        )
    }

    @Test
    suspend fun `Java wildcard import preprocessor recognizes Java PSI and ignores unrelated steps`() {
        val psiFile = createProjectFile("UnusedImport.java", "class UnusedImport {}")
        val preprocessor = JavaWildcardImportPreprocessor()
        readAction {
            assertTrue(preprocessor.isApplicableTo(psiFile))
        }

        assertNull(
            preprocessor.preprocess(
                TestFormattingPreprocessContext(
                    psiFile = psiFile,
                    content = "class UnusedImport {}",
                    daemonSteps = listOf("googleJavaFormat"),
                ),
            ),
        )
    }

    @Test
    suspend fun `no applicable preprocessor does not inspect daemon steps`() {
        val stepsCount = AtomicInteger()
        val preprocessor = TestFormattingPreprocessor(isApplicable = { false })
        maskPreprocessors(preprocessor)
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = {
                    stepsCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK, "expandWildcardImports\n")
                },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("NoPreprocessor.java", "class NoPreprocessor {}")

        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "content"))
        assertEquals(1, preprocessor.applicabilityCount)
        assertEquals(0, stepsCount.get())
        assertEquals(0, preprocessor.processCount)
    }

    @Test
    suspend fun `preprocessor without matching steps does not transform formatting content`() {
        val formatBodies = mutableListOf<String>()
        val formatSkipSteps = mutableListOf<List<String>>()
        val preprocessor = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { context ->
                assertEquals(listOf("googleJavaFormat"), context.daemonSteps)
                null
            },
        )
        maskPreprocessors(preprocessor)
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = { DaemonResponse(HttpStatusCode.OK, "googleJavaFormat\n") },
                format = { DaemonResponse(HttpStatusCode.OK) },
                onFormat = { request ->
                    formatBodies += request.bodyText()
                    formatSkipSteps += request.url.parameters.getAll("skipStep").orEmpty()
                },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("NoMatchingSteps.java", "class NoMatchingSteps {}")

        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "original-content"))
        assertEquals(1, preprocessor.processCount)
        assertEquals(listOf("original-content"), formatBodies)
        assertEquals(listOf(emptyList<String>()), formatSkipSteps)
    }

    @Test
    suspend fun `non Java and dry run formatting do not inspect Java import steps`() {
        val stepsCount = AtomicInteger()
        val preprocessor = TestFormattingPreprocessor(
            isApplicable = { psiFile -> psiFile.name.endsWith(".java") },
            process = { SpotlessFormattingPreprocessResult("optimized-imports") },
        )
        maskPreprocessors(preprocessor)
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = {
                    stepsCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK, "expandWildcardImports")
                },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val kotlinFile = createProjectFile("NoImportSteps.kt", "fun main() = Unit")
        val javaFile = createProjectFile("DryRun.java", "class DryRun {}")

        assertEquals(SpotlessFormatResult.Clean, spotless.format(kotlinFile, "content"))
        assertTrue(spotless.canFormat(javaFile))
        assertEquals(0, stepsCount.get())
        assertEquals(0, preprocessor.processCount)
    }

    @Test
    suspend fun `unavailable formatting preprocessing falls back to normal daemon formatting`() {
        val formatBodies = mutableListOf<String>()
        val formatSkipSteps = mutableListOf<List<String>>()
        val preprocessor = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { null },
        )
        maskPreprocessors(preprocessor)
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = { DaemonResponse(HttpStatusCode.OK, "expandWildcardImports") },
                format = { DaemonResponse(HttpStatusCode.OK) },
                onFormat = { request ->
                    formatBodies += request.bodyText()
                    formatSkipSteps += request.url.parameters.getAll("skipStep").orEmpty()
                },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("Unavailable.java", "class Unavailable {}")

        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "original-content"))
        assertEquals(1, preprocessor.processCount)
        assertEquals(listOf("original-content"), formatBodies)
        assertEquals(listOf(emptyList<String>()), formatSkipSteps)
    }

    @Test
    suspend fun `failing formatting preprocessing falls back to normal daemon formatting`() {
        val formatBodies = mutableListOf<String>()
        val formatSkipSteps = mutableListOf<List<String>>()
        val preprocessor = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { error("preprocessing failed") },
        )
        maskPreprocessors(preprocessor)
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = { DaemonResponse(HttpStatusCode.OK, "expandWildcardImports") },
                format = { DaemonResponse(HttpStatusCode.OK) },
                onFormat = { request ->
                    formatBodies += request.bodyText()
                    formatSkipSteps += request.url.parameters.getAll("skipStep").orEmpty()
                },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("FailingPreprocessor.java", "class FailingPreprocessor {}")

        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "original-content"))
        assertEquals(1, preprocessor.processCount)
        assertEquals(listOf("original-content"), formatBodies)
        assertEquals(listOf(emptyList<String>()), formatSkipSteps)
    }

    @Test
    suspend fun `formatting preprocessor step lookup failure falls back to normal daemon formatting`() {
        val formatSkipSteps = mutableListOf<List<String>>()
        val preprocessor = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { SpotlessFormattingPreprocessResult("optimized-imports") },
        )
        maskPreprocessors(preprocessor)
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = { throw IOException("steps unavailable") },
                format = { DaemonResponse(HttpStatusCode.OK) },
                onFormat = { request ->
                    formatSkipSteps += request.url.parameters.getAll("skipStep").orEmpty()
                },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("StepFailure.java", "class StepFailure {}")

        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "original-content"))
        assertEquals(0, preprocessor.processCount)
        assertEquals(listOf(emptyList<String>()), formatSkipSteps)
    }

    @Test
    suspend fun `formatting preprocessors run in extension order and preserve daemon step order`() {
        val inputs = mutableListOf<CharSequence>()
        val first = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { request ->
                inputs += request.content
                SpotlessFormattingPreprocessResult("first", setOf("forbidWildcardImports"))
            },
        )
        val second = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { request ->
                inputs += request.content
                SpotlessFormattingPreprocessResult("second", setOf("expandWildcardImports", "notConfigured"))
            },
        )
        maskPreprocessors(first, second)
        val formatBodies = mutableListOf<String>()
        val formatSkipSteps = mutableListOf<List<String>>()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = {
                    DaemonResponse(
                        HttpStatusCode.OK,
                        "expandWildcardImports\ngoogleJavaFormat\nforbidWildcardImports\n",
                    )
                },
                format = { DaemonResponse(HttpStatusCode.OK) },
                onFormat = { request ->
                    formatBodies += request.bodyText()
                    formatSkipSteps += request.url.parameters.getAll("skipStep").orEmpty()
                },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("Pipeline.java", "class Pipeline {}")

        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "original"))
        assertEquals(listOf("original", "first"), inputs)
        assertEquals(listOf("second"), formatBodies)
        assertEquals(
            listOf(listOf("expandWildcardImports", "forbidWildcardImports")),
            formatSkipSteps,
        )
    }

    @Test
    suspend fun `disposed formatting preprocessor is not invoked again`() {
        val preprocessor = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { SpotlessFormattingPreprocessResult("processed") },
        )
        val preprocessorDisposable = maskPreprocessors(preprocessor)
        val stepsCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = {
                    stepsCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK, "expandWildcardImports")
                },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("Dynamic.kt", "fun dynamic() = Unit")

        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "original"))
        Disposer.dispose(preprocessorDisposable)
        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "original"))

        assertEquals(1, preprocessor.processCount)
        assertEquals(1, stepsCount.get())
    }

    @Test
    suspend fun `can format sync refreshes retryable misses without file edits`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        val currentProvider = AtomicReference<SpotlessDaemonProvider?>()
        spotless.daemonProvidersLookup = { listOfNotNull(currentProvider.get()) }
        val virtualFile = createProjectFile("RetryableMiss.kt", "content")

        assertFalse(spotless.canFormatSync(virtualFile))
        assertTrue(waitUntil { !spotless.canFormatSync(virtualFile) })
        currentProvider.set(provider)
        spotless.canFormatSync(virtualFile)

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })
    }

    @Test
    suspend fun `can format sync cache is scoped per file`() {
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("ScopedCache.kt", "content")
        val otherVirtualFile = createProjectFile("OtherScopedCache.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })
        assertFalse(spotless.canFormatSync(otherVirtualFile))
    }

    @Test
    suspend fun `can format sync revalidates cached true after daemon failure`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { query ->
                    if (query.contains("dryrun=")) {
                        DaemonResponse(HttpStatusCode.OK)
                    } else {
                        DaemonResponse(HttpStatusCode.OK, "formatted-content")
                    }
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("Revalidate.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })

        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { throw IOException("connection refused") },
                format = { DaemonResponse(HttpStatusCode.OK, "formatted-content") },
            ),
        )

        assertTrue(spotless.canFormatSync(virtualFile))
        assertEquals(
            SpotlessFormatResult.Error("Spotless Daemon is not responding"),
            spotless.format(virtualFile, "content"),
        )
        assertTrue(waitUntil { !spotless.canFormatSync(virtualFile) })
    }

    @Test
    suspend fun `release daemon invalidates cached can format sync result`() {
        val stopCount = AtomicInteger()
        val firstCleanupStopCount = AtomicInteger(-1)
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { query ->
                    if (query.contains("dryrun=")) {
                        DaemonResponse(HttpStatusCode.OK)
                    } else {
                        DaemonResponse(HttpStatusCode.OK, "formatted-content")
                    }
                },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
            onCleanup = {
                firstCleanupStopCount.compareAndSet(-1, stopCount.get())
            },
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("Cached.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })

        assertEquals(1, spotless.releaseAllDaemons())
        spotless.daemonProvidersLookup = { emptyList() }
        assertFalse(spotless.canFormatSync(virtualFile))
        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(1, stopCount.get())
        assertEquals(1, firstCleanupStopCount.get())
        spotless.daemonProvidersLookup = { listOf(provider) }
        assertTrue(spotless.canFormat(virtualFile))
    }

    @Test
    suspend fun `provider process termination removes daemon and invalidates cached can format sync result`() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("NaturalExit.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))

        provider.terminate()

        assertTrue(waitUntil { !spotless.hasRunningDaemons() })
        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(0, stopCount.get())
        assertFalse(spotless.canFormatSync(virtualFile))
    }

    @Test
    suspend fun `provider switch stops old daemon before new handle wins`() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val oldProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        val newProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25253),
        )
        val currentProvider = AtomicReference<SpotlessDaemonProvider>(oldProvider)
        spotless.daemonProvidersLookup = { listOf(currentProvider.get()) }
        val virtualFile = createProjectFile("ProviderSwitch.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))

        currentProvider.set(newProvider)
        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "content"))

        assertEquals(1, oldProvider.startCount)
        assertTrue(waitUntil { oldProvider.completionCount.get() == 1 })
        assertEquals(1, stopCount.get())
        assertEquals(1, newProvider.startCount)
    }

    @Test
    suspend fun `project dispose stops running daemons and runs provider cleanup`() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("Dispose.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))

        spotlessHarness.close()

        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(1, stopCount.get())
        assertFalse(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `provider extension removal releases owned daemons`() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val providerDisposable = registerProvider(provider, "SpotlessProjectServiceTest.provider")
        val virtualFile = createProjectFile("ProviderRemoved.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))

        Disposer.dispose(providerDisposable)

        assertEquals(1, provider.completionCount.get())
        assertEquals(1, stopCount.get())
        assertFalse(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `attachment cleanup is lifo exactly once and continues after failure`() {
        val cleanupOrder = Collections.synchronizedList(mutableListOf<String>())
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
            lifecycleSetup = { lifecycle ->
                lifecycle.registerCleanup { cleanupOrder.add("first") }
                lifecycle.registerCleanup {
                    cleanupOrder.add("second")
                    throw IOException("cleanup failure")
                }
                lifecycle.registerCleanup { cleanupOrder.add("third") }
            },
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("LifoCleanup.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(1, spotless.releaseAllDaemons())
        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(listOf("third", "second", "first"), cleanupOrder)
        assertEquals(0, spotless.releaseAllDaemons())
        assertEquals(1, provider.completionCount.get())
    }

    @Test
    suspend fun `concurrent requests share one starting daemon`() = supervisorScope {
        val startGate = CompletableDeferred<Unit>()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
            startGate = startGate,
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("ConcurrentStart.kt", "content")

        val first = async(Dispatchers.Default) { spotless.canFormat(virtualFile) }
        assertTrue(waitUntil { provider.startCount == 1 })
        val second = async(Dispatchers.Default) { spotless.canFormat(virtualFile) }
        delay(100.milliseconds)
        assertEquals(1, provider.startCount)

        startGate.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, provider.startCount)
    }

    @Test
    suspend fun `provider extension removal cancels starting daemon before returning`() = supervisorScope {
        val startGate = CompletableDeferred<Unit>()
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
            startGate = startGate,
            lifecycleSetup = { lifecycle ->
                lifecycle.registerCleanup { startGate.complete(Unit) }
            },
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val providerDisposable = registerProvider(provider, "SpotlessProjectServiceTest.startingProvider")
        val virtualFile = createProjectFile("ProviderRemovedDuringStart.kt", "content")

        val formatting = async(Dispatchers.Default) { spotless.canFormat(virtualFile) }
        assertTrue(waitUntil { provider.startCount == 1 })

        Disposer.dispose(providerDisposable)

        assertEquals(1, provider.completionCount.get())
        assertFalse(spotless.hasRunningDaemons())
        assertTrue(formatting.isCompleted)
        assertTrue(runCatching { formatting.await() }.isFailure)
        assertEquals(0, stopCount.get())
    }

    @Test
    suspend fun `can format returns false when file is not covered`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.NotFound) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("NotCovered.kt", "content")

        assertFalse(spotless.canFormat(virtualFile))
        assertEquals(
            SpotlessFormatResult.NotCovered,
            spotless.format(virtualFile, "content"),
        )
    }

    @Test
    suspend fun `format returns error when daemon health check fails`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { throw IOException("connection refused") },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("HealthCheck.kt", "content")

        assertEquals(
            SpotlessFormatResult.Error("Spotless Daemon is not responding"),
            spotless.format(virtualFile, "content"),
        )
        assertFalse(spotless.canFormat(virtualFile))
    }

    @Test
    suspend fun `release all daemons stops project daemons`() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("ReleaseAll.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))

        assertEquals(1, spotless.releaseAllDaemons())
        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(1, stopCount.get())
        assertFalse(spotless.hasRunningDaemons())
    }

    private suspend fun createProjectFile(name: String, content: String): PsiFile {
        val virtualFile = backgroundWriteAction {
            val sourceRoot = sourceRootFixture.get().virtualFile
            val file = sourceRoot.findChild(name) ?: sourceRoot.createChildData(this, name)
            file.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
            file
        }
        return readAction {
            requireNotNull(PsiManager.getInstance(projectFixture.get()).findFile(virtualFile))
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean): Boolean =
        withTimeoutOrNull(2.seconds) {
            while (!condition()) {
                delay(25.milliseconds)
            }
            true
        } ?: condition()

    private fun registerProvider(
        provider: SpotlessDaemonProvider,
        name: String,
    ) = Disposer.newDisposable(name).also { providerDisposable ->
        Disposer.register(providerParentDisposable, providerDisposable)
        @Suppress("CAST_NEVER_SUCCEEDS")
        ExtensionPointName.create<SpotlessDaemonProvider>("dev.ghostflyby.spotless.spotlessDaemonProvider")
            .point
            .registerExtension(provider, providerDisposable)
    }

    private fun maskPreprocessors(vararg preprocessors: SpotlessFormattingPreprocessor): Disposable =
        Disposer.newDisposable("test formatting preprocessors").also { preprocessorDisposable ->
            Disposer.register(preprocessorParentDisposable, preprocessorDisposable)
            ExtensionTestUtil.maskExtensions(
                ExtensionPointName.create("dev.ghostflyby.spotless.spotlessFormattingPreprocessor"),
                preprocessors.toList(),
                preprocessorDisposable,
            )
        }

    private fun testHttpClient(
        healthCheck: () -> DaemonResponse,
        format: (query: String) -> DaemonResponse,
        steps: (HttpRequestData) -> DaemonResponse = { DaemonResponse(HttpStatusCode.NotFound) },
        stop: () -> DaemonResponse = { DaemonResponse(HttpStatusCode.OK) },
        onFormat: (HttpRequestData) -> Unit = {},
    ): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val response = when (request.method) {
                    HttpMethod.Get ->
                        when (request.url.encodedPath) {
                            "/" -> healthCheck()
                            "/steps" -> steps(request)
                            else -> DaemonResponse(HttpStatusCode.MethodNotAllowed)
                        }

                    HttpMethod.Post ->
                        when (request.url.encodedPath) {
                            "/" -> {
                                onFormat(request)
                                format(request.url.encodedQuery)
                            }
                            "/stop" -> stop()
                            else -> DaemonResponse(HttpStatusCode.MethodNotAllowed)
                        }

                    else -> DaemonResponse(HttpStatusCode.MethodNotAllowed)
                }
                respond(
                    content = response.body,
                    status = response.status,
                    headers = headersOf("Content-Type", "text/plain"),
                )
            }
        }
    }

    private data class DaemonResponse(
        val status: HttpStatusCode,
        val body: String = "",
    )

    private fun HttpRequestData.bodyText(): String =
        (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            ?: error("Expected a byte-array request body")

    private class TestFormattingPreprocessor(
        private val isApplicable: (PsiFile) -> Boolean = { true },
        private val process: suspend (SpotlessFormattingPreprocessContext) -> SpotlessFormattingPreprocessResult? = {
            null
        },
    ) : SpotlessFormattingPreprocessor {
        var applicabilityCount = 0
            private set
        var processCount = 0
            private set

        override fun isApplicableTo(psiFile: PsiFile): Boolean {
            applicabilityCount++
            return isApplicable(psiFile)
        }

        override suspend fun preprocess(
            context: SpotlessFormattingPreprocessContext,
        ): SpotlessFormattingPreprocessResult? {
            processCount++
            return process(context)
        }
    }

    private data class TestFormattingPreprocessContext(
        override val psiFile: PsiFile,
        override val content: CharSequence,
        override val daemonSteps: List<String>,
    ) : SpotlessFormattingPreprocessContext

    private class TestDaemonProvider(
        private val projectPath: Path,
        val host: SpotlessDaemonEndpoint,
        private val onCleanup: (() -> Unit)? = null,
        private val lifecycleSetup: (SpotlessDaemonLifecycle) -> Unit = {},
        private val startGate: CompletableDeferred<Unit>? = null,
        private val targetResolver: (VirtualFile) -> SpotlessDaemonTarget? = { file ->
            SpotlessDaemonTarget(projectPath, requireNotNull(file.toNioPath()))
        },
    ) : SpotlessDaemonProvider {
        private val startCounter = AtomicInteger()

        val startCount: Int
            get() = startCounter.get()

        val completionCount = AtomicInteger()
        private val lastLifecycle = AtomicReference<SpotlessDaemonLifecycle>()

        fun terminate() {
            lastLifecycle.get()?.requestClose("test daemon process terminated")
        }

        override fun resolveTarget(project: Project, file: VirtualFile): SpotlessDaemonTarget? =
            targetResolver(file)

        override suspend fun startDaemon(context: SpotlessDaemonStartContext): SpotlessDaemonEndpoint {
            startCounter.incrementAndGet()
            lastLifecycle.set(context.lifecycle)
            context.lifecycle.registerCleanup {
                onCleanup?.invoke()
                completionCount.incrementAndGet()
            }
            lifecycleSetup(context.lifecycle)
            startGate?.await()
            return host
        }
    }
}

private fun TestFixture<Project>.spotlessServiceFixture(): TestFixture<TestSpotlessService> =
    testFixture {
        val project = init()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = TestSpotlessService(SpotlessProjectService(project, scope), scope)
        initialized(service) {
            service.close()
        }
    }

private class TestSpotlessService(
    val service: SpotlessProjectService,
    private val scope: CoroutineScope,
) {
    private var closed = false

    fun close() {
        if (closed) {
            return
        }
        closed = true
        scope.cancel()
        service.dispose()
    }
}
