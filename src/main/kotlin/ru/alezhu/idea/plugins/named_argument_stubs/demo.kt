package ru.alezhu.idea.plugins.named_argument_stubs

fun testFun(
    id: Int,
    name: String,
    value: Double = 0.0
): Boolean = false

class Test(
    val id: Int,
    val name: String,
    val value: Double = 0.0
)

fun getName() = "Tst"

val testObj1 = Test(1, value = 2.0, name = getName())
val testObj2 = Test(2, name = getName())

//val testObj3 = Test()
val test1 = testFun(1, name = "2")
val test3 = testFun(1, value = 3.0 / 4, name = "2")
//val test4 = testFun()