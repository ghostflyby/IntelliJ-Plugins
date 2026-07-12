/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.UnknownConfigurationException
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.SettingsInternal
import org.gradle.api.internal.artifacts.DependencyResolutionServices
import org.gradle.api.internal.catalog.DefaultVersionCatalogBuilder
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService
import java.io.File
import java.io.Serializable
import java.lang.reflect.InvocationTargetException

public class TypesafeConventionsCatalogModelBuilder : ModelBuilderService {

    override fun canBuild(modelName: String): Boolean =
        modelName == TypesafeConventionsCatalogModel::class.java.name

    override fun buildAll(modelName: String, project: Project): Any {
        val settings = (project.gradle as? GradleInternal)?.settings
            ?: return TypesafeConventionsCatalogModelImpl(enabled = false, catalogs = emptyMap())
        val enabled = settings.pluginManager.hasPlugin(TYPESAFE_CONVENTIONS_PLUGIN_ID)
        if (!enabled) {
            return TypesafeConventionsCatalogModelImpl(enabled = false, catalogs = emptyMap())
        }

        return TypesafeConventionsCatalogModelImpl(
            enabled = true,
            catalogs = collectCatalogLocations(settings),
        )
    }

    private fun collectCatalogLocations(settings: SettingsInternal): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (builder in settings.dependencyResolutionManagement.versionCatalogs) {
            val catalogBuilder = builder as? DefaultVersionCatalogBuilder ?: continue
            catalogBuilder.build()
            val service = extractDependencyResolutionService(catalogBuilder) ?: continue
            val catalogPath = resolveImportedCatalogFile(service, catalogBuilder.name) ?: continue
            result[catalogBuilder.name] = catalogPath.absolutePath.replace(File.separatorChar, '/')
        }
        return result
    }

    private fun resolveImportedCatalogFile(
        service: DependencyResolutionServices,
        catalogName: String,
    ): File? {
        val configurationName = "incomingCatalogFor${catalogName.capitalized()}0"
        val configuration = try {
            service.configurationContainer.getByName(configurationName)
        } catch (_: UnknownConfigurationException) {
            return null
        }

        return configuration.incoming.artifacts.artifacts
            .asSequence()
            .map(ResolvedArtifactResult::getFile)
            .firstOrNull()
    }

    private fun extractDependencyResolutionService(
        builder: DefaultVersionCatalogBuilder,
    ): DependencyResolutionServices? {
        return try {
            val supplierField = DefaultVersionCatalogBuilder::class.java
                .getDeclaredField("dependencyResolutionServicesSupplier")
                .apply { isAccessible = true }
            val supplier = supplierField.get(builder)
            val getMethod = supplier.javaClass.getMethod("get").apply { isAccessible = true }
            getMethod.invoke(supplier) as? DependencyResolutionServices
        } catch (_: NoSuchFieldException) {
            null
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: IllegalAccessException) {
            null
        } catch (_: InvocationTargetException) {
            null
        }
    }

    private fun String.capitalized(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private companion object {
        private const val TYPESAFE_CONVENTIONS_PLUGIN_ID = "dev.panuszewski.typesafe-conventions"
    }
}

public interface TypesafeConventionsCatalogModel : Serializable {
    public val enabled: Boolean
    public val catalogs: Map<String, String>
}

internal data class TypesafeConventionsCatalogModelImpl(
    override val enabled: Boolean,
    override val catalogs: Map<String, String>,
) : TypesafeConventionsCatalogModel
