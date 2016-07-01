interface IBase<T> {
    fun foo() {}
    fun bar() {}
}

interface IDerived<T> : IBase<T> {
    override fun foo() {}
    fun qux() {}
}

class Test : IDerived<String>, IBase<String> {
    fun test() {
        super<<!QUALIFIED_SUPERTYPE_OVERRIDDEN!>IBase<!>>.foo()
        super<<!QUALIFIED_SUPERTYPE_OVERRIDDEN!>IBase<!>>.bar()
        super<IDerived>.foo()
        super<IDerived>.bar()
        super<IDerived>.qux()
    }
}