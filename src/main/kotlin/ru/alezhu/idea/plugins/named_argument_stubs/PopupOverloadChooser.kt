package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep

/** Asks the user which of the equally fitting [Overload]s the call means. */
internal fun interface OverloadChooser {
    fun choose(overloads: List<Overload>, editor: Editor, onChosen: (Overload) -> Unit)
}

internal class PopupOverloadChooser : OverloadChooser {
    override fun choose(overloads: List<Overload>, editor: Editor, onChosen: (Overload) -> Unit) {
        val step = object : BaseListPopupStep<Overload>(TITLE, overloads) {
            override fun getTextFor(value: Overload): String = value.signature

            override fun onChosen(selectedValue: Overload, finalChoice: Boolean): PopupStep<*>? =
                if (finalChoice) {
                    onChosen(selectedValue)
                    PopupStep.FINAL_CHOICE
                } else {
                    super.onChosen(selectedValue, finalChoice)
                }
        }
        JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(editor)
    }

    companion object {
        private const val TITLE = "Add named argument stubs for"
    }
}
