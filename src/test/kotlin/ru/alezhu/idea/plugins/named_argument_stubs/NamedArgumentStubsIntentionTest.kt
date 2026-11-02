package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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

    // A name that is not being called has no argument list of its own, so the intention stays out
    // even where the name resolves to a declaration that has parameters.
    fun testIsNotAvailableForACallableReference() {
        assertAvailable("::tar<caret>get", expected = false)
    }

    fun testIsNotAvailableForANamePassedAsAnArgument() {
        assertAvailable("Pair(1, tar<caret>get)", expected = false)
    }

    // The callee of a constructor call is a name reference directly inside the call expression,
    // not a type reference, so it passes the same position check a function call does.
    fun testIsAvailableForACalleeThatIsAConstructor() {
        assertAvailable(
            usage = "Hol<caret>der(1, \"2\")",
            declarations = "class Holder(val id: Int, val name: String, val active: Boolean = false)",
            expected = true,
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

    fun testPicksTheOverloadReachedByPositionalArguments() {
        assertExpanded(
            usage = "<caret>target(1, \"2\")",
            declarations = """
                fun target(id: Int, name: String, value: Double = 0.0): Boolean = true
                fun target(id: Int, title: String): Boolean = false
            """.trimIndent(),
            expected = """
                target(
                    id = 1,
                    title = "2",
                )
            """.normalize(),
        )
    }

    fun testPicksTheOverloadWhoseExtraParametersAreNamed() {
        assertExpanded(
            usage = "<caret>target(1, title = \"2\")",
            declarations = """
                fun target(id: Int, name: String, value: Double = 0.0): Boolean = true
                fun target(id: Int, title: String): Boolean = false
            """.trimIndent(),
            expected = """
                target(
                    id = 1,
                    title = "2",
                )
            """.normalize(),
        )
    }

    fun testOffersEveryOverloadTheCallMatchesEqually() {
        val chooser = RecordingChooser { it.first() }
        val expanded = invokeOn("<caret>target(1, \"2\")", AMBIGUOUS_DECLARATIONS, chooser)

        assertEquals(
            listOf("target(first: Int, second: String)", "target(id: Int, name: String)"),
            chooser.offered.orEmpty().map { it.signature },
        )
        assertEquals(
            """
            target(
                first = 1,
                second = "2",
            )
            """.normalize(),
            expanded,
        )
    }

    fun testExpandsWithTheOverloadChosenInThePopup() {
        val expanded = invokeOn("<caret>target(1, \"2\")", AMBIGUOUS_DECLARATIONS, RecordingChooser { it.last() })

        assertEquals(
            """
            target(
                id = 1,
                name = "2",
            )
            """.normalize(),
            expanded,
        )
    }

    fun testLeavesTheCallUntouchedWhenThePopupIsCancelled() {
        val expanded = invokeOn("<caret>target(1, \"2\")", AMBIGUOUS_DECLARATIONS, RecordingChooser { null })

        assertEquals("target(1, \"2\")", expanded)
    }

    fun testDoesNotOfferAChoiceWhenASingleOverloadFits() {
        val chooser = RecordingChooser { it.first() }
        assertExpanded(
            usage = "<caret>target(1, \"2\")",
            declarations = """
                fun target(id: Int, name: String, value: Double = 0.0): Boolean = true
                fun target(id: Int, title: String): Boolean = false
            """.trimIndent(),
            expected = """
                target(
                    id = 1,
                    title = "2",
                )
            """.normalize(),
            chooser = chooser,
        )
        assertNull("No choice offered for an unambiguous call", chooser.offered)
    }

    // An argument name that exists in neither overload leaves the frontend with one
    // closest candidate; the intention follows it instead of refusing outright.
    fun testExpandsToTheCandidateLeftWhenNoOverloadMatchesTheNames() {
        assertExpanded(
            usage = "<caret>target(1, unknown = \"2\")",
            declarations = """
                fun target(id: Int, name: String, value: Double = 0.0): Boolean = true
                fun target(id: Int, title: String): Boolean = false
            """.trimIndent(),
            expected = """
                target(
                    id = 1,
                    title = title,
                )
            """.normalize(),
        )
    }

    // An empty call with two fully applicable overloads: the frontend narrows to the
    // candidate that needs no default arguments, so the stubs come from that signature.
    fun testExpandsEmptyCallToTheOverloadThatNeedsNoDefaults() {
        assertExpanded(
            usage = "<caret>target()",
            declarations = """
                fun target(id: Int = 1, name: String = "a", value: Double = 0.0): Boolean = true
                fun target(id: Int = 2, title: String = "b"): Boolean = false
            """.trimIndent(),
            expected = """
                target(
                    id = 2,
                    title = "b",
                )
            """.normalize(),
        )
    }

    fun testExpandsEmptyCallWithUnapplicableOverloads() {
        assertExpanded(
            usage = "<caret>target()",
            declarations = """
                fun target(id: Int, name: String, value: Double = 0.0): Boolean = true
                fun target(id: Int, title: String): Boolean = false
            """.trimIndent(),
            expected = """
                target(
                    id = id,
                    title = title,
                )
            """.normalize(),
        )
    }

    fun testPicksTheSameOverloadWhateverTheDeclarationOrderIs() {
        assertExpanded(
            usage = "<caret>target(1, \"2\")",
            declarations = """
                fun target(id: Int, title: String): Boolean = false
                fun target(id: Int, name: String, value: Double = 0.0): Boolean = true
            """.trimIndent(),
            expected = """
                target(
                    id = 1,
                    title = "2",
                )
            """.normalize(),
        )
    }

    fun testPreviewsTheOnlyExpansionAnUnambiguousCallHas() {
        val (info, previewed) = preview("<caret>target(1, \"2\")", DEFAULT_DECLARATIONS)

        assertSame(IntentionPreviewInfo.DIFF, info)
        assertEquals(
            """
            target(
                id = 1,
                name = "2",
                value = 0.0,
            )
            """.normalize(),
            previewed,
        )
    }

    // Several overloads fit the call, so the preview cannot know which one the user will pick.
    fun testPreviewsNothingWhenTheCallLeavesAChoice() {
        val (info, previewed) = preview("<caret>target(1, \"2\")", AMBIGUOUS_DECLARATIONS)

        assertSame(IntentionPreviewInfo.EMPTY, info)
        assertEquals("target(1, \"2\")", previewed)
    }

    fun testLeavesTheOriginalCallUntouchedWhenPreviewing() {
        configure("<caret>target(1, \"2\")", DEFAULT_DECLARATIONS)
        preview("<caret>target(1, \"2\")", DEFAULT_DECLARATIONS)

        assertEquals("target(1, \"2\")", callText("target"))
    }

    // An underfilled call is what the intention exists for, so a parameter without an
    // argument does not disqualify its declaration; the chooser lists what still fits.
    fun testOffersEveryOverloadOfACallWithMissingArguments() {
        val chooser = RecordingChooser { it.first() }
        val expanded = invokeOn(
            "<caret>testFun2()",
            """
                fun testFun2(id: Int): Boolean = false
                fun testFun2(name: String): Boolean = false
            """.trimIndent(),
            chooser,
        )

        assertEquals(
            listOf("testFun2(id: Int)", "testFun2(name: String)"),
            chooser.offered.orEmpty().map { it.signature },
        )
        assertEquals(
            """
            testFun2(
                id = id,
            )
            """.normalize(),
            expanded,
        )
    }

    fun testIsNotAvailableWhenNoOverloadTakesEveryArgument() {
        assertAvailable(
            usage = "<caret>target(1, \"2\", 3.0)",
            declarations = """
                fun target(first: Int, second: String): Boolean = true
                fun target(id: Int, name: String): Boolean = false
            """.trimIndent(),
            expected = false,
        )
    }

    fun testIsNotAvailableWhenTheOnlyOverloadTakesFewerArguments() {
        assertAvailable(
            usage = "<caret>target(1, \"2\", 3.0)",
            declarations = "fun target(id: Int, name: String): Boolean = true",
            expected = false,
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
        chooser: OverloadChooser = RecordingChooser { it.first() },
    ) {
        assertEquals(expected, invokeOn(usage, declarations, chooser))
    }

    private fun invokeOn(usage: String, declarations: String, chooser: OverloadChooser): String {
        configure(usage, declarations)
        val intention = NamedArgumentStubsIntention()
        intention.overloadChooser = chooser
        val element = elementAtCaret()
        val callee = PsiTreeUtil.getParentOfType(element, KtCallExpression::class.java)?.calleeExpression?.text
            ?: error("There is no call expression around the caret")
        assertTrue("The intention should be available for `$usage`", intention.isAvailable(project, myFixture.editor, element))

        WriteCommandAction.runWriteCommandAction(project) {
            intention.invoke(project, myFixture.editor, element)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        return callText(callee)
    }

    private fun callText(callee: String, file: PsiFile = myFixture.file): String =
        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
            .first { it.calleeExpression?.text == callee }
            .text.normalize()

    // The intentions preview writes to a detached copy of the file; do the same here.
    private fun preview(usage: String, declarations: String): Pair<IntentionPreviewInfo, String> {
        configure(usage, declarations)
        val intention = NamedArgumentStubsIntention()
        val callee = PsiTreeUtil.getParentOfType(elementAtCaret(), KtCallExpression::class.java)?.calleeExpression?.text
            ?: error("There is no call expression around the caret")
        val copy = IntentionPreviewUtils.obtainCopyForPreview(myFixture.file)

        var info: IntentionPreviewInfo = IntentionPreviewInfo.EMPTY
        IntentionPreviewUtils.previewSession(myFixture.editor) {
            info = intention.generatePreview(project, myFixture.editor, copy)
        }

        return info to callText(callee, copy)
    }

    private fun String.normalize(): String = replace(Regex("\\s+"), " ").trim()

    // Stands in for the popup: records what was offered and applies the caller's pick.
    private class RecordingChooser(private val pick: (List<Overload>) -> Overload?) : OverloadChooser {
        var offered: List<Overload>? = null
            private set

        override fun choose(overloads: List<Overload>, editor: Editor, onChosen: (Overload) -> Unit) {
            offered = overloads
            pick(overloads)?.let(onChosen)
        }
    }

    companion object {
        private const val DEFAULT_DECLARATIONS =
            "fun target(id: Int, name: String, value: Double = 0.0): Boolean = true"

        private val AMBIGUOUS_DECLARATIONS = """
            fun target(first: Int, second: String): Boolean = true
            fun target(id: Int, name: String): Boolean = false
        """.trimIndent()
    }
}
