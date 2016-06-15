package kotlin.jdk7.test

import java.io.*
import org.junit.Test
import kotlin.test.*

class TryWithResourcesCloseableTest {

    class Resource(val faultyClose: Boolean = false) : Closeable {

        override fun close() {
            if (faultyClose)
                throw IOException("Close failed")
        }
    }

    @Test fun success() {
        val result = Resource().use { "ok" }
        assertEquals("ok", result)
    }

    @Test fun closeFails() {
        val e = assertFails {
            Resource(faultyClose = true).use { "" }
        }
        assertTrue(e is IOException)
    }

    @Test fun opFailsCloseSuccess() {
        val e = assertFails {
            Resource().use { error("op fail") }
        }
        assertTrue(e is IllegalStateException)
        assertTrue(e.suppressed.isEmpty())
    }

    @Test fun opFailsCloseFails() {
        val e = assertFails {
            Resource(faultyClose = true).use { error("op fail") }
        }
        assertTrue(e is IllegalStateException)
        assertTrue(e.suppressed.single() is IOException)
    }

    @Test fun opFailsCloseFailsTwice() {
        val e = assertFails {
            Resource(faultyClose = true).use { res1 ->
                Resource(faultyClose = true).use { res2 ->
                    error("op fail")
                }
            }
        }
        assertTrue(e is IllegalStateException)
        val suppressed = e.suppressed
        assertEquals(2, suppressed.size)
        assertTrue(suppressed.all { it is IOException })
    }

}