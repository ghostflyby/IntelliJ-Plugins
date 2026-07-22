/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import dev.ghostflyby.spotless.api.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
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
            host = SpotlessDaemonEndpoint.Localhost(25251U),
            targetResolver = { null },
        )
        val selectedProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(skippedProvider, selectedProvider) }
        val virtualFile = createProjectFile("ProviderFallback.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(0, skippedProvider.startCount)
        assertEquals(1, selectedProvider.startCount)
    }

    @Test
    suspend fun `selected provider startup failure does not fall back`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val selectedProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25251U),
            startFailure = IOException("startup failed"),
        )
        val fallbackProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(selectedProvider, fallbackProvider) }
        val virtualFile = createProjectFile("StartupFailure.kt", "content")

        val failure = runCatching { spotless.canFormat(virtualFile) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(1, selectedProvider.startCount)
        assertEquals(1, selectedProvider.completionCount.get())
        assertEquals(0, fallbackProvider.startCount)
        assertFalse(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `provider return before endpoint fails startup without fallback`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val selectedProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25251U),
            returnBeforeEndpoint = true,
        )
        val fallbackProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(selectedProvider, fallbackProvider) }

        val failure = runCatching {
            spotless.canFormat(createProjectFile("ReturnBeforeEndpoint.kt", "content"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, selectedProvider.startCount)
        assertEquals(1, selectedProvider.completionCount.get())
        assertEquals(0, fallbackProvider.startCount)
        assertFalse(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `duplicate endpoint publication fails startup`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
            publishEndpointTwice = true,
        )
        spotless.daemonProvidersLookup = { listOf(provider) }

        val failure = runCatching {
            spotless.canFormat(createProjectFile("DuplicateEndpoint.kt", "content"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, provider.completionCount.get())
        assertFalse(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `invalid provider target continues to the next provider`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val detectedRoot = projectPathFixture.get().resolve("detected")
        val foreignRoot = projectPathFixture.get().resolve("foreign")
        val invalidProvider = TestDaemonProvider(
            projectPath = detectedRoot,
            host = SpotlessDaemonEndpoint.Localhost(25251U),
            targetResolver = { file ->
                SpotlessDaemonTarget(foreignRoot, requireNotNull(file.toNioPath()))
            },
        )
        val selectedProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(invalidProvider, selectedProvider) }
        val virtualFile = createProjectFile("ForeignTarget.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(0, invalidProvider.startCount)
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
                SpotlessFormattingPreprocessor.Result(
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
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252U))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("ImportSteps.java", "class ImportSteps {}")

        assertEquals(
            SpotlessFormatResult.Dirty("formatted-content"),
            spotless.format(virtualFile, "original-content"),
        )
        assertEquals(listOf("ImportSteps.java"), applicableFiles)
        assertEquals(1, preprocessor.processCount)
        assertEquals(listOf(virtualFile.viewProvider.virtualFile.path), stepPaths)
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
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252U))
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
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252U))
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
            process = { SpotlessFormattingPreprocessor.Result("optimized-imports") },
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
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252U))
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
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252U))
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
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252U))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("FailingPreprocessor.java", "class FailingPreprocessor {}")

        assertEquals(SpotlessFormatResult.Clean, spotless.format(virtualFile, "original-content"))
        assertEquals(1, preprocessor.processCount)
        assertEquals(listOf("original-content"), formatBodies)
        assertEquals(listOf(emptyList<String>()), formatSkipSteps)
    }

    @Test
    suspend fun `formatting preprocessor transport failure invalidates daemon`() {
        val preprocessor = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { SpotlessFormattingPreprocessor.Result("optimized-imports") },
        )
        maskPreprocessors(preprocessor)
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                steps = { throw IOException("steps unavailable") },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252U))
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("StepFailure.java", "class StepFailure {}")

        val failure = runCatching { spotless.format(virtualFile, "original-content") }.exceptionOrNull()

        assertTrue(failure is SpotlessDaemonTransportException)
        assertEquals(0, preprocessor.processCount)
        assertEquals(1, provider.completionCount.get())
        assertFalse(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `formatting preprocessors run in extension order and preserve daemon step order`() {
        val inputs = mutableListOf<CharSequence>()
        val first = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { request ->
                inputs += request.content
                SpotlessFormattingPreprocessor.Result("first", setOf("forbidWildcardImports"))
            },
        )
        val second = TestFormattingPreprocessor(
            isApplicable = { true },
            process = { request ->
                inputs += request.content
                SpotlessFormattingPreprocessor.Result("second", setOf("expandWildcardImports", "notConfigured"))
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
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252U))
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
            process = { SpotlessFormattingPreprocessor.Result("processed") },
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
        val provider = TestDaemonProvider(projectPathFixture.get(), SpotlessDaemonEndpoint.Localhost(25252U))
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        val currentProvider = AtomicReference<SpotlessDaemonProvider?>()
        spotless.daemonProvidersLookup = { listOfNotNull(currentProvider.get()) }
        val virtualFile = createProjectFile("RetryableMiss.kt", "content")

        assertFalse(spotless.canFormatSync(virtualFile))
        assertTrue(waitUntil { !spotless.canFormatSync(virtualFile) })
        currentProvider.set(provider)
        spotless.refreshDaemonProviders()
        spotless.canFormatSync(virtualFile)

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })
    }

    @Test
    suspend fun `can format sync cache is scoped per file`() {
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("Revalidate.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })

        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { throw IOException("connection refused") },
            ),
        )

        assertTrue(spotless.canFormatSync(virtualFile))
        assertTrue(
            runCatching {
                spotless.format(
                    virtualFile,
                    "content",
                )
            }.exceptionOrNull() is SpotlessDaemonTransportException,
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
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
        assertTrue(waitUntil { provider.completionCount.get() >= 1 })
        assertEquals(1, provider.completionCount.get())
        assertEquals(1, stopCount.get())
        assertEquals(1, firstCleanupStopCount.get())
        spotless.daemonProvidersLookup = { listOf(provider) }
        assertTrue(spotless.canFormat(virtualFile))
    }

    @Test
    suspend fun `same root provider scoped stop leaves other provider daemon running`() {
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
        val sharedRoot = projectPathFixture.get()
        val firstProvider = TestDaemonProvider(
            projectPath = sharedRoot,
            host = SpotlessDaemonEndpoint.Localhost(25251U),
            targetResolver = { file ->
                file.takeIf { it.name == "FirstProvider.kt" }
                    ?.let { SpotlessDaemonTarget(sharedRoot, requireNotNull(it.toNioPath())) }
            },
        )
        val secondProvider = TestDaemonProvider(
            projectPath = sharedRoot,
            host = SpotlessDaemonEndpoint.Localhost(25252U),
            targetResolver = { file ->
                file.takeIf { it.name == "SecondProvider.kt" }
                    ?.let { SpotlessDaemonTarget(sharedRoot, requireNotNull(it.toNioPath())) }
            },
        )
        spotless.daemonProvidersLookup = { listOf(firstProvider, secondProvider) }

        assertTrue(spotless.canFormat(createProjectFile("FirstProvider.kt", "content")))
        assertTrue(spotless.canFormat(createProjectFile("SecondProvider.kt", "content")))
        assertEquals(2, spotless.daemonStatus.value.providers.sumOf { it.runtimeStates.size })

        spotless.releaseDaemons(firstProvider.id).join()

        assertTrue(waitUntil { firstProvider.completionCount.get() == 1 })
        assertEquals(0, secondProvider.completionCount.get())
        assertEquals(1, stopCount.get())
        assertTrue(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `external project scoped stop leaves sibling daemon running`() {
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
        val firstRoot = projectPathFixture.get().resolve("first-project")
        val secondRoot = projectPathFixture.get().resolve("second-project")
        val provider = TestDaemonProvider(
            projectPath = firstRoot,
            externalProjects = listOf(firstRoot, secondRoot),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        spotless.refreshDaemonProviders()

        spotless.restartDaemon(provider.id, firstRoot).join()
        spotless.restartDaemon(provider.id, secondRoot).join()

        spotless.releaseDaemon(provider.id, firstRoot).join()

        assertEquals(1, provider.completionCount.get())
        assertEquals(1, stopCount.get())
        assertEquals(
            mapOf(secondRoot.toAbsolutePath().normalize() to SpotlessDaemonRuntimeState.Ready),
            spotless.daemonStatus.value.providers.single().runtimeStates,
        )
        assertTrue(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `external project restart replaces its daemon after cleanup`() {
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
        val root = projectPathFixture.get().resolve("restart-project")
        val provider = TestDaemonProvider(
            projectPath = root,
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        spotless.refreshDaemonProviders()
        spotless.restartDaemon(provider.id, root).join()

        spotless.restartDaemon(provider.id, root).join()

        assertEquals(2, provider.startCount)
        assertEquals(1, provider.completionCount.get())
        assertEquals(1, stopCount.get())
        assertTrue(waitUntil { spotless.daemonStatus.value.providers.single().runtimeStates.isNotEmpty() })
        assertEquals(
            mapOf(root.toAbsolutePath().normalize() to SpotlessDaemonRuntimeState.Ready),
            spotless.daemonStatus.value.providers.single().runtimeStates,
        )
    }

    @Test
    suspend fun `provider state change restarts its running daemon`() {
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }

        assertTrue(spotless.canFormat(createProjectFile("ProviderStateChanged.kt", "content")))

        provider.invalidateState()

        assertTrue(waitUntil { provider.startCount == 2 && provider.completionCount.get() == 1 })
        assertEquals(1, stopCount.get())
        assertTrue(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `provider reconcile restarts only changed active root and does not start new root`() {
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
        val firstRoot = projectPathFixture.get().resolve("changed-root")
        val secondRoot = projectPathFixture.get().resolve("unchanged-root")
        val addedRoot = projectPathFixture.get().resolve("added-root")
        val provider = TestDaemonProvider(
            projectPath = firstRoot,
            externalProjects = listOf(firstRoot, secondRoot),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }

        spotless.restartDaemon(provider.id, firstRoot).join()
        spotless.restartDaemon(provider.id, secondRoot).join()

        provider.updateProjectGenerations(
            linkedMapOf(
                firstRoot to 1L,
                secondRoot to 0L,
                addedRoot to 0L,
            ),
        )

        assertTrue(waitUntil { provider.startedRoots.count { it == firstRoot.toAbsolutePath().normalize() } == 2 })
        assertEquals(1, provider.startedRoots.count { it == secondRoot.toAbsolutePath().normalize() })
        assertEquals(0, provider.startedRoots.count { it == addedRoot.toAbsolutePath().normalize() })
        assertEquals(1, provider.completionCount.get())
        assertEquals(1, stopCount.get())
        assertEquals(
            setOf(firstRoot.toAbsolutePath().normalize(), secondRoot.toAbsolutePath().normalize()),
            spotless.daemonStatus.value.providers.single().runtimeStates.keys,
        )
    }

    @Test
    suspend fun `manual provider refresh does not restart a running daemon`() {
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }

        assertTrue(spotless.canFormat(createProjectFile("ManualRefresh.kt", "content")))

        spotless.refreshDaemonProviders()

        assertEquals(1, provider.startCount)
        assertEquals(0, provider.completionCount.get())
        assertEquals(0, stopCount.get())
        assertTrue(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `provider state removes daemon whose external project is no longer valid`() {
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }

        assertTrue(spotless.canFormat(createProjectFile("ProviderRootRemoved.kt", "content")))

        provider.updateExternalProjects(emptyList())

        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(1, provider.startCount)
        assertEquals(1, stopCount.get())
        assertFalse(spotless.hasRunningDaemons())
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
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
    suspend fun `provider failure after readiness detaches without HTTP stop`() {
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("ProviderFailure.kt", "content")
        assertTrue(spotless.canFormat(virtualFile))

        provider.fail(IOException("provider process failed"))

        assertTrue(waitUntil { !spotless.hasRunningDaemons() })
        assertEquals(1, provider.completionCount.get())
        assertEquals(0, stopCount.get())
        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(2, provider.startCount)
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        val newProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25253U),
        )
        val currentProvider = AtomicReference<SpotlessDaemonProvider>(oldProvider)
        spotless.daemonProvidersLookup = { listOf(currentProvider.get()) }
        val virtualFile = createProjectFile("ProviderSwitch.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))

        currentProvider.set(newProvider)
        spotless.refreshDaemonProviders()
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        val providerDisposable = registerProvider(provider, "SpotlessProjectServiceTest.provider")
        val virtualFile = createProjectFile("ProviderRemoved.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))

        Disposer.dispose(providerDisposable)

        assertEquals(1, provider.completionCount.get())
        assertEquals(1, stopCount.get())
        assertFalse(spotless.hasRunningDaemons())
    }

    @Test
    suspend fun `duplicate provider id uses first extension and promotes successor after removal`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val providerId = "dev.ghostflyby.spotless.test.duplicate"
        val root = projectPathFixture.get().toAbsolutePath().normalize()
        val firstProvider = TestDaemonProvider(
            projectPath = root,
            host = SpotlessDaemonEndpoint.Localhost(25251U),
            id = providerId,
        )
        val secondProvider = TestDaemonProvider(
            projectPath = root,
            host = SpotlessDaemonEndpoint.Localhost(25252U),
            id = providerId,
        )
        val firstDisposable = registerProvider(firstProvider, "SpotlessProjectServiceTest.firstDuplicate")
        registerProvider(secondProvider, "SpotlessProjectServiceTest.secondDuplicate")
        val virtualFile = createProjectFile("DuplicateProviderId.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(1, firstProvider.startCount)
        assertEquals(0, secondProvider.startCount)

        Disposer.dispose(firstDisposable)

        assertTrue(waitUntil { firstProvider.completionCount.get() == 1 })
        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(1, secondProvider.startCount)
        assertEquals(providerId, spotless.daemonStatus.value.providers.single().providerId)
    }

    @Test
    suspend fun `provider finally cleanup runs exactly once even when it fails`() {
        val cleanupOrder = Collections.synchronizedList(mutableListOf<String>())
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
            onCleanup = {
                cleanupOrder.add("provider-finally")
                throw IOException("cleanup failure")
            },
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("ProviderFinallyCleanup.kt", "content")

        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(1, spotless.releaseAllDaemons())
        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(listOf("provider-finally"), cleanupOrder)
        assertEquals(0, spotless.releaseAllDaemons())
        assertEquals(1, provider.completionCount.get())
    }

    @Test
    suspend fun `core stop completes HTTP stop before provider finally`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    order.add("http-stop")
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
            onCleanup = { order.add("provider-finally") },
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        assertTrue(spotless.canFormat(createProjectFile("CoreStopOrder.kt", "content")))

        assertEquals(1, spotless.releaseAllDaemons())

        assertEquals(listOf("http-stop", "provider-finally"), order)
        assertEquals(1, provider.completionCount.get())
    }

    @Test
    suspend fun `request cancellation does not cancel shared starting daemon`() = supervisorScope {
        val startGate = CompletableDeferred<Unit>()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
            startGate = startGate,
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val virtualFile = createProjectFile("ConcurrentStart.kt", "content")

        val first = async(Dispatchers.Default) { spotless.canFormat(virtualFile) }
        assertTrue(waitUntil { provider.startCount == 1 })
        val second = async(Dispatchers.Default) { spotless.canFormat(virtualFile) }
        delay(100.milliseconds)
        assertEquals(1, provider.startCount)

        first.cancelAndJoin()
        startGate.complete(Unit)
        assertTrue(second.await())
        assertEquals(1, provider.startCount)
    }

    @Test
    suspend fun `core detach during readiness stops published endpoint`() = supervisorScope {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.ServiceUnavailable) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val formatting = async(Dispatchers.Default) {
            spotless.canFormat(createProjectFile("DetachDuringReadiness.kt", "content"))
        }
        assertTrue(waitUntil { provider.publishedCount.get() == 1 })

        assertEquals(1, spotless.releaseAllDaemons())

        assertEquals(1, stopCount.get())
        assertEquals(1, provider.completionCount.get())
        assertTrue(waitUntil { formatting.isCompleted })
        assertTrue(runCatching { formatting.await() }.isFailure)
    }

    @Test
    suspend fun `provider return during readiness does not send HTTP stop`() = supervisorScope {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.ServiceUnavailable) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(provider) }
        val formatting = async(Dispatchers.Default) {
            spotless.canFormat(createProjectFile("ProviderReturnDuringReadiness.kt", "content"))
        }
        assertTrue(waitUntil { provider.publishedCount.get() == 1 })

        provider.terminate()

        assertTrue(waitUntil { formatting.isCompleted })
        assertTrue(runCatching { formatting.await() }.isFailure)
        assertEquals(0, stopCount.get())
        assertEquals(1, provider.completionCount.get())
        assertFalse(spotless.hasRunningDaemons())
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
            startGate = startGate,
        )
        val providerDisposable = registerProvider(provider, "SpotlessProjectServiceTest.startingProvider")
        val virtualFile = createProjectFile("ProviderRemovedDuringStart.kt", "content")

        val formatting = async(Dispatchers.Default) { spotless.canFormat(virtualFile) }
        assertTrue(waitUntil { provider.startCount == 1 })

        Disposer.dispose(providerDisposable)

        assertEquals(1, provider.completionCount.get())
        assertFalse(spotless.hasRunningDaemons())
        assertTrue(waitUntil { formatting.isCompleted })
        assertTrue(runCatching { formatting.await() }.isFailure)
        assertEquals(0, stopCount.get())
    }

    @Test
    suspend fun `provider removal deadline bounds cleanup and startup join`() = supervisorScope {
        val root = projectPathFixture.get().toAbsolutePath().normalize()
        val startGate = CompletableDeferred<Unit>()
        val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = root,
            host = SpotlessDaemonEndpoint.Localhost(25252U),
            onCleanup = { Thread.sleep(500) },
            startGate = startGate,
            startCancellationDelay = 500.milliseconds,
        )
        val session = ProviderSession(
            id = provider.id,
            provider = provider,
            scope = providerScope,
            initialSnapshot = ProviderSnapshot(mapOf(root to 0L)),
        )
        val registry = SpotlessDaemonRegistry(
            project = projectFixture.get(),
            projectScope = this,
            clientProvider = { client },
            providerRemovalCleanupTimeout = 100.milliseconds,
        )

        try {
            val startup = async(Dispatchers.Default) {
                registry.withDaemon(session, root, generation = 0L) {}
            }
            assertTrue(waitUntil { provider.startCount == 1 })
            providerScope.cancel()

            val elapsed = measureTime {
                assertEquals(1, registry.releaseSessionSynchronously(session))
            }

            assertTrue(elapsed < 1.seconds, "Provider removal exceeded its shared deadline: $elapsed")
            assertFalse(registry.hasRunningDaemons())
            assertEquals(0, provider.completionCount.get())
            assertTrue(startup.isCompleted)
            assertTrue(waitUntil { provider.completionCount.get() == 1 })
            withTimeout(2.seconds) {
                startup.join()
            }
        } finally {
            providerScope.cancel()
            registry.dispose()
            client.close()
        }
    }

    @Test
    suspend fun `old execution completion cannot detach replacement`() = supervisorScope {
        val root = projectPathFixture.get().toAbsolutePath().normalize()
        val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = root,
            host = SpotlessDaemonEndpoint.Localhost(25252U),
            startCancellationDelay = 500.milliseconds,
        )
        val session = ProviderSession(
            id = provider.id,
            provider = provider,
            scope = providerScope,
            initialSnapshot = ProviderSnapshot(mapOf(root to 0L)),
        )
        val registry = SpotlessDaemonRegistry(
            project = projectFixture.get(),
            projectScope = this,
            clientProvider = { client },
            daemonCleanupTimeout = 100.milliseconds,
        )

        try {
            registry.withDaemon(session, root, generation = 0L) {}

            registry.restartDaemon(session, root, generation = 0L)

            assertEquals(2, provider.startCount)
            assertTrue(registry.hasRunningDaemons())
            assertTrue(waitUntil { provider.completionCount.get() == 1 })
            assertTrue(registry.hasRunningDaemons())
        } finally {
            providerScope.cancel()
            registry.dispose()
            client.close()
        }
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
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
    suspend fun `transport failure invalidates selected daemon without provider fallback`() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { throw IOException("connection refused") },
            ),
        )
        val selectedProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25251U),
        )
        val fallbackProvider = TestDaemonProvider(
            projectPath = projectPathFixture.get(),
            host = SpotlessDaemonEndpoint.Localhost(25252U),
        )
        spotless.daemonProvidersLookup = { listOf(selectedProvider, fallbackProvider) }
        val virtualFile = createProjectFile("TransportFailure.kt", "content")

        val failure = runCatching { spotless.format(virtualFile, "content") }.exceptionOrNull()

        assertTrue(failure is SpotlessDaemonTransportException)
        assertEquals(1, selectedProvider.startCount)
        assertEquals(1, selectedProvider.completionCount.get())
        assertEquals(0, fallbackProvider.startCount)
        assertFalse(spotless.hasRunningDaemons())

        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        assertTrue(spotless.canFormat(virtualFile))
        assertEquals(2, selectedProvider.startCount)
        assertEquals(0, fallbackProvider.startCount)
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
            host = SpotlessDaemonEndpoint.Localhost(25252U),
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
        SpotlessDaemonProvider.EP_NAME.point
            .registerExtension(provider, providerDisposable)
    }

    private fun maskPreprocessors(vararg preprocessors: SpotlessFormattingPreprocessor): Disposable =
        Disposer.newDisposable("test formatting preprocessors").also { preprocessorDisposable ->
            Disposer.register(preprocessorParentDisposable, preprocessorDisposable)
            ExtensionTestUtil.maskExtensions(
                SpotlessFormattingPreprocessor.EP_NAME,
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
        private val process: suspend (SpotlessFormattingPreprocessor.Context) -> SpotlessFormattingPreprocessor.Result? = {
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
            context: SpotlessFormattingPreprocessor.Context,
        ): SpotlessFormattingPreprocessor.Result? {
            processCount++
            return process(context)
        }
    }

    private data class TestFormattingPreprocessContext(
        override val psiFile: PsiFile,
        override val content: CharSequence,
        override val daemonSteps: List<String>,
    ) : SpotlessFormattingPreprocessor.Context

    private class TestDaemonProvider(
        private val projectPath: Path,
        val host: SpotlessDaemonEndpoint,
        override val id: String = nextProviderId(),
        externalProjects: List<Path> = listOf(projectPath),
        private val onCleanup: (() -> Unit)? = null,
        private val startGate: CompletableDeferred<Unit>? = null,
        private val startCancellationDelay: Duration? = null,
        private val startFailure: Throwable? = null,
        private val returnBeforeEndpoint: Boolean = false,
        private val publishEndpointTwice: Boolean = false,
        private val targetResolver: (VirtualFile) -> SpotlessDaemonTarget? = { file ->
            SpotlessDaemonTarget(projectPath, requireNotNull(file.toNioPath()))
        },
    ) : SpotlessDaemonProvider {
        private val startCounter = AtomicInteger()
        private var generation = 0L
        private val mutableState = MutableStateFlow(
            providerState(externalProjects, generation),
        )

        val startCount: Int
            get() = startCounter.get()

        val completionCount = AtomicInteger()
        val publishedCount = AtomicInteger()
        val startedRoots: MutableList<Path> = Collections.synchronizedList(mutableListOf())
        private val terminations = ConcurrentHashMap<Path, CompletableDeferred<Throwable?>>()

        fun terminate() {
            terminations.values.forEach { termination -> termination.complete(null) }
        }

        fun fail(error: Throwable) {
            terminations.values.forEach { termination -> termination.complete(error) }
        }

        override fun state(project: Project): StateFlow<SpotlessDaemonProviderState> = mutableState

        override fun resolveTarget(project: Project, file: VirtualFile): SpotlessDaemonTarget? =
            targetResolver(file)

        override suspend fun runDaemon(context: SpotlessDaemonRunContext) {
            startCounter.incrementAndGet()
            val root = context.externalProjectRoot.toAbsolutePath().normalize()
            val termination = CompletableDeferred<Throwable?>()
            startedRoots.add(root)
            terminations[root] = termination
            try {
                startGate?.await()
                startFailure?.let { throw it }
                if (returnBeforeEndpoint) {
                    return
                }
                context.publishEndpoint(host)
                if (publishEndpointTwice) {
                    context.publishEndpoint(host)
                }
                publishedCount.incrementAndGet()
                termination.await()?.let { failure -> throw failure }
            } catch (error: CancellationException) {
                startCancellationDelay?.let { delay ->
                    withContext(NonCancellable) {
                        delay(delay)
                    }
                }
                throw error
            } finally {
                terminations.remove(root, termination)
                try {
                    onCleanup?.invoke()
                } finally {
                    completionCount.incrementAndGet()
                }
            }
        }

        fun invalidateState() {
            generation++
            mutableState.value = providerState(
                mutableState.value.projects.map(ExternalProject::root),
                generation,
            )
        }

        fun updateExternalProjects(externalProjects: List<Path>) {
            generation++
            mutableState.value = providerState(externalProjects, generation)
        }

        fun updateProjectGenerations(projects: Map<Path, Long>) {
            mutableState.value = SpotlessDaemonProviderState(
                projects.entries.map { (root, projectGeneration) ->
                    ExternalProject(root, projectGeneration)
                },
            )
        }
    }

    private companion object {
        private val providerIdCounter = AtomicInteger()

        fun nextProviderId(): String =
            "dev.ghostflyby.spotless.test.provider.p${providerIdCounter.incrementAndGet()}"

        fun providerState(
            externalProjects: List<Path>,
            generation: Long,
        ): SpotlessDaemonProviderState = SpotlessDaemonProviderState(
            externalProjects.map { root -> ExternalProject(root, generation) },
        )
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
        service.dispose()
        scope.cancel()
    }
}
