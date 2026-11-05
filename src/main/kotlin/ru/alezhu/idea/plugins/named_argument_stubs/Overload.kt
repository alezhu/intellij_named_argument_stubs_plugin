package ru.alezhu.idea.plugins.named_argument_stubs

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtParameter

/**
 * A declaration identified by its place in a file. PSI elements are recreated on re-resolution,
 * their file and range are not, so the tuple survives the popup round-trip. The file name covers
 * synthetic and light declarations, which report no virtual file: without it, two of them with the
 * same range would collapse into one.
 */
internal typealias DeclarationKey = Triple<String?, String?, TextRange>

internal fun PsiElement.declarationKey(): DeclarationKey {
    val file = containingFile
    return Triple(file?.virtualFile?.path, file?.name, textRange)
}

/**
 * One declaration the call could be expanded against: [parameters] drive the stubs and
 * [signature] is what the user reads in the chooser popup. The signature is display text only:
 * it flattens whitespace, so distinct declarations can read the same.
 */
class Overload(
    val declaration: PsiElement,
    val parameters: List<KtParameter>,
    val signature: String,
) {
    // Taken eagerly: the popup outlives invoke(), and the chosen declaration may be invalidated
    // by the time the callback asks for its place in the file.
    internal val key: DeclarationKey = declaration.declarationKey()
}
