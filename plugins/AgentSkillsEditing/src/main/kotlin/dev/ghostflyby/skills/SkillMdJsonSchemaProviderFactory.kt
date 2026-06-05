package dev.ghostflyby.skills

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

internal class SkillMdJsonSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {

    override fun getProviders(project: Project): List<JsonSchemaFileProvider> {
        return listOf(SkillMdJsonSchemaFileProvider())
    }
}

private class SkillMdJsonSchemaFileProvider : JsonSchemaFileProvider {

    override fun getName(): String = SkillMdBundle.message("skill.md.schema.display.name")

    override fun getPresentableName(): String = getName()

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

    override fun isAvailable(file: VirtualFile): Boolean {
        return file.name == "SKILL.md"
    }

    override fun getSchemaFile(): VirtualFile? {
        return JsonSchemaProviderFactory.getResourceFile(
            SkillMdJsonSchemaProviderFactory::class.java,
            "/schemas/skill-md-schema.json",
        )
    }
}
