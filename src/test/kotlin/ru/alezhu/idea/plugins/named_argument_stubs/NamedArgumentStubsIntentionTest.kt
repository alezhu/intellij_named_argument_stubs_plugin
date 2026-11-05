package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
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

    // The popup lists flattened signatures, so two declarations can read the same. The choice must
    // still land on the declaration it was made for, identified by file and range, not by that text.
    fun testAppliesTheChosenDeclarationWhenTwoSignaturesReadTheSame() {
        val declarations = listOf(
            "fun target(id: Int, s: String = \"\"\"a\nb\"\"\"): Boolean = true",
            "fun target(id: Int, s: String = \"\"\"a b\"\"\"): Boolean = false",
        ).joinToString("\n")
        configure("<caret>target(1)", declarations)
        val chooser = RecordingChooser { it.last() }
        val intention = NamedArgumentStubsIntention().apply { overloadChooser = chooser }

        intention.invoke(project, myFixture.editor, elementAtCaret())
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(
            "The chooser preconditions changed: both rows must read the same",
            1,
            chooser.offered.orEmpty().map { it.signature }.distinct().size,
        )
        assertEquals(2, chooser.offered.orEmpty().size)

        val expanded = callRawText("target")
        assertTrue(
            "Expected the default of the chosen declaration, got:\n$expanded",
            expanded.contains("s = \"\"\"a b\"\"\""),
        )
        assertFalse(
            "The default of the other declaration leaked in:\n$expanded",
            expanded.contains("\"\"\"a\nb\"\"\""),
        )
    }

    // The intention's own command is over by the time the popup reports a choice, so the callback
    // has to open a named command or the write lands in undo as an unnamed entry.
    fun testNamesTheCommandThatAppliesThePopupChoice() {
        configure("<caret>target(1, \"2\")", AMBIGUOUS_DECLARATIONS)
        val intention = NamedArgumentStubsIntention().apply {
            overloadChooser = RecordingChooser { it.first() }
        }
        val started = mutableListOf<String?>()
        project.messageBus.connect(testRootDisposable).subscribe(
            CommandListener.TOPIC,
            object : CommandListener {
                override fun commandStarted(event: CommandEvent) {
                    started += event.commandName
                }
            },
        )

        intention.invoke(project, myFixture.editor, elementAtCaret())
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(listOf("Add named argument stubs"), started)
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

    fun testKeepsACommentBetweenArguments() {
        assertExpanded(
            usage = "<caret>target(1, /* name */ \"2\")",
            expected = """
                target(
                    id = 1,
                    /* name */ name = "2",
                    value = 0.0,
                )
            """.normalize(),
        )
    }

    // A comment before the first argument has nothing to trail, so it leads that argument.
    fun testKeepsACommentBeforeTheFirstArgument() {
        assertExpanded(
            usage = "<caret>target(/* first */ 1, \"2\")",
            expected = """
                target(
                    /* first */ id = 1,
                    name = "2",
                    value = 0.0,
                )
            """.normalize(),
        )
    }

    // A line comment runs to the end of the line, so it belongs to the argument before it.
    fun testKeepsLineCommentsThatFollowArguments() {
        val usage = """
            <caret>target(
                1, // id
                "2", // name
            )
        """.trimIndent()
        invokeOn(usage, DEFAULT_DECLARATIONS, RecordingChooser { it.first() })

        assertEquals(
            """
            target(
            id = 1, // id
            name = "2", // name
            value = 0.0,
            )
            """.trimIndent(),
            callRawText("target").trimIndentPerLine(),
        )
    }

    // This comment is inside the argument, so it travels with the argument's own text.
    fun testKeepsACommentInsideAnAlreadyNamedArgument() {
        assertExpanded(
            usage = "<caret>target(1, value = /* keep */ 3.0)",
            expected = """
                target(
                    id = 1,
                    name = name,
                    value = /* keep */ 3.0,
                )
            """.normalize(),
        )
    }

    fun testKeepsEveryPositionalArgumentOfAVarargCall() {
        assertExpanded(
            usage = "<caret>target(1, \"a\", \"b\")",
            declarations = "fun target(id: Int, vararg names: String): Boolean = true",
            expected = """
                target(
                    id = 1,
                    names = *arrayOf("a", "b"),
                )
            """.normalize(),
        )
    }

    fun testStubsAVarargThatGotNoArgument() {
        assertExpanded(
            usage = "<caret>target(1)",
            declarations = "fun target(id: Int, vararg names: String): Boolean = true",
            expected = """
                target(
                    id = 1,
                    names = *emptyArray(),
                )
            """.normalize(),
        )
    }

    // A lone spread already is the array the vararg needs, so it is reused instead of wrapped.
    fun testKeepsASpreadArgumentAsTheVararg() {
        assertExpanded(
            usage = "<caret>target(1, *arrayOf(\"a\", \"b\"))",
            declarations = "fun target(id: Int, vararg names: String): Boolean = true",
            expected = """
                target(
                    id = 1,
                    names = *arrayOf("a", "b"),
                )
            """.normalize(),
        )
    }

    fun testExpandsAVarargBeforeTrailingParameters() {
        assertExpanded(
            usage = "<caret>target(\"a\", \"b\")",
            declarations = "fun target(vararg names: String, active: Boolean = false): Boolean = true",
            expected = """
                target(
                    names = *arrayOf("a", "b"),
                    active = false,
                )
            """.normalize(),
        )
    }

    // `target { }` has no valueArgumentList, so there is nothing for process() to rewrite; the row
    // must not appear at all instead of doing nothing.
    fun testIsNotAvailableForACallWithoutAnArgumentList() {
        assertAvailable(
            usage = "<caret>target { }",
            declarations = "fun target(id: Int = 0, block: () -> Unit): Boolean = true",
            expected = false,
        )
    }

    // The trailing lambda is a child of the call, not an entry of valueArgumentList. It fills the
    // last parameter, so that parameter must not be stubbed next to the lambda.
    fun testKeepsTheTrailingLambdaOutOfTheNamedArguments() {
        assertExpanded(
            usage = "<caret>target(1) { }",
            declarations = "fun target(id: Int, block: () -> Unit): Boolean = true",
            expected = "target( id = 1, ) { }",
        )
    }

    fun testStubsMissingArgumentsNextToATrailingLambda() {
        assertExpanded(
            usage = "<caret>target() { }",
            declarations = "fun target(id: Int, block: () -> Unit): Boolean = true",
            expected = "target( id = id, ) { }",
        )
    }

    fun testIsNotAvailableWhenTheTrailingLambdaIsTheOnlyArgument() {
        assertAvailable(
            usage = "<caret>target() { }",
            declarations = "fun target(block: () -> Unit): Boolean = true",
            expected = false,
        )
    }

    fun testIsNotAvailableWhenEveryArgumentButTheLambdaIsNamed() {
        assertAvailable(
            usage = "<caret>target(id = 1) { }",
            declarations = "fun target(id: Int, block: () -> Unit): Boolean = true",
            expected = false,
        )
    }

    // Positional arguments cannot use the slot the trailing lambda fills.
    fun testIsNotAvailableWhenPositionalArgumentsDoNotFitBeforeTheLambda() {
        assertAvailable(
            usage = "<caret>target(1, 2) { }",
            declarations = "fun target(id: Int, block: () -> Unit): Boolean = true",
            expected = false,
        )
    }

    fun testNamesArgumentsBeforeAVarargThatTakesTheTrailingLambda() {
        assertExpanded(
            usage = "<caret>target(1) { }",
            declarations = "fun target(id: Int, vararg blocks: () -> Unit): Boolean = true",
            expected = "target( id = 1, ) { }",
        )
    }

    // The callee is a parameter of function type, so it resolves to the KtParameter. The first
    // KtParameterList inside it is the one of `(Int, Int) -> Int`, whose parameters are unnamed;
    // expanding it wrote `target(null = 1, null = 2)`. Kotlin prohibits named arguments for a
    // value of function type, so there is nothing valid to generate.
    fun testIsNotAvailableWhenTheCalleeIsAParameterOfFunctionType() {
        assertAvailable(
            usage = "true",
            declarations = "fun applyIt(target: (Int, Int) -> Int): Int = tar<caret>get(1, 2)",
            expected = false,
        )
    }

    // Same for a property initialized with a lambda: the first KtParameterList found belongs to
    // the lambda, not to the call, and named arguments are rejected for function types.
    fun testIsNotAvailableWhenTheCalleeIsAPropertyOfFunctionType() {
        assertAvailable(
            usage = "true",
            declarations = """
                val target = { a: Int, b: Int -> a + b }
                val other = tar<caret>get(1, 2)
            """.trimIndent(),
            expected = false,
        )
    }

    // The parser recovers from an incomplete parameter list by leaving a null-named KtParameter
    // ahead of the real ones (`fun target(, value: Int)` -> [null <>, value]). Without the name
    // check the expansion writes `null = 1` into the call.
    fun testIsNotAvailableForADeclarationWithAnUnnamedParameter() {
        assertAvailable(
            usage = "<caret>target(1)",
            declarations = "fun target(, value: Int): Boolean = true",
            expected = false,
        )
    }

    // A Java method resolves to a PsiMethod, which carries no Kotlin parameter names. That is a
    // known gap of the Kotlin-PSI-only implementation, and refusing it keeps the call untouched.
    fun testIsNotAvailableForAJavaMethod() {
        myFixture.addFileToProject(
            "J.java",
            "public class J { public void target(int first, int second) {} }",
        )
        assertAvailable(usage = "J().tar<caret>get(1, 2)", declarations = "", expected = false)
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

    // Whitespace matters here: a raw string default differs from another by the newline inside it.
    private fun callRawText(callee: String, file: PsiFile = myFixture.file): String =
        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
            .first { it.calleeExpression?.text == callee }
            .text

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

    // A line comment swallows everything up to the newline, so normalizing all whitespace would
    // merge it with the next argument; drop only the per-line indentation instead.
    private fun String.trimIndentPerLine(): String = lines().joinToString("\n") { it.trim() }

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
