/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.disposableFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider.ExternalProject
import dev.ghostflyby.spotless.api.frontend.SpotlessDaemonProviderPresentation
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider.Endpoint as SpotlessDaemonEndpoint
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider.Handle as SpotlessDaemonHandle
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider.StartContext as SpotlessDaemonStartContext
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider.State as SpotlessDaemonProviderState
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider.Target as SpotlessDaemonTarget

@Suppress("OverrideOnly")
@TestApplication
internal class SpotlessStatusBarWidgetTest {
    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(
        pathFixture = projectPathFixture,
        openAfterCreation = true,
    )
    private val providerDisposableFixture = disposableFixture()
    private val providerDisposable by providerDisposableFixture

    @Test
    fun `widget is unavailable when providers have not detected Spotless`() {
        maskProviders(TestStatusProvider(emptyList()))

        assertFalse(SpotlessStatusBarWidgetFactory().isAvailable(projectFixture.get()))
    }

    @Test
    suspend fun `widget is available from a non Gradle provider`() {
        val root = projectPathFixture.get().resolve("external-project")
        maskProviders(TestStatusProvider(listOf(root), "Test Build Spotless"))

        val project = projectFixture.get()
        val service = project.service<SpotlessProjectService>()
        service.refreshDaemonProviders()
        withTimeout(5.seconds) {
            while (service.daemonStatus.value.providers.isEmpty()) {
                delay(10.milliseconds)
            }
        }
        val snapshot = service.daemonStatus.value

        assertTrue(SpotlessStatusBarWidgetFactory().isAvailable(project))
        assertEquals(providerId("Test Build Spotless"), snapshot.providers.single().providerId)
        assertEquals(listOf(root.toAbsolutePath().normalize()), snapshot.providers.single().externalProjects)
    }

    @Test
    suspend fun `provider state refreshes widget availability`() {
        val provider = TestStatusProvider(emptyList())
        maskProviders(provider)
        val project = projectFixture.get()
        val service = project.service<SpotlessProjectService>()
        assertTrue(service.daemonStatus.value.providers.isEmpty())

        provider.updateProjects(listOf(projectPathFixture.get().resolve("detected")))

        withTimeout(5.seconds) {
            while (service.daemonStatus.value.providers.size != 1) {
                delay(10.milliseconds)
            }
        }
    }

    @Test
    suspend fun `provider extension removal cancels its state collector`() {
        val project = projectFixture.get()
        val service = project.service<SpotlessProjectService>()
        val provider = TestStatusProvider(listOf(projectPathFixture.get().resolve("detected")))
        val providerDisposable = registerProvider(provider)

        withTimeout(5.seconds) {
            while (!provider.hasSubscriber()) {
                delay(10.milliseconds)
            }
        }
        assertTrue(service.daemonStatus.value.providers.any { it.providerId == provider.id })

        Disposer.dispose(providerDisposable)

        withTimeout(5.seconds) {
            while (provider.hasSubscriber()) {
                delay(10.milliseconds)
            }
        }
        assertFalse(service.daemonStatus.value.providers.any { it.providerId == provider.id })
    }

    @Test
    suspend fun `provider collector cancellation can precede combined status update`() {
        maskProviders()
        // Hold the derived status collector while the provider collector cancels on Dispatchers.Default.
        val dispatcher = QueuedDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
        val provider = TestStatusProvider(listOf(projectPathFixture.get().resolve("detected")))
        val coordinator = SpotlessDaemonCoordinator(projectFixture.get(), coordinatorScope, ::readyClient)

        try {
            coordinator.providersLookup = { listOf(provider) }
            dispatcher.runAll()
            assertTrue(provider.hasSubscriber())
            assertTrue(coordinator.snapshot.value.providers.any { it.providerId == provider.id })

            coordinator.providersLookup = { emptyList() }

            withTimeout(5.seconds) {
                while (provider.hasSubscriber()) {
                    delay(10.milliseconds)
                }
            }
            assertFalse(coordinator.snapshot.value.providers.any { it.providerId == provider.id })
        } finally {
            coordinator.shutdown()
            coordinatorScope.cancel()
            dispatcher.runAll()
        }
    }

    @Test
    suspend fun `restart completion publishes Ready status before returning`() {
        maskProviders()
        val dispatcher = QueuedDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
        val root = projectPathFixture.get().resolve("external-project")
        val provider = TestStatusProvider(listOf(root))
        val readinessStarted = CompletableDeferred<Unit>()
        val allowReadiness = CompletableDeferred<Unit>()
        val client = SpotlessDaemonClient(
            HttpClient(MockEngine {
                readinessStarted.complete(Unit)
                allowReadiness.await()
                respond("", HttpStatusCode.OK)
            }),
        )
        val coordinator = SpotlessDaemonCoordinator(projectFixture.get(), coordinatorScope) { client }
        val normalizedRoot = root.toAbsolutePath().normalize()

        try {
            coordinator.providersLookup = { listOf(provider) }
            dispatcher.runAll()

            val restart = coordinator.restartDaemon(provider.id, root)
            withTimeout(5.seconds) {
                readinessStarted.await()
            }
            dispatcher.runAll()
            assertEquals(
                mapOf(normalizedRoot to SpotlessDaemonRuntimeState.Starting),
                coordinator.snapshot.value.providers.single().runtimeStates,
            )

            allowReadiness.complete(Unit)
            withTimeout(5.seconds) {
                restart.join()
            }

            assertEquals(
                mapOf(normalizedRoot to SpotlessDaemonRuntimeState.Ready),
                coordinator.snapshot.value.providers.single().runtimeStates,
            )
        } finally {
            allowReadiness.complete(Unit)
            coordinator.shutdown()
            client.close()
            coordinatorScope.cancel()
            dispatcher.runAll()
        }
    }

    @Test
    suspend fun `runtime states are grouped by provider identity`() {
        val firstRoot = projectPathFixture.get().resolve("first")
        val secondRoot = projectPathFixture.get().resolve("second")
        val firstProvider = TestStatusProvider(
            listOf(firstRoot),
            presentationName = "First Spotless",
            equalToOtherTestProviders = true,
        )
        val secondProvider = TestStatusProvider(
            listOf(secondRoot),
            presentationName = "Second Spotless",
            equalToOtherTestProviders = true,
        )
        maskProviders(firstProvider, secondProvider)
        val project = projectFixture.get()
        val service = project.service<SpotlessProjectService>()
        service.client = readyClient()

        service.restartDaemon(firstProvider.id, firstRoot).join()

        withTimeout(5.seconds) {
            while (service.daemonStatus.value.providers
                    .single { it.providerId == firstProvider.id }
                    .runtimeStates
                    .isEmpty()
            ) {
                delay(10.milliseconds)
            }
        }

        val firstStatus = service.daemonStatus.value.providers.single { it.providerId == firstProvider.id }
        val secondStatus = service.daemonStatus.value.providers.single { it.providerId == secondProvider.id }
        assertEquals(
            mapOf(firstRoot.toAbsolutePath().normalize() to SpotlessDaemonRuntimeState.Ready),
            firstStatus.runtimeStates,
        )
        assertTrue(secondStatus.runtimeStates.isEmpty())

        firstProvider.requestClose()
        withTimeout(5.seconds) {
            while (service.daemonStatus.value.providers.any { it.runtimeStates.isNotEmpty() }) {
                delay(10.milliseconds)
            }
        }
    }

    @Test
    fun `external project paths omit their common parent`() {
        val root = projectPathFixture.get().toAbsolutePath().normalize()
        val paths = listOf(root.resolve("application"), root.resolve("included/library"))

        assertEquals(
            listOf("application", "included/library"),
            abbreviatedExternalProjectPaths(paths).map(Pair<Path, String>::second),
        )
    }

    @Test
    suspend fun `popup uses provider separators and root inline actions`() {
        val root = projectPathFixture.get().toAbsolutePath().normalize()
        val first = root.resolve("application")
        val second = root.resolve("included/library")
        val provider = TestStatusProvider(listOf(first, second), "Test Build Spotless")
        maskProviders(provider)
        val service = projectFixture.get().service<SpotlessProjectService>()
        service.refreshDaemonProviders()
        withTimeout(5.seconds) {
            while (service.daemonStatus.value.providers.isEmpty()) {
                delay(10.milliseconds)
            }
        }

        val actions = createSpotlessDaemonPopupActionGroup(
            spotlessService = service,
            snapshot = service.daemonStatus.value,
        ).getChildren(null).toList()

        assertEquals("Test Build Spotless", (actions[0] as Separator).text)
        assertEquals(
            listOf("application", "included/library"),
            actions.subList(1, 3).map { it.templatePresentation.text },
        )
        actions.subList(1, 3).forEach { action ->
            assertEquals(2, action.templatePresentation.getClientProperty(ActionUtil.INLINE_ACTIONS)?.size)
        }
        assertTrue(actions[3] is Separator)
        assertEquals(Bundle.message("status.bar.widget.action.refresh"), actions[4].templatePresentation.text)
    }

    @Test
    fun `presentation follows EP order and promotes successor after removal`() {
        val providerId = "dev.ghostflyby.spotless.test.presentation"
        val firstDisposable = registerPresentation(
            TestProviderPresentation(providerId, "First presentation"),
        )
        registerPresentation(TestProviderPresentation(providerId, "Second presentation"))

        assertEquals("First presentation", providerPresentableName(providerId))

        Disposer.dispose(firstDisposable)

        assertEquals("Second presentation", providerPresentableName(providerId))
    }

    @Test
    fun `presentation falls back to provider id for missing blank and throwing names`() {
        val missingId = "dev.ghostflyby.spotless.test.missing.presentation"
        assertEquals(missingId, providerPresentableName(missingId))

        val blankId = "dev.ghostflyby.spotless.test.blank.presentation"
        registerPresentation(TestProviderPresentation(blankId, ""))
        assertEquals(blankId, providerPresentableName(blankId))

        val throwingId = "dev.ghostflyby.spotless.test.throwing.presentation"
        registerPresentation(
            object : SpotlessDaemonProviderPresentation {
                override val providerId: String = throwingId
                override val presentableName: String
                    get() = throw IllegalStateException("presentation failed")
            },
        )
        assertEquals(throwingId, providerPresentableName(throwingId))
    }

    @Test
    suspend fun `stop inline action follows root runtime state`() {
        val root = projectPathFixture.get().resolve("external-project")
        val provider = TestStatusProvider(listOf(root))
        maskProviders(provider)
        val service = projectFixture.get().service<SpotlessProjectService>()
        service.client = readyClient()
        service.refreshDaemonProviders()
        withTimeout(5.seconds) {
            while (service.daemonStatus.value.providers.isEmpty()) {
                delay(10.milliseconds)
            }
        }
        val rootAction = createSpotlessDaemonPopupActionGroup(
            spotlessService = service,
            snapshot = service.daemonStatus.value,
        ).getChildren(null).first { action ->
            action !is Separator && action.templatePresentation.getClientProperty(ActionUtil.INLINE_ACTIONS) != null
        }
        val stopAction = requireNotNull(rootAction.templatePresentation.getClientProperty(ActionUtil.INLINE_ACTIONS))
            .single { it.templatePresentation.text == Bundle.message("status.bar.widget.action.stop.daemon") }

        assertFalse(stopAction.updatedPresentation().isVisible)

        service.restartDaemon(provider.id, root).join()
        withTimeout(5.seconds) {
            while (!stopAction.updatedPresentation().isEnabledAndVisible) {
                delay(10.milliseconds)
            }
        }

        service.releaseDaemon(provider.id, root).join()
        withTimeout(5.seconds) {
            while (stopAction.updatedPresentation().isVisible) {
                delay(10.milliseconds)
            }
        }
    }

    private fun maskProviders(vararg providers: SpotlessDaemonProvider) {
        ExtensionTestUtil.maskExtensions(
            SpotlessDaemonProvider.EP_NAME,
            providers.toList(),
            providerDisposable,
        )
        ExtensionTestUtil.maskExtensions(
            SpotlessDaemonProviderPresentation.EP_NAME,
            providers.filterIsInstance<TestStatusProvider>().map { provider ->
                TestProviderPresentation(provider.id, provider.presentationName)
            },
            providerDisposable,
        )
    }

    private fun registerProvider(provider: SpotlessDaemonProvider): Disposable =
        Disposer.newDisposable("SpotlessStatusBarWidgetTest.provider").also { disposable ->
            Disposer.register(providerDisposable, disposable)
            SpotlessDaemonProvider.EP_NAME.point.registerExtension(provider, disposable)
        }

    private fun registerPresentation(presentation: SpotlessDaemonProviderPresentation): Disposable =
        Disposer.newDisposable("SpotlessStatusBarWidgetTest.presentation").also { disposable ->
            Disposer.register(providerDisposable, disposable)
            SpotlessDaemonProviderPresentation.EP_NAME.point.registerExtension(presentation, disposable)
        }

    private fun AnAction.updatedPresentation() = AnActionEvent.createEvent(
        this,
        DataContext.EMPTY_CONTEXT,
        null,
        ActionPlaces.POPUP,
        ActionUiKind.POPUP,
        null,
    ).also(::update).presentation

    private class TestStatusProvider(
        projects: List<Path>,
        val presentationName: String = "Test Spotless",
        private val equalToOtherTestProviders: Boolean = false,
    ) : SpotlessDaemonProvider {
        override val id: String = providerId(presentationName)
        private var generation = 0L
        private val mutableState = MutableStateFlow(
            providerState(projects, generation),
        )
        private val termination = AtomicReference<CompletableDeferred<Unit>>()

        override fun state(project: Project): StateFlow<SpotlessDaemonProviderState> = mutableState

        override fun resolveTarget(project: Project, file: VirtualFile): SpotlessDaemonTarget? = null

        override suspend fun startDaemon(context: SpotlessDaemonStartContext): SpotlessDaemonHandle {
            val currentTermination = CompletableDeferred<Unit>()
            termination.set(currentTermination)
            currentTermination.invokeOnCompletion {
                termination.compareAndSet(currentTermination, null)
            }
            return SpotlessDaemonHandle(SpotlessDaemonEndpoint.Localhost(25252U), currentTermination)
        }

        fun updateProjects(projects: List<Path>) {
            generation++
            mutableState.value = providerState(projects, generation)
        }

        fun hasSubscriber(): Boolean = mutableState.subscriptionCount.value > 0

        fun requestClose() {
            termination.get()?.complete(Unit)
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                    equalToOtherTestProviders &&
                    other is TestStatusProvider &&
                    other.equalToOtherTestProviders

        override fun hashCode(): Int =
            if (equalToOtherTestProviders) 0 else System.identityHashCode(this)
    }

    private data class TestProviderPresentation(
        override val providerId: String,
        override val presentableName: String,
    ) : SpotlessDaemonProviderPresentation

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }

        fun runAll() {
            while (true) {
                tasks.poll()?.run() ?: return
            }
        }
    }

    private fun readyClient(): SpotlessDaemonClient = SpotlessDaemonClient(
        HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
    )

    private companion object {
        fun providerId(name: String): String =
            "dev.ghostflyby.spotless.test.${name.lowercase().replace(' ', '.')}"

        fun providerState(
            projects: List<Path>,
            generation: Long,
        ): SpotlessDaemonProviderState = SpotlessDaemonProviderState(
            projects.map { root -> ExternalProject(root, generation) },
        )
    }
}
