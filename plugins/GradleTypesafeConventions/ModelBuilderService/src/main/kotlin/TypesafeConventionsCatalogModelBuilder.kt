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

    override fun buildAll(modelName: String, project: Project): Any =
        collectCatalogModel(project)
}

public interface TypesafeConventionsCatalogModel : Serializable {
    public val status: TypesafeConventionsCatalogModelStatus
    public val catalogs: Map<String, String>
    public val diagnostics: List<TypesafeConventionsCatalogDiagnostic>
}

public enum class TypesafeConventionsCatalogModelStatus {
    DISABLED,
    COMPLETE,
    INCOMPLETE,
}

public data class TypesafeConventionsCatalogDiagnostic(
    public val code: String,
    public val message: String,
    public val catalogName: String? = null,
) : Serializable

internal data class TypesafeConventionsCatalogModelImpl(
    override val status: TypesafeConventionsCatalogModelStatus,
    override val catalogs: Map<String, String> = emptyMap(),
    override val diagnostics: List<TypesafeConventionsCatalogDiagnostic> = emptyList(),
) : TypesafeConventionsCatalogModel

private fun collectCatalogModel(project: Project): TypesafeConventionsCatalogModel {
    val settings = (project.gradle as? GradleInternal)?.settings
        ?: return incomplete(
            message = "Gradle settings are unavailable for ${project.path}",
        )
    if (!settings.pluginManager.hasPlugin(TYPESAFE_CONVENTIONS_PLUGIN_ID)) {
        return TypesafeConventionsCatalogModelImpl(TypesafeConventionsCatalogModelStatus.DISABLED)
    }
    return collectCatalogLocations(settings)
}

private fun collectCatalogLocations(settings: SettingsInternal): TypesafeConventionsCatalogModel {
    val catalogs = linkedMapOf<String, String>()
    val diagnostics = mutableListOf<TypesafeConventionsCatalogDiagnostic>()
    for (builder in settings.dependencyResolutionManagement.versionCatalogs) {
        val catalogBuilder = builder as? DefaultVersionCatalogBuilder
        if (catalogBuilder == null) {
            diagnostics.add(
                diagnostic(
                    code = "unsupported-catalog-builder",
                    message = "Unsupported version catalog builder ${builder.javaClass.name}",
                ),
            )
            continue
        }
        val catalogName = catalogBuilder.name
        try {
            catalogBuilder.build()
        } catch (exception: RuntimeException) {
            diagnostics.add(
                diagnostic(
                    code = "catalog-build-failed",
                    message = exception.diagnosticMessage(),
                    catalogName = catalogName,
                ),
            )
            continue
        }

        val importedCatalog = readGradleField(catalogBuilder, "importedCatalog")
        if (importedCatalog.isFailure) {
            diagnostics.add(importedCatalog.failureDiagnostic("imported-catalog-field-unavailable", catalogName))
            continue
        }
        if (importedCatalog.getOrNull() == null) {
            continue
        }

        val service = extractDependencyResolutionService(catalogBuilder)
        if (service.isFailure) {
            diagnostics.add(service.failureDiagnostic("dependency-resolution-service-unavailable", catalogName))
            continue
        }
        val catalogPath = resolveImportedCatalogFile(service.getOrThrow(), catalogName)
        if (catalogPath.isFailure) {
            diagnostics.add(catalogPath.failureDiagnostic("catalog-resolution-failed", catalogName))
            continue
        }
        catalogs[catalogName] = catalogPath.getOrThrow().absolutePath.replace(File.separatorChar, '/')
    }

    return TypesafeConventionsCatalogModelImpl(
        status = if (diagnostics.isEmpty()) {
            TypesafeConventionsCatalogModelStatus.COMPLETE
        } else {
            TypesafeConventionsCatalogModelStatus.INCOMPLETE
        },
        catalogs = catalogs,
        diagnostics = diagnostics,
    )
}

private fun resolveImportedCatalogFile(
    service: DependencyResolutionServices,
    catalogName: String,
): Result<File> {
    val configurationName = "incomingCatalogFor${catalogName.capitalized()}0"
    val configuration = try {
        service.configurationContainer.getByName(configurationName)
    } catch (exception: UnknownConfigurationException) {
        return failure(
            code = "catalog-configuration-missing",
            message = "Configuration $configurationName is unavailable: ${exception.diagnosticMessage()}",
        )
    }

    val file = try {
        configuration.incoming.artifacts.artifacts
            .asSequence()
            .map(ResolvedArtifactResult::getFile)
            .firstOrNull()
    } catch (exception: RuntimeException) {
        return failure(
            code = "catalog-resolution-failed",
            message = "Configuration $configurationName could not be resolved: ${exception.diagnosticMessage()}",
        )
    } ?: return failure(
        code = "catalog-artifact-missing",
        message = "Configuration $configurationName has no resolved catalog artifact",
    )
    return Result.success(file)
}

private fun extractDependencyResolutionService(
    builder: DefaultVersionCatalogBuilder,
): Result<DependencyResolutionServices> {
    val supplier = readGradleField(builder, "dependencyResolutionServicesSupplier").getOrElse { exception ->
        return failure("dependency-resolution-field-unavailable", exception.diagnosticMessage())
    } ?: return failure(
        code = "dependency-resolution-supplier-missing",
        message = "dependencyResolutionServicesSupplier is null",
    )
    return try {
        val getMethod = supplier.javaClass.getMethod("get").apply { isAccessible = true }
        val service = getMethod.invoke(supplier) as? DependencyResolutionServices
            ?: return failure(
                code = "dependency-resolution-service-invalid",
                message = "dependencyResolutionServicesSupplier returned an unsupported value",
            )
        Result.success(service)
    } catch (exception: Exception) {
        failure(
            code = "dependency-resolution-service-unavailable",
            message = exception.diagnosticMessage(),
        )
    }
}

private fun readGradleField(instance: Any, fieldName: String): Result<Any?> =
    try {
        val field = generateSequence(instance.javaClass as Class<*>?) { it.superclass }
            .firstNotNullOfOrNull { type -> runCatching { type.getDeclaredField(fieldName) }.getOrNull() }
            ?: return Result.failure(NoSuchFieldException(fieldName))
        field.isAccessible = true
        Result.success(field.get(instance))
    } catch (exception: Exception) {
        Result.failure(exception)
    }

private fun incomplete(message: String): TypesafeConventionsCatalogModel =
    TypesafeConventionsCatalogModelImpl(
        status = TypesafeConventionsCatalogModelStatus.INCOMPLETE,
        diagnostics = listOf(diagnostic("settings-unavailable", message)),
    )

private fun <T> failure(code: String, message: String): Result<T> =
    Result.failure(CatalogCollectionException(diagnostic(code, message)))

private fun Result<*>.failureDiagnostic(
    defaultCode: String,
    catalogName: String,
): TypesafeConventionsCatalogDiagnostic {
    val exception = requireNotNull(exceptionOrNull())
    return if (exception is CatalogCollectionException) {
        exception.diagnostic.copy(catalogName = catalogName)
    } else {
        diagnostic(defaultCode, exception.diagnosticMessage(), catalogName)
    }
}

private class CatalogCollectionException(
    val diagnostic: TypesafeConventionsCatalogDiagnostic,
) : RuntimeException(diagnostic.message)

private fun diagnostic(
    code: String,
    message: String,
    catalogName: String? = null,
): TypesafeConventionsCatalogDiagnostic =
    TypesafeConventionsCatalogDiagnostic(code, message, catalogName)

private fun Throwable.diagnosticMessage(): String =
    buildString {
        append(javaClass.name)
        message?.takeIf(String::isNotBlank)?.let {
            append(": ")
            append(it)
        }
        if (this@diagnosticMessage is InvocationTargetException) {
            targetException?.message?.takeIf(String::isNotBlank)?.let {
                append(": ")
                append(it)
            }
        }
    }

private fun String.capitalized(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private const val TYPESAFE_CONVENTIONS_PLUGIN_ID = "dev.panuszewski.typesafe-conventions"
