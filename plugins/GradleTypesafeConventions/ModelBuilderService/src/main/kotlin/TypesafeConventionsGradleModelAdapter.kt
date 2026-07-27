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
import java.io.File
import java.lang.reflect.InvocationTargetException

internal class DefaultTypesafeConventionsGradleModelAdapter : TypesafeConventionsGradleModelAdapter {

    override fun collect(project: Project): TypesafeConventionsGradleModelResult {
        val settings = (project.gradle as? GradleInternal)?.settings
            ?: return incomplete(
                code = "settings-unavailable",
                message = "Gradle settings are unavailable for ${project.path}",
            )
        if (!settings.pluginManager.hasPlugin(TYPESAFE_CONVENTIONS_PLUGIN_ID)) {
            return TypesafeConventionsGradleModelResult(TypesafeConventionsCatalogModelStatus.DISABLED)
        }
        return collectCatalogLocations(settings)
    }

    private fun collectCatalogLocations(settings: SettingsInternal): TypesafeConventionsGradleModelResult {
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

            val importedCatalog = readTypesafeConventionsGradleField(catalogBuilder, "importedCatalog")
            if (importedCatalog is TypesafeConventionsReflectionRead.Failure) {
                diagnostics.add(importedCatalog.toDiagnostic("imported-catalog-field-unavailable", catalogName))
                continue
            }
            if ((importedCatalog as TypesafeConventionsReflectionRead.Success).value == null) {
                continue
            }

            val service = extractDependencyResolutionService(catalogBuilder)
            if (service is AdapterValue.Failure) {
                diagnostics.add(service.diagnostic.copy(catalogName = catalogName))
                continue
            }
            val catalogPath = resolveImportedCatalogFile((service as AdapterValue.Success).value, catalogName)
            if (catalogPath is AdapterValue.Failure) {
                diagnostics.add(catalogPath.diagnostic.copy(catalogName = catalogName))
                continue
            }
            catalogs[catalogName] = (catalogPath as AdapterValue.Success).value.absolutePath
                .replace(File.separatorChar, '/')
        }

        return TypesafeConventionsGradleModelResult(
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
    ): AdapterValue<File> {
        val configurationName = "incomingCatalogFor${catalogName.capitalized()}0"
        val configuration = try {
            service.configurationContainer.getByName(configurationName)
        } catch (exception: UnknownConfigurationException) {
            return AdapterValue.Failure(
                diagnostic(
                    code = "catalog-configuration-missing",
                    message = "Configuration $configurationName is unavailable: ${exception.diagnosticMessage()}",
                ),
            )
        }

        val file = try {
            configuration.incoming.artifacts.artifacts
                .asSequence()
                .map(ResolvedArtifactResult::getFile)
                .firstOrNull()
        } catch (exception: RuntimeException) {
            return AdapterValue.Failure(
                diagnostic(
                    code = "catalog-resolution-failed",
                    message = "Configuration $configurationName could not be resolved: ${exception.diagnosticMessage()}",
                ),
            )
        }
            ?: return AdapterValue.Failure(
                diagnostic(
                    code = "catalog-artifact-missing",
                    message = "Configuration $configurationName has no resolved catalog artifact",
                ),
            )
        return AdapterValue.Success(file)
    }

    private fun extractDependencyResolutionService(
        builder: DefaultVersionCatalogBuilder,
    ): AdapterValue<DependencyResolutionServices> {
        val supplier = when (
            val field = readTypesafeConventionsGradleField(builder, "dependencyResolutionServicesSupplier")
        ) {
            is TypesafeConventionsReflectionRead.Success -> field.value
            is TypesafeConventionsReflectionRead.Failure -> return AdapterValue.Failure(
                field.toDiagnostic("dependency-resolution-field-unavailable"),
            )
        } ?: return AdapterValue.Failure(
            diagnostic(
                code = "dependency-resolution-supplier-missing",
                message = "dependencyResolutionServicesSupplier is null",
            ),
        )
        return try {
            val getMethod = supplier.javaClass.getMethod("get").apply { isAccessible = true }
            val service = getMethod.invoke(supplier) as? DependencyResolutionServices
                ?: return AdapterValue.Failure(
                    diagnostic(
                        code = "dependency-resolution-service-invalid",
                        message = "dependencyResolutionServicesSupplier returned an unsupported value",
                    ),
                )
            AdapterValue.Success(service)
        } catch (exception: Exception) {
            AdapterValue.Failure(
                diagnostic(
                    code = "dependency-resolution-service-unavailable",
                    message = exception.diagnosticMessage(),
                ),
            )
        }
    }

    private fun incomplete(code: String, message: String): TypesafeConventionsGradleModelResult =
        TypesafeConventionsGradleModelResult(
            status = TypesafeConventionsCatalogModelStatus.INCOMPLETE,
            diagnostics = listOf(diagnostic(code, message)),
        )

    private fun TypesafeConventionsReflectionRead.Failure.toDiagnostic(
        code: String,
        catalogName: String? = null,
    ): TypesafeConventionsCatalogDiagnostic =
        diagnostic(code, exception.diagnosticMessage(), catalogName)

    private sealed interface AdapterValue<out T> {
        data class Success<T>(val value: T) : AdapterValue<T>
        data class Failure(val diagnostic: TypesafeConventionsCatalogDiagnostic) : AdapterValue<Nothing>
    }

    private companion object {
        private const val TYPESAFE_CONVENTIONS_PLUGIN_ID = "dev.panuszewski.typesafe-conventions"
    }
}

internal sealed interface TypesafeConventionsReflectionRead {
    data class Success(val value: Any?) : TypesafeConventionsReflectionRead
    data class Failure(val exception: Exception) : TypesafeConventionsReflectionRead
}

internal fun readTypesafeConventionsGradleField(
    instance: Any,
    fieldName: String,
): TypesafeConventionsReflectionRead =
    try {
        val field = generateSequence(instance.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(fieldName) }.getOrNull() }
            .firstOrNull()
            ?: return TypesafeConventionsReflectionRead.Failure(NoSuchFieldException(fieldName))
        field.isAccessible = true
        TypesafeConventionsReflectionRead.Success(field.get(instance))
    } catch (exception: Exception) {
        TypesafeConventionsReflectionRead.Failure(exception)
    }

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
