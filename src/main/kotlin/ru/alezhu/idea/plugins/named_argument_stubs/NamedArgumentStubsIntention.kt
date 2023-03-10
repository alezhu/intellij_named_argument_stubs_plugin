package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

class NamedArgumentStubsIntention : PsiElementBaseIntentionAction(), IntentionAction {
    init {
        text = "Add named argument stubs"
    }

    private var context: Context? = null

    override fun getFamilyName(): String = "NamedArgumentStubsIntention"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        context = Context(project, element)
        return try {
            val available = context!!.isAvailable()
            if (!available) {
                context = null
            }
            available
        } catch (ex: Throwable) {
            LOG.error(ex)
            false
        }
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        if (context == null) context = Context(project, element)
        try {
            context!!.process()
        } catch (ex: Throwable) {
            LOG.error(ex)
            throw ex
        } finally {
            context = null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PsiTreeUtil::class.java)
    }

}

