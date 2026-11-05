package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList


class Context(
    private val project: Project,
    private val currentElement: PsiElement
) {
    private val referenceElement: PsiElement? by lazy {
        PsiTreeUtil.getParentOfType<PsiElement>(currentElement, KtNameReferenceExpression::class.java)
    }

    private val reference: PsiReference? by lazy {
        // The callee name carries a single composite reference; the first element is it.
        referenceElement?.references?.firstOrNull()
    }

    private val definitionCandidates: List<PsiElement> by lazy {
        val ref = reference ?: return@lazy emptyList()
        // Only a parenthesised argument list is something process() can rewrite (and what it exits
        // on). A bare trailing lambda `f { }` has none, so availability must refuse it rather than
        // offering an expansion that cannot be written.
        if (callExpression?.valueArgumentList == null) return@lazy emptyList()
        val elements = if (ref is PsiPolyVariantReference) {
            ref.multiResolve(false).mapNotNull { it.element }
        } else {
            listOfNotNull(ref.resolve())
        }
        // Merged declarations (expect/actual, multi-file) repeat the same PSI element.
        elements.distinctBy { it.declarationKey() }
    }

    private val callExpression: KtCallExpression? by lazy {
        val refParent = reference?.element?.parent
        refParent as? KtCallExpression
    }

    private val arguments: List<KtValueArgument> by lazy {
        callExpression?.valueArgumentList?.arguments.orEmpty()
    }

    private val namedArguments: Map<String, KtValueArgument> by lazy {
        arguments.namedByName()
    }

    private val positionalArguments: List<KtValueArgument> by lazy {
        arguments.filterNot { it.isNamed() }
    }

    private val lambdaArguments: List<KtLambdaArgument> by lazy {
        callExpression?.lambdaArguments.orEmpty()
    }

    // A trailing lambda is a child of the call, not an entry of valueArgumentList, and it always
    // fills the last parameter. Broken code can parse more than one, which has no sane mapping.
    private val trailingLambda: KtLambdaArgument? by lazy {
        lambdaArguments.singleOrNull()
    }

    private val tooManyTrailingLambdas: Boolean by lazy {
        lambdaArguments.size > 1
    }

    /**
     * Declarations the call can be expanded against. A single one is applied without asking,
     * several mean the user has to pick, none means the intention does not apply.
     *
     * The fit is judged from the call shape alone — arity always, the names actually present only
     * while the frontend still leaves a choice. Positional arguments fill the leading parameters
     * (a vararg takes all the rest), a trailing lambda keeps the last one, and the parameters left
     * without an argument are the ones to be stubbed. Types are not compared, so an overload that
     * differs only by argument types stays a candidate and reaches the chooser instead of being
     * silently guessed.
     */
    fun expansionTargets(): List<Overload> {
        if (tooManyTrailingLambdas) return emptyList()
        val candidates = overloads
        val split = positionalArguments.size
        val lambdaSlots = if (trailingLambda != null) 1 else 0
        // Arity applies even to the single candidate the frontend leaves: a declaration that cannot
        // take every positional argument would make process() drop the extras in silence. A leading
        // vararg absorbs any number of them. The trailing lambda reserves the last parameter, so
        // positional arguments may fill only the ones before it.
        val fitting = candidates.filter { overload ->
            val parameters = overload.parameters
            // A parameter already filled by name takes no positional argument, and a vararg filled
            // by name absorbs none either, so only the free parameters are slots for them.
            val positionalTargets = parameters.dropLast(lambdaSlots).filter { it.name !in namedArguments }
            parameters.size >= lambdaSlots &&
                (positionalTargets.any { it.isVarArg } || positionalTargets.size >= split)
        }
        if (candidates.size <= 1) return fitting

        return fitting.filter { overload ->
            namedArguments.keys.all { name -> overload.parameters.any { it.name == name } }
        }
    }

    // The trailing lambda is already an argument of the last parameter, so it counts as one filled
    // slot and must not be stubbed a second time.
    fun isAvailable(): Boolean {
        val lambdaSlots = if (trailingLambda != null) 1 else 0
        return expansionTargets().any { it.parameters.size != namedArguments.size + lambdaSlots }
    }

    fun process(overload: Overload) {
        // PSI can change between isAvailable() and invoke(); exit quietly instead of throwing.
        val parameters = overload.parameters
        val call = callExpression ?: return
        val argumentList = call.valueArgumentList ?: return
        // One snapshot for the whole rewrite. The slots reference arguments by their index in this
        // very list, so indexOf() is an identity lookup inside a single list, not across two lists
        // built by separate calls to `arguments`.
        val sourceArguments = argumentList.arguments
        val named = sourceArguments.namedByName()
        val positional = sourceArguments.filterNot { it.isNamed() }
        val lambdaFilledIndex = if (trailingLambda != null) parameters.lastIndex else -1

        val slots = ArrayList<ArgumentSlot>(parameters.size)
        var nextPositional = 0

        for ((index, parameter) in parameters.withIndex()) {
            // The lambda stays outside the list being replaced; its parameter is this skipped slot.
            if (index == lambdaFilledIndex) continue
            val name = parameter.name
            val argument = named[name]

            val slot = when {
                argument != null ->
                    ArgumentSlot(argument.text, listOf(sourceArguments.indexOf(argument)))

                parameter.isVarArg -> {
                    val elements = positional.drop(nextPositional)
                    nextPositional = positional.size
                    val indices = elements.map { sourceArguments.indexOf(it) }
                    val text = when {
                        elements.isEmpty() -> "$name = *emptyArray()"
                        // A lone spread already is the array of elements, so it is reused as is.
                        elements.singleOrNull()?.getSpreadElement() != null ->
                            "$name = ${elements.single().text}"

                        else -> "$name = *arrayOf(${elements.joinToString(", ") { it.text }})"
                    }
                    ArgumentSlot(text, indices)
                }

                nextPositional < positional.size -> {
                    val argument = positional[nextPositional++]
                    ArgumentSlot("$name = ${argument.text}", listOf(sourceArguments.indexOf(argument)))
                }

                else -> ArgumentSlot(
                    "$name = ${parameter.defaultValue?.text ?: name}",
                    emptyList(),
                )
            }
            slots += slot
        }

        if (slots.isEmpty()) return

        val newCallStr = renderArguments(argumentList, sourceArguments, slots)
        val factory = KtPsiFactory(project)
        val ktNewArguments = factory.createCallArguments(newCallStr)
        argumentList.replace(ktNewArguments as PsiElement)
    }

    /**
     * A rebuilt argument keeps the text of the arguments it came from, so a comment inside one of
     * them survives. The comments between arguments are children of the argument list, not of an
     * argument, so they have to be carried over by hand: a line comment trails the argument before
     * it, a block comment leads the argument after it, and a comment with no argument to attach to
     * goes to the end of the rebuilt list.
     */
    private fun renderArguments(
        argumentList: KtValueArgumentList,
        sourceArguments: List<KtValueArgument>,
        slots: List<ArgumentSlot>,
    ): String {
        val comments = PsiTreeUtil.findChildrenOfType(argumentList, PsiComment::class.java)
            .filter { comment -> sourceArguments.none { it.textRange.contains(comment.textRange) } }
        val trailingComments = ArrayList<String>()

        for (comment in comments) {
            // -1 means no argument lies on that side of the comment, so there is no index to look up.
            val previous = sourceArguments.indexOfLast {
                it.textRange.endOffset <= comment.textRange.startOffset
            }.takeIf { it >= 0 }
            val next = sourceArguments.indexOfFirst {
                it.textRange.startOffset >= comment.textRange.endOffset
            }.takeIf { it >= 0 }
            val previousSlot = previous?.let { index -> slots.firstOrNull { index in it.sourceIndices } }
            val nextSlot = next?.let { index -> slots.firstOrNull { index in it.sourceIndices } }
            when {
                comment.text.startsWith("//") && previousSlot != null ->
                    previousSlot.trailingComments += comment.text

                nextSlot != null -> nextSlot.leadingComments += comment.text
                else -> trailingComments += comment.text
            }
        }

        val lines = ArrayList<String>(slots.size)
        for (slot in slots) {
            lines += slot.leadingComments
            val inlineComment = slot.trailingComments.firstOrNull()?.let { " $it" }.orEmpty()
            lines += "${slot.text},$inlineComment"
            lines += slot.trailingComments.drop(1)
        }
        lines += trailingComments

        return lines.joinToString(
            separator = "\n",
            prefix = "(\n",
            postfix = "\n)",
        )
    }

    private class ArgumentSlot(
        val text: String,
        val sourceIndices: List<Int>,
    ) {
        val leadingComments = ArrayList<String>()
        val trailingComments = ArrayList<String>()
    }

    private val overloads: List<Overload> by lazy {
        val callee = callExpression?.calleeExpression?.text ?: return@lazy emptyList()
        definitionCandidates.mapNotNull { element ->
            val parameters = element.namedArgumentParameters() ?: return@mapNotNull null
            Overload(element, parameters, signatureOf(parameters, callee))
        }
    }

    /**
     * The parameters named arguments can be generated from, or null when the declaration is not
     * the callee's own signature. A property or a parameter of a function type resolves to a
     * [org.jetbrains.kotlin.psi.KtProperty]/[KtParameter], and the first `KtParameterList` inside
     * one of those belongs to the type `(Int, Int) -> Int` or to the lambda initializing it — its
     * parameters are unnamed, which is how `null = 1` used to reach the call. Kotlin prohibits named
     * arguments for function types, so such a callee is refused instead of expanded.
     */
    private fun PsiElement.namedArgumentParameters(): List<KtParameter>? {
        val parameters = when (this) {
            is KtNamedFunction -> valueParameterList?.parameters
            is KtConstructor<*> -> valueParameterList?.parameters
            is KtClass -> primaryConstructor?.valueParameterList?.parameters
            else -> null
        } ?: return null
        // A parameter without a name has no argument name to emit. Valid Kotlin cannot produce one
        // here (destructuring is lambda-only); this is the parser's recovery on incomplete source,
        // e.g. `fun target(, value: Int)` -> [null <>, value].
        return parameters.takeIf { list -> list.all { it.name != null } }
    }

    private fun signatureOf(parameters: List<KtParameter>, callee: String): String =
        parameters.joinToString(", ", "$callee(", ")") { it.flatText() }

    private fun List<KtValueArgument>.namedByName(): Map<String, KtValueArgument> {
        val named = HashMap<String, KtValueArgument>()
        for (argument in this) {
            if (!argument.isNamed()) continue
            val name = argument.getArgumentName()?.asName?.identifier ?: continue
            named[name] = argument
        }
        return named
    }

    private fun KtParameter.flatText(): String = text.replace(Regex("\\s+"), " ").trim()
}
