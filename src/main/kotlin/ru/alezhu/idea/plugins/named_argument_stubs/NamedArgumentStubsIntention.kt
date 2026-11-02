package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class NamedArgumentStubsIntention : PsiElementBaseIntentionAction(), IntentionAction {
    init {
        text = "Add named argument stubs"
    }

    // Swapped in tests; the platform instantiates the intention through the no-arg constructor.
    internal var overloadChooser: OverloadChooser = PopupOverloadChooser()

    override fun getFamilyName(): String = "Add named argument stubs for all parameters"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        return try {
            Context(project, element).isAvailable()
        } catch (ex: IndexNotReadyException) {
            false
        }
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val context = Context(project, element)
        val targets = context.expansionTargets()
        when {
            targets.size == 1 -> context.process(targets.single())
            targets.size > 1 -> chooseAndProcess(project, editor, element, targets)
        }
    }

    // The popup closes after invoke() returns, so the write action it triggers reads the PSI anew.
    private fun chooseAndProcess(
        project: Project,
        editor: Editor?,
        element: PsiElement,
        targets: List<Overload>,
    ) {
        if (editor == null) return
        overloadChooser.choose(targets, editor) { chosen ->
            WriteCommandAction.runWriteCommandAction(project) {
                if (!element.isValid()) return@runWriteCommandAction
                val fresh = Context(project, element)
                // Re-resolution builds new PSI elements; the file and range identify the one the
                // user picked even when another declaration's signature reads the same.
                fresh.expansionTargets()
                    .firstOrNull { it.key == chosen.key }
                    ?.let { fresh.process(it) }
            }
        }
    }

    /**
     * Shows the expanded call in the intentions preview while there is only one way to expand it.
     * With several fitting overloads the chooser lists them, so previewing one would be a guess.
     */
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val element = getElement(editor, file) ?: return IntentionPreviewInfo.EMPTY
        val context = Context(project, element)
        val targets = context.expansionTargets()
        if (targets.size != 1) return IntentionPreviewInfo.EMPTY
        IntentionPreviewUtils.write<Throwable> { context.process(targets.single()) }
        return IntentionPreviewInfo.DIFF
    }
}
