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

import com.intellij.execution.configurations.SimpleJavaParameters;
import com.intellij.openapi.externalSystem.ExternalSystemAutoImportAware;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.ExternalSystemUiAware;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver;
import com.intellij.openapi.externalSystem.service.ui.DefaultExternalSystemUiAware;
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.util.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.util.List;

public final class MillExternalSystemManager implements ExternalSystemManager<
        MillProjectSettings,
        MillSettingsListener,
        MillSettings,
        MillLocalSettings,
        MillExecutionSettings
        >, ExternalSystemUiAware, ExternalSystemAutoImportAware {

    @Override
    public @NotNull ProjectSystemId getSystemId() {
        return MillConstants.systemId;
    }

    @Override
    public @NotNull Function<Project, MillSettings> getSettingsProvider() {
        return MillSettings::getInstance;
    }

    @Override
    public @NotNull Function<Project, MillLocalSettings> getLocalSettingsProvider() {
        return MillLocalSettings::getInstance;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public @NotNull Function getExecutionSettingsProvider() {
        return (Function<Pair<Project, String>, MillExecutionSettings>) pair -> {
            MillProjectSettings linkedSettings = MillSettings.getInstance(pair.first).getLinkedProjectSettings(pair.second);
            MillExecutionSettings settings = new MillExecutionSettings();
            settings.setMillExecutablePath(linkedSettings == null ? MillConstants.defaultExecutable : linkedSettings.getMillExecutablePath());
            return settings;
        };
    }

    @Override
    public @NotNull Class<? extends ExternalSystemProjectResolver<MillExecutionSettings>> getProjectResolverClass() {
        return MillProjectResolver.class;
    }

    @Override
    public @NotNull Class<? extends ExternalSystemTaskManager<MillExecutionSettings>> getTaskManagerClass() {
        return MillTaskManager.class;
    }

    @Override
    public @NotNull FileChooserDescriptor getExternalProjectDescriptor() {
        return FileChooserDescriptorFactory.singleFileOrDir();
    }

    @Override
    public @NotNull String getProjectRepresentationName(@NotNull String targetProjectPath, @Nullable String rootProjectPath) {
        return MillProjectResolverSupport.presentableProjectName(targetProjectPath, rootProjectPath);
    }

    @Override
    public @NotNull FileChooserDescriptor getExternalProjectConfigDescriptor() {
        return FileChooserDescriptorFactory.singleFileOrDir();
    }

    @Override
    public @NotNull Icon getProjectIcon() {
        return DefaultExternalSystemUiAware.INSTANCE.getProjectIcon();
    }

    @Override
    public @NotNull Icon getTaskIcon() {
        return DefaultExternalSystemUiAware.INSTANCE.getTaskIcon();
    }

    @Override
    public @Nullable String getAffectedExternalProjectPath(@NotNull String changedFileOrDirPath, @NotNull Project project) {
        List<String> linkedProjectPaths = MillSettings.getInstance(project)
                .getLinkedProjectsSettings()
                .stream()
                .map(MillProjectSettings::getExternalProjectPath)
                .toList();
        return MillProjectResolverSupport.findAffectedExternalProjectPath(changedFileOrDirPath, linkedProjectPaths);
    }

    @Override
    public @NotNull List<File> getAffectedExternalProjectFiles(String projectPath, @NotNull Project project) {
        return MillProjectResolverSupport.findAffectedExternalProjectFiles(projectPath);
    }

    @Override
    public void enhanceRemoteProcessing(@NotNull SimpleJavaParameters parameters) {
    }
}
