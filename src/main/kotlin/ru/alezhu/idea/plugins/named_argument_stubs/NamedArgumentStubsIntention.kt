package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class NamedArgumentStubsIntention : PsiElementBaseIntentionAction(), IntentionAction {
    init {
        text = "Add named argument stubs"
    }

    override fun getFamilyName(): String = "Add named argument stubs for all parameters"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val context = Context(project, element)
        return try {
            context.isAvailable()
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Throwable) {
            LOG.error(ex)
            false
        }
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val context = Context(project, element)
        try {
            context.process()
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Throwable) {
            LOG.error(ex)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(this::class.java)
    }

}

