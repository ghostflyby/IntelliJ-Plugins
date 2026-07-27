/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import org.gradle.api.Project
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService
import java.io.Serializable

public class TypesafeConventionsCatalogModelBuilder : ModelBuilderService {

    private val adapter: TypesafeConventionsGradleModelAdapter = DefaultTypesafeConventionsGradleModelAdapter()

    override fun canBuild(modelName: String): Boolean =
        modelName == TypesafeConventionsCatalogModel::class.java.name

    override fun buildAll(modelName: String, project: Project): Any =
        adapter.collect(project).toModel()
}

public interface TypesafeConventionsCatalogModel : Serializable {
    public val enabled: Boolean
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
    override val catalogs: Map<String, String>,
    override val diagnostics: List<TypesafeConventionsCatalogDiagnostic>,
) : TypesafeConventionsCatalogModel {
    override val enabled: Boolean
        get() = status != TypesafeConventionsCatalogModelStatus.DISABLED
}

internal data class TypesafeConventionsGradleModelResult(
    val status: TypesafeConventionsCatalogModelStatus,
    val catalogs: Map<String, String> = emptyMap(),
    val diagnostics: List<TypesafeConventionsCatalogDiagnostic> = emptyList(),
)

internal interface TypesafeConventionsGradleModelAdapter {
    fun collect(project: Project): TypesafeConventionsGradleModelResult
}

internal fun TypesafeConventionsGradleModelResult.toModel(): TypesafeConventionsCatalogModel =
    TypesafeConventionsCatalogModelImpl(status, catalogs, diagnostics)
