package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class NamedArgumentStubsIntention : PsiElementBaseIntentionAction(), IntentionAction {
    init {
        text = "Add named argument stubs"
    }

    // Swapped in tests; the platform instantiates the intention through the no-arg constructor.
    internal var overloadChooser: OverloadChooser = PopupOverloadChooser()

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
        try {
            val context = Context(project, element)
            val targets = context.expansionTargets()
            when {
                targets.size == 1 -> context.process(targets.single())
                targets.size > 1 -> chooseAndProcess(project, editor, element, targets)
            }
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Throwable) {
            LOG.error(ex)
        }
    }

    // The popup closes after invoke() returns, so the write action it triggers reads the PSI anew.
    private fun chooseAndProcess(
        project: Project,
        editor: Editor?,
        element: PsiElement,
        targets: List<Overload>,
    ) {
        val anchor = editor?.contentComponent ?: return
        overloadChooser.choose(targets, anchor) { chosen ->
            WriteCommandAction.runWriteCommandAction(project) {
                if (!element.isValid()) return@runWriteCommandAction
                val fresh = Context(project, element)
                fresh.expansionTargets()
                    .firstOrNull { it.signature == chosen.signature }
                    ?.let { fresh.process(it) }
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(this::class.java)
    }

}
