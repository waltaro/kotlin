@file:JvmName("StandardJDK7Kt")
package kotlin.jdk7


public inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
    val result = try {
        block(this)
    } catch (e: Throwable) {
        @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
        closeSuppressed(e)
        throw e
    }
    close()
    return result
}

/**
 * Closes this [AutoCloseable] suppressing possible exception or error thrown by [AutoCloseable.close] function.
 * The suppressed exception is added to the list of suppressed exceptions of [cause] exception.
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@kotlin.internal.InlineExposed
internal fun AutoCloseable.closeSuppressed(cause: Throwable) {
    try {
        close()
    } catch (closeException: Throwable) {
        cause.addSuppressed(closeException)
    }
}

