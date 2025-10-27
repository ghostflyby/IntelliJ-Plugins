/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.template.EverywhereContextType
import com.intellij.codeInsight.template.impl.TemplateContextTypes
import com.intellij.codeInsight.template.impl.TemplateImpl
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.CompletionAutoPopupTestCase
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/*
 * Copyright (c) 2025 ghostflyby <ghostflyby+intellij@outlook.com>
 *
 * This program is free software; you can redistribute it and/or
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

class LiveTemplatesTest : CompletionAutoPopupTestCase() {

    lateinit var template: TemplateImpl
    lateinit var nonSelection: TemplateImpl
    lateinit var variableTemplate: TemplateImpl
    override fun setUp() {
        super.setUp()


        val settings = TemplateSettings.getInstance()

        val context =
            TemplateContextTypes.getByClass(EverywhereContextType::class.java)
        template = TemplateImpl("test", $$"{$SELECTION$}", "test").apply {
            templateContext.setEnabled(context, true)
        }.apply(settings::addTemplate)

        nonSelection = TemplateImpl("nonSelection", "{}", "test").apply {
            templateContext.setEnabled(context, true)
        }.apply(settings::addTemplate)

        variableTemplate = TemplateImpl("variable", $$"{$SELECTION$ $VAR$}", "test").apply {
            addVariable("VAR", "", "", true)
            templateContext.setEnabled(context, true)
        }.apply(settings::addTemplate)

    }

    override fun tearDown() {
        try {
            TemplateSettings.getInstance().removeTemplate(template)
            TemplateSettings.getInstance().removeTemplate(nonSelection)
            TemplateSettings.getInstance().removeTemplate(variableTemplate)
            runInEdtAndWait {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()
            }
//            FileEditorManager.getInstance(project).closeFile()
        } finally {
            super.tearDown()
        }
    }

    fun `test $SELECTION$ replace`() {
        myFixture.configureByText(
            "test.json",
            """
                <selection>"a" : 1</selection>
            """.trimIndent()
        )
        type("test")

        runBlocking(Dispatchers.EDT) {
            myFixture.performEditorAction("InsertLiveTemplate")
            lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }


        myFixture.checkResult(
            """
                {"a" : 1}
            """.trimIndent()
        )
    }

    fun `test $SELECTION$ with no selection`() {
        myFixture.configureByText(
            "test-no-selection.json",
            """
                "a" : 1
            """.trimIndent()
        )
        type("test")

        runBlocking(Dispatchers.EDT) {
            myFixture.performEditorAction("InsertLiveTemplate")
            lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }


        myFixture.checkResult(
            """
                {}"a" : 1
            """.trimIndent()
        )
    }

    fun `test non $SELECTION$`() {
        myFixture.configureByText(
            "non-selection.json",
            """
                <selection>"a" : 1</selection>
            """.trimIndent()
        )

        type("nonSelection")
        runBlocking(Dispatchers.EDT) {
            myFixture.performEditorAction("InsertLiveTemplate")
            lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }

        myFixture.checkResult(
            """
                {}
            """.trimIndent()
        )
    }

    fun `test variable template with $SELECTION$ and $VAR$`() {
        myFixture.configureByText(
            "variable-selection.json",
            """
                <selection>"a" : 1</selection>
            """.trimIndent()
        )

        type("variable")
        runBlocking(Dispatchers.EDT) {
            myFixture.performEditorAction("InsertLiveTemplate")
            lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }

        type("X")
        type("\t")

        myFixture.checkResult(
            """
                {"a" : 1 X}
            """.trimIndent()
        )
    }

}