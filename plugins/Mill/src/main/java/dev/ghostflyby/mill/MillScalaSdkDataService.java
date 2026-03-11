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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mill;

import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.Key;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider;
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.scala.project.ReplClasspath;
import org.jetbrains.plugins.scala.project.ReplClasspath$;
import org.jetbrains.plugins.scala.project.external.ScalaSdkUtils$;
import scala.Option;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class MillScalaSdkDataService extends AbstractProjectDataService<MillScalaSdkData, Module> {
  private static final Logger LOG = Logger.getInstance(MillScalaSdkDataService.class);

  @Override
  public @NotNull Key<MillScalaSdkData> getTargetDataKey() {
    return MillScalaSdkData.key;
  }

  @Override
  public void importData(@NotNull Collection<? extends DataNode<MillScalaSdkData>> toImport,
                         @Nullable ProjectData projectData,
                         @NotNull Project project,
                         @NotNull IdeModifiableModelsProvider modelsProvider) {
    for (DataNode<MillScalaSdkData> dataNode : toImport) {
      Object parentData = dataNode.getParent() == null ? null : dataNode.getParent().getData();
      if (!(parentData instanceof ModuleData moduleData)) {
        continue;
      }

      Module ideModule = modelsProvider.findIdeModule(moduleData);
      if (ideModule == null) {
        continue;
      }

      try {
        MillScalaSdkData data = dataNode.getData();
        LOG.info("[Mill import] Configuring Scala SDK for module " + ideModule.getName() +
                 " version=" + data.getScalaVersion() +
                 " scalacJars=" + data.getScalacClasspath().size() +
                 " scaladocJars=" + data.getScaladocClasspath().size() +
                 " replJars=" + data.getReplClasspath().size());
        ReplClasspath replClasspath = ReplClasspath$.MODULE$.fromPaths(toScalaSeq(data.getReplClasspath()));
        ScalaSdkUtils$.MODULE$.configureScalaSdk(
          ideModule,
          data.getScalaVersion(),
          toScalaSeq(data.getScalacClasspath()),
          toScalaSeq(data.getScaladocClasspath()),
          Option.empty(),
          replClasspath,
          MillConstants.scalaSdkPrefix,
          modelsProvider
        );
        LOG.info("[Mill import] Scala SDK configured for module " + ideModule.getName());
      }
      catch (Throwable t) {
        LOG.warn("Failed to configure Mill Scala SDK for module " + ideModule.getName(), t);
      }
    }
  }

  private static Seq<Path> toScalaSeq(List<String> values) {
    ArrayList<Path> paths = new ArrayList<>(values.size());
    for (String value : values) {
      paths.add(Path.of(value));
    }
    return CollectionConverters.asScala(paths).toSeq();
  }
}
