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

import com.intellij.openapi.components.*;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemLocalSettings;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
@State(name = "MillLocalSettings", storages = @Storage(StoragePathMacros.CACHE_FILE))
public final class MillLocalSettings
        extends AbstractExternalSystemLocalSettings<MillLocalSettings.State>
        implements PersistentStateComponent<MillLocalSettings.State> {

    public MillLocalSettings(@NotNull Project project) {
        super(MillConstants.systemId, project, new State());
    }

    public static @NotNull MillLocalSettings getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(MillLocalSettings.class);
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        super.loadState(state);
    }

    public static final class State extends AbstractExternalSystemLocalSettings.State {
    }
}
