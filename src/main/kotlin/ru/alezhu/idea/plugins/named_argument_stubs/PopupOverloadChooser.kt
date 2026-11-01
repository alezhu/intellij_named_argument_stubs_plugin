package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import javax.swing.JComponent

/** Asks the user which of the equally fitting [Overload]s the call means. */
internal fun interface OverloadChooser {
    fun choose(overloads: List<Overload>, anchor: JComponent, onChosen: (Overload) -> Unit)
}

internal class PopupOverloadChooser : OverloadChooser {
    override fun choose(overloads: List<Overload>, anchor: JComponent, onChosen: (Overload) -> Unit) {
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
        JBPopupFactory.getInstance().createListPopup(step).show(RelativePoint.getSouthWestOf(anchor))
    }

    companion object {
        private const val TITLE = "Add named argument stubs for"
    }
}
