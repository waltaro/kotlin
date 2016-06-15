package kotlin.jdk7.internal

import java.io.Closeable
import kotlin.internal.*
import kotlin.jdk7.closeSuppressed

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")
internal open class JDK7PlatformImplementations : DefaultPlatformImplementations() {
    override fun closeSuppressed(instance: Closeable, cause: Throwable) = instance.closeSuppressed(cause)
}
