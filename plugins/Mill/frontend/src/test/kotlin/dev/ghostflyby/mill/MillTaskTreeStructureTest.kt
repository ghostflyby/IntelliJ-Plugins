/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.task.TaskData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

internal class MillTaskTreeStructureTest {

    @Test
    fun `builds dotted task hierarchy from raw task names`() {
        val root = Path.of("/tmp/project")
        val tree = MillTaskTreeStructure.build(
            listOf(
                taskNode(root, "clean"),
                taskNode(root, "foo"),
                taskNode(root, "foo.test"),
                taskNode(root, "foo.test.testForked"),
                taskNode(root, "show foo.compileClasspath"),
            ),
        )

        assertEquals(listOf("clean", "foo", "show foo"), tree.map { it.displayName })
        assertEquals("foo", tree[1].taskNode?.data?.name)
        assertEquals(listOf("test"), tree[1].children.map { it.displayName })
        assertEquals("foo.test", tree[1].children.single().taskNode?.data?.name)
        assertEquals(
            listOf("testForked"),
            tree[1].children.single().children.map { it.displayName },
        )
        assertEquals(
            "show foo.compileClasspath",
            tree[2].children.single().taskNode?.data?.name,
        )
    }

    private fun taskNode(root: Path, name: String): DataNode<TaskData> {
        return DataNode(
            ProjectKeys.TASK,
            TaskData(
                MillConstants.SYSTEM_ID,
                name,
                root.toString(),
                "Task $name",
            ),
            null,
        )
    }
}
