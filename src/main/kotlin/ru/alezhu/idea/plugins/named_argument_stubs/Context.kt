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
        //reference will be null if references more than 1
//        referenceElement?.reference
        referenceElement?.references?.firstOrNull { it is PsiReference }
    }

    private val definition: PsiElement? by lazy {
        var result = reference?.resolve()
        if (reference != null && result == null && reference is PsiPolyVariantReference) {
            val definitions = (reference as PsiPolyVariantReference).multiResolve(true)
            if (definitions.isNotEmpty()) {
                result = definitions[0].element
            }
        }
        result
    }

    private val callExpression: KtCallExpression? by lazy {
        val refParent = reference?.element?.parent
        refParent as? KtCallExpression
    }

    private val parameterList: List<KtParameter>? by lazy {
        definition?.let {
            val ktParameterList = PsiTreeUtil.findChildOfType(it, KtParameterList::class.java)
            ktParameterList?.parameters
        }
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

    fun isAvailable(): Boolean {
        val parametersCount = parameterList?.size
        return parametersCount != null && (parametersCount != namedArguments.size)
    }

    fun process() {
        // PSI can change between isAvailable() and invoke(); exit quietly instead of throwing.
        val parameters = parameterList ?: return
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
}