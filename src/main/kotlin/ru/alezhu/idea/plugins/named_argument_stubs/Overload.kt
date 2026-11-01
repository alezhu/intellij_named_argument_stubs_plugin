package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtParameter

/**
 * One declaration the call could be expanded against: [parameters] drive the stubs and
 * [signature] is what the user reads in the chooser popup.
 */
class Overload(
    val declaration: PsiElement,
    val parameters: List<KtParameter>,
    val signature: String,
)
