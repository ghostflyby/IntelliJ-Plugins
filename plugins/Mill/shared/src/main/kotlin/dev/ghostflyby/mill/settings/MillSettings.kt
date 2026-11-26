/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings
import com.intellij.openapi.externalSystem.settings.DelegatingExternalSystemSettingsListener
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.ghostflyby.mill.MillConstants

@Service(Service.Level.PROJECT)
@State(name = "MillSettings", storages = [Storage(MillConstants.SETTINGS_FILE_NAME)])
public class MillSettings(
    project: Project,
) : AbstractExternalSystemSettings<MillSettings, MillProjectSettings, MillSettingsListener>(TOPIC, project),
    PersistentStateComponent<MillSettings.State> {

    public override fun subscribe(
        listener: ExternalSystemSettingsListener<MillProjectSettings>,
        parentDisposable: Disposable,
    ) {
        val millListener = listener as? MillSettingsListener ?: DelegatingMillSettingsListener(listener)
        doSubscribe(millListener, parentDisposable)
    }

    protected override fun copyExtraSettingsFrom(settings: MillSettings): Unit = Unit

    protected override fun checkSettings(old: MillProjectSettings, current: MillProjectSettings): Unit = Unit

    public override fun getState(): State = State().also(::fillState)

    public override fun loadState(state: State) {
        super.loadState(state)
    }

    public class State : AbstractExternalSystemSettings.State<MillProjectSettings> {
        private var linkedExternalProjectsSettings: Set<MillProjectSettings> = emptySet()

        override fun getLinkedExternalProjectsSettings(): Set<MillProjectSettings> = linkedExternalProjectsSettings

        override fun setLinkedExternalProjectsSettings(settings: Set<MillProjectSettings>) {
            linkedExternalProjectsSettings = settings
        }
    }

    private class DelegatingMillSettingsListener(
        delegate: ExternalSystemSettingsListener<MillProjectSettings>,
    ) : DelegatingExternalSystemSettingsListener<MillProjectSettings>(delegate), MillSettingsListener

    public companion object {
        private val TOPIC = Topic("Mill settings changes", MillSettingsListener::class.java)

        @JvmStatic
        public fun getInstance(project: Project): MillSettings = project.service()
    }
}
