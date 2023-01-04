package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*

class Context(
    private val project: Project,
    private val currentElement: PsiElement
) {
    private val referenceElement: PsiElement? by lazy {
        PsiTreeUtil.getParentOfType(currentElement, KtNameReferenceExpression::class.java)
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
        if (refParent is KtCallExpression) {
            refParent
        } else {
            null
        }
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
                val name = argument.getArgumentName()!!.asName.identifier
                namedArguments[name] = argument
            }
        }
        namedArguments
    }

    fun isAvailable(): Boolean {
        val parametersCount = parameterList?.size
        return parametersCount != null && (parametersCount != namedArguments.size)
    }

    fun process() {
        val newArgumentsArray = arrayOfNulls<String>(parameterList!!.size)

        for ((index, parameter) in parameterList!!.withIndex()) {
            val name = parameter.name

            var argument: KtValueArgument? = null
            var value: String? = null
            if (namedArguments.containsKey(name)) {
                argument = namedArguments[name]
            } else if (arguments!!.size > index) {
                argument = arguments!![index]
            }
            if (argument != null) {
                value = argument.getArgumentExpression()?.text
            } else if (parameter.hasDefaultValue()) {
                value = parameter.defaultValue?.text
            }
            if (value == null) {
                value = name
            }
            newArgumentsArray[index] = "$name = $value,"
        }

        val factory = KtPsiFactory(project)
        val newCallStr = newArgumentsArray.joinToString(
            separator = "\n",
            prefix = "(\n",
            postfix = "\n)",
        )
        val ktNewArguments = factory.createCallArguments(newCallStr)
        callExpression!!.valueArgumentList!!.replace(ktNewArguments as PsiElement)
    }
}

