package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
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

    private val arguments: List<KtValueArgument>? by lazy {
        val ktArguments = callExpression?.valueArgumentList
        ktArguments?.arguments
    }

    private val namedArguments: HashMap<String, KtValueArgument> by lazy {
        val namedArguments = HashMap<String, KtValueArgument>()
        arguments?.forEach { argument ->
            if (argument.isNamed()) {
                val name = argument.getArgumentName()?.asName?.identifier ?: return@forEach
                namedArguments[name] = argument
            }
        }
        namedArguments
    }

    private val positionalArguments: List<KtValueArgument> by lazy {
        arguments?.filterNot { it.isNamed() }.orEmpty()
    }

    private val lambdaArguments: List<KtLambdaArgument> by lazy {
        callExpression?.lambdaArguments.orEmpty()
    }

    // A trailing lambda is a child of the call, not an entry of valueArgumentList, and it always
    // fills the last parameter. Broken code can parse more than one, which has no sane mapping.
    private val trailingLambdaSlots: Int by lazy {
        when (lambdaArguments.size) {
            0 -> 0
            1 -> 1
            else -> -1
        }
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
        val candidates = overloads
        val split = positionalArguments.size
        val lambdaSlots = trailingLambdaSlots
        if (lambdaSlots < 0) return emptyList()
        // Arity applies even to the single candidate the frontend leaves: a declaration that cannot
        // take every positional argument would make process() drop the extras in silence. A leading
        // vararg absorbs any number of them. The trailing lambda reserves the last parameter, so
        // positional arguments may fill only the ones before it.
        val fitting = candidates.filter { overload ->
            val parameters = overload.parameters
            val positionalTargets = parameters.dropLast(lambdaSlots)
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
        val lambdaSlots = trailingLambdaSlots
        return expansionTargets().any { it.parameters.size != namedArguments.size + lambdaSlots }
    }

    fun process(overload: Overload) {
        // PSI can change between isAvailable() and invoke(); exit quietly instead of throwing.
        val parameters = overload.parameters
        val call = callExpression ?: return
        val argumentList = call.valueArgumentList ?: return
        val sourceArguments = argumentList.arguments
        val lambdaFilledIndex = if (trailingLambdaSlots == 1) parameters.lastIndex else -1

        val slots = ArrayList<ArgumentSlot>(parameters.size)
        var nextPositional = 0

        for ((index, parameter) in parameters.withIndex()) {
            // The lambda stays outside the list being replaced; its parameter is this skipped slot.
            if (index == lambdaFilledIndex) continue
            val name = parameter.name
            val named = namedArguments[name]

            val slot = when {
                named != null ->
                    ArgumentSlot(named.text, listOf(sourceArguments.indexOf(named)))

                parameter.isVarArg -> {
                    val elements = positionalArguments.drop(nextPositional)
                    nextPositional = positionalArguments.size
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

                nextPositional < positionalArguments.size -> {
                    val argument = positionalArguments[nextPositional++]
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
            val previous = sourceArguments.indexOfLast {
                it.textRange.endOffset <= comment.textRange.startOffset
            }
            val next = sourceArguments.indexOfFirst {
                it.textRange.startOffset >= comment.textRange.endOffset
            }
            val previousSlot = slots.firstOrNull { previous in it.sourceIndices }
            val nextSlot = slots.firstOrNull { next in it.sourceIndices }
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
            val parameters = PsiTreeUtil.findChildOfType(element, KtParameterList::class.java)?.parameters
            parameters?.let { Overload(element, it, signatureOf(it, callee)) }
        }
    }

    private fun signatureOf(parameters: List<KtParameter>, callee: String): String =
        parameters.joinToString(", ", "$callee(", ")") { it.flatText() }

    private fun KtParameter.flatText(): String = text.replace(Regex("\\s+"), " ").trim()
}
