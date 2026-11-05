fun testFun(
    id: Int,
    name: String,
    value: Double = 0.0
): Boolean = false

fun testFun2(
    id: Int,
): Boolean = false

fun testFun2(
    name: String,
): Boolean = false


class Test(
    val id: Int,
    val name: String,
    val value: Double = 0.0
)

fun getName() = "Tst"

val testObj1 = Test(1, value = 2.0, name = getName())
val testObj2 = Test(2, name = getName())

val testObj3 = Test()
val test1 = testFun(1, name = "2")
val test3 = testFun(1, value = 3.0 / 4, name = "2")
val test4 = testFun()
val test5 = testFun2()

// Trailing lambda: availability must match what process() writes.

fun lambdaFun(id: Int, block: () -> Unit): Boolean = false

fun lambdaOnly(block: () -> Unit): Boolean = false

fun lambdaDefaults(id: Int = 0, block: () -> Unit): Boolean = false

fun lambdaVararg(id: Int, vararg blocks: () -> Unit): Boolean = false

class LambdaHolder(val id: Int, val block: () -> Unit)

// Expects: lambdaFun(id = 1) { } — the lambda stays outside, no block = block.
val lambda1 = lambdaFun(1) { }

// Expects: lambdaFun(id = id) { } — the missing parameter becomes a stub.
val lambda2 = lambdaFun() { }

// Expects: no intention row — there is no valueArgumentList to rewrite.
val lambda3 = lambdaFun { }

// Expects: no intention row — id is named, the lambda already fills block.
val lambda4 = lambdaFun(id = 1) { }

// Expects: no intention row — 1 and 2 cannot both fit before the lambda slot.
val lambda5 = lambdaFun(1, 2) { }

// Expects: no intention row — the lambda is the only argument.
val lambda6 = lambdaOnly() { }

// Expects: no intention row — no parentheses, even though id has a default to stub.
val lambda7 = lambdaDefaults { }

// Expects: lambdaVararg(id = 1) { } — the vararg keeps the trailing lambda.
val lambda8 = lambdaVararg(1) { }

// Expects: lambdaFun(id = 1, block = { }) — a lambda inside parentheses is a normal argument.
val lambda9 = lambdaFun(1, { })

// Expects: LambdaHolder(id = 1) { } — constructors behave the same way.
val lambda10 = LambdaHolder(1) { }