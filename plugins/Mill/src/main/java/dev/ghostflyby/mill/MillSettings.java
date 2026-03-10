/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mill;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.*;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings;
import com.intellij.openapi.externalSystem.settings.DelegatingExternalSystemSettingsListener;
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
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

    private static final Topic<MillSettingsListener> TOPIC = new Topic<>("Mill settings changes", MillSettingsListener.class);

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
