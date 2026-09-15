/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.*;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings;
import com.intellij.openapi.externalSystem.settings.DelegatingExternalSystemSettingsListener;
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import dev.ghostflyby.mill.MillConstants;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;

@Service(Service.Level.PROJECT)
@State(name = "MillSettings", storages = @Storage(MillConstants.settingsFileName))
public final class MillSettings extends AbstractExternalSystemSettings<
        MillSettings,
        MillProjectSettings,
        MillSettingsListener
        > implements PersistentStateComponent<MillSettings.State> {

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final Topic<MillSettingsListener> TOPIC =
            (Topic<MillSettingsListener>) new Topic("Mill settings changes", MillSettingsListener.class);

    public MillSettings(@NotNull Project project) {
        super(TOPIC, project);
    }

    public static @NotNull MillSettings getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(MillSettings.class);
    }

    @Override
    public void subscribe(
            @NotNull ExternalSystemSettingsListener<MillProjectSettings> listener,
            @NotNull Disposable parentDisposable
    ) {
        MillSettingsListener millListener = listener instanceof MillSettingsListener
                ? (MillSettingsListener) listener
                : new DelegatingMillSettingsListener(listener);
        doSubscribe(millListener, parentDisposable);
    }

    @Override
    protected void copyExtraSettingsFrom(@NotNull MillSettings settings) {
    }

    @Override
    protected void checkSettings(@NotNull MillProjectSettings old, @NotNull MillProjectSettings current) {
    }

    @Override
    public State getState() {
        State state = new State();
        fillState(state);
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        super.loadState(state);
    }

    public static final class State implements AbstractExternalSystemSettings.State<MillProjectSettings> {
        private Set<MillProjectSettings> linkedExternalProjectsSettings = Collections.emptySet();

        @Override
        public Set<MillProjectSettings> getLinkedExternalProjectsSettings() {
            return linkedExternalProjectsSettings;
        }

        @Override
        public void setLinkedExternalProjectsSettings(Set<MillProjectSettings> settings) {
            linkedExternalProjectsSettings = settings;
        }
    }

    private static final class DelegatingMillSettingsListener
            extends DelegatingExternalSystemSettingsListener<MillProjectSettings>
            implements MillSettingsListener {

        private DelegatingMillSettingsListener(@NotNull ExternalSystemSettingsListener<MillProjectSettings> delegate) {
            super(delegate);
        }
    }
}
