package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtCallExpression

class NamedArgumentStubsIntentionTest : BasePlatformTestCase() {

    fun testTextAndFamilyName() {
        val intention = NamedArgumentStubsIntention()
        assertEquals("Add named argument stubs", intention.text)
        assertEquals("Add named argument stubs for all parameters", intention.familyName)
    }

    fun testIsAvailableForCallWithPositionalArguments() {
        assertAvailable("<caret>target(1, \"2\")", expected = true)
    }

    fun testIsAvailableWhenCaretIsInsideCalleeName() {
        assertAvailable("tar<caret>get(1, \"2\")", expected = true)
    }

    fun testIsAvailableForCallWithMissingArguments() {
        assertAvailable("<caret>target()", expected = true)
    }

    fun testIsNotAvailableWhenEveryArgumentIsNamed() {
        assertAvailable("<caret>target(id = 1, name = \"2\", value = 3.0)", expected = false)
    }

    fun testIsNotAvailableForCallWithoutParameters() {
        assertAvailable(
            usage = "<caret>nothing()",
            declarations = "fun nothing(): Boolean = true",
            expected = false,
        )
    }

    // The intention reacts only on the callee name: a caret inside an argument has no
    // KtNameReferenceExpression among its ancestors.
    fun testIsNotAvailableWhenCaretIsInsideArgument() {
        assertAvailable("target(1, \"<caret>2\")", expected = false)
    }

    fun testIsNotAvailableForUnresolvedReference() {
        assertAvailable("<caret>unknown(1, \"2\")", expected = false)
    }

    fun testIsNotAvailableOutsideOfACall() {
        assertAvailable(
            usage = "true",
            declarations = "fun <caret>target(id: Int, name: String, value: Double = 0.0): Boolean = true",
            expected = false,
        )
    }

    fun testExpandsPositionalArgumentsAndFillsDefaults() {
        assertExpanded(
            "<caret>target(1, \"2\")",
            """
            target(
                id = 1,
                name = "2",
                value = 0.0,
            )
            """.normalize(),
        )
    }

    fun testGeneratesStubForArgumentWithoutValue() {
        assertExpanded(
            "<caret>target()",
            """
            target(
                id = id,
                name = name,
                value = 0.0,
            )
            """.normalize(),
        )
    }

    fun testKeepsNamedArgumentAndStubsTheMissingOne() {
        assertExpanded(
            "<caret>target(id = 1)",
            """
            target(
                id = 1,
                name = name,
                value = 0.0,
            )
            """.normalize(),
        )
    }

    fun testExpandsConstructorArguments() {
        assertExpanded(
            usage = "<caret>Holder(1, \"2\")",
            declarations = "class Holder(val id: Int, val name: String, val active: Boolean = false)",
            expected = """
                Holder(
                    id = 1,
                    name = "2",
                    active = false,
                )
            """.normalize(),
        )
    }

    fun testExpandsMemberCallArguments() {
        assertExpanded(
            usage = "Service().<caret>target(1, \"2\")",
            declarations = """
                class Service {
                    fun target(id: Int, name: String, value: Double = 0.0): Boolean = true
                }
            """.trimIndent(),
            expected = """
                target(
                    id = 1,
                    name = "2",
                    value = 0.0,
                )
            """.normalize(),
        )
    }

    fun testKeepsComplexArgumentExpressions() {
        assertExpanded(
            usage = "<caret>target(sum(1, 2), \"2\".uppercase())",
            declarations = """
                fun sum(first: Int, second: Int): Int = first + second
                fun target(id: Int, name: String, value: Double = 0.0): Boolean = true
            """.trimIndent(),
            expected = """
                target(
                    id = sum(1, 2),
                    name = "2".uppercase(),
                    value = 0.0,
                )
            """.normalize(),
        )
    }

    fun testCountsOnlyPositionalArgumentsWhenNamedOnesFollow() {
        assertExpanded(
            usage = "<caret>target(1, value = 3.0)",
            expected = """
                target(
                    id = 1,
                    name = name,
                    value = 3.0,
                )
            """.normalize(),
        )
    }

    private fun source(usage: String, declarations: String) = """
        $declarations

        val usage = $usage
    """.trimIndent()

    private fun configure(usage: String, declarations: String = DEFAULT_DECLARATIONS) {
        myFixture.configureByText("Test.kt", source(usage, declarations))
    }

    private fun elementAtCaret(): PsiElement =
        myFixture.file.findElementAt(myFixture.caretOffset) ?: error("No element at the caret")

    private fun assertAvailable(usage: String, declarations: String = DEFAULT_DECLARATIONS, expected: Boolean) {
        configure(usage, declarations)
        val available = NamedArgumentStubsIntention().isAvailable(project, myFixture.editor, elementAtCaret())
        assertEquals("Availability of the intention for `$usage`", expected, available)
    }

    private fun assertExpanded(
        usage: String,
        expected: String,
        declarations: String = DEFAULT_DECLARATIONS,
    ) {
        configure(usage, declarations)
        val intention = NamedArgumentStubsIntention()
        val element = elementAtCaret()
        val callee = PsiTreeUtil.getParentOfType(element, KtCallExpression::class.java)?.calleeExpression?.text
            ?: error("There is no call expression around the caret")
        assertTrue("The intention should be available for `$usage`", intention.isAvailable(project, myFixture.editor, element))

        WriteCommandAction.runWriteCommandAction(project) {
            intention.invoke(project, myFixture.editor, element)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(expected, callText(callee))
    }

    private fun callText(callee: String): String =
        PsiTreeUtil.findChildrenOfType(myFixture.file, KtCallExpression::class.java)
            .first { it.calleeExpression?.text == callee }
            .text.normalize()

    private fun String.normalize(): String = replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val DEFAULT_DECLARATIONS =
            "fun target(id: Int, name: String, value: Double = 0.0): Boolean = true"
    }
}
