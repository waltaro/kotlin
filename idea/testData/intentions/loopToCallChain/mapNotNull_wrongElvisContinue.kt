// WITH_RUNTIME
// IS_APPLICABLE: false
fun foo(list: List<String>, target: MutableList<String>) {
    Loop@
    <caret>for (s in list) {
        for (i in s.indices) {
            val v = bar(i) ?: continue@Loop
            target.add(v.substring(1))
        }
    }
}

fun bar(p: Int): String? = null