// !DIAGNOSTICS: -UNUSED_PARAMETER

class K {
    class Nested
}

fun foo(f: Any) {}

fun test() {
    foo(K::Nested)
}
