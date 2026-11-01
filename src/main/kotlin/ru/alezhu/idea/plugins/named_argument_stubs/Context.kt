package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument


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
        val elements = if (ref is PsiPolyVariantReference) {
            ref.multiResolve(false).mapNotNull { it.element }
        } else {
            listOfNotNull(ref.resolve())
        }
        // Merged declarations (expect/actual, multi-file) repeat the same PSI element.
        elements.distinctBy { it.containingFile?.virtualFile?.path to it.textRange }
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

    /**
     * Declarations the call can be expanded against. A single one is applied without asking,
     * several mean the user has to pick, none means the intention does not apply.
     *
     * The fit is judged from the call shape alone — arity and the names actually present;
     * positional arguments fill the leading parameters, every named argument has to exist in
     * the declaration, and the parameters left without an argument are the ones to be stubbed.
     * Types are not compared, so an overload that differs only by argument types stays a
     * candidate and reaches the chooser instead of being silently guessed.
     */
    fun expansionTargets(): List<Overload> {
        val candidates = overloads
        if (candidates.size <= 1) return candidates

        val split = positionalArguments.size
        return candidates.filter { overload ->
            val parameters = overload.parameters
            parameters.size >= split &&
                namedArguments.keys.all { name -> parameters.any { it.name == name } }
        }
    }

    fun isAvailable(): Boolean = expansionTargets().any { it.parameters.size != namedArguments.size }

    fun process(overload: Overload) {
        // PSI can change between isAvailable() and invoke(); exit quietly instead of throwing.
        val parameters = overload.parameters
        val call = callExpression ?: return
        val argumentList = call.valueArgumentList ?: return

        val newArgumentsArray = arrayOfNulls<String>(parameters.size)
        var nextPositional = 0

        for ((index, parameter) in parameters.withIndex()) {
            val name = parameter.name

            val argument = namedArguments[name] ?: positionalArguments.getOrNull(nextPositional++)
            val value = argument?.getArgumentExpression()?.text ?: parameter.defaultValue?.text ?: name
            newArgumentsArray[index] = "$name = $value,"
        }

        val factory = KtPsiFactory(project)
        val newCallStr = newArgumentsArray.joinToString(
            separator = "\n",
            prefix = "(\n",
            postfix = "\n)",
        )
        val ktNewArguments = factory.createCallArguments(newCallStr)
        argumentList.replace(ktNewArguments as PsiElement)
    }

    private val overloads: List<Overload> by lazy {
        definitionCandidates.mapNotNull { element ->
            val parameters = PsiTreeUtil.findChildOfType(element, KtParameterList::class.java)?.parameters
            parameters?.let { Overload(element, it, signatureOf(it)) }
        }
    }

    private fun signatureOf(parameters: List<KtParameter>): String {
        val callee = callExpression?.calleeExpression?.text ?: return parameters.joinToString(", ", "(", ")") { it.flatText() }
        return parameters.joinToString(", ", "$callee(", ")") { it.flatText() }
    }

    private fun KtParameter.flatText(): String = text.replace(Regex("\\s+"), " ").trim()
}
