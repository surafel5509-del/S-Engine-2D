/*
 * LOCAL CHECK STUBS — not part of the application.
 *
 * Minimal JUnit 4 surface so the exact same `app/src/test` suite that CI runs through Gradle can
 * also be compiled and executed locally by tools/localtest.sh (see tools/LocalTest.kt for the
 * reflection based runner). Gradle uses the real JUnit jar.
 */
package org.junit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test(val expected: kotlin.reflect.KClass<out Throwable> = Throwable::class)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Before

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class After

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ignore(val value: String = "")

class AssertionError_(message: String) : AssertionError(message)

object Assert {
    @JvmStatic fun fail(message: String? = null): Nothing = throw AssertionError(message ?: "failed")
    @JvmStatic fun assertTrue(msg: String, cond: Boolean) { if (!cond) throw AssertionError(msg) }
    @JvmStatic fun assertTrue(cond: Boolean) = assertTrue("expected true", cond)
    @JvmStatic fun assertFalse(msg: String, cond: Boolean) { if (cond) throw AssertionError(msg) }
    @JvmStatic fun assertFalse(cond: Boolean) = assertFalse("expected false", cond)

    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("expected:<$expected> but was:<$actual>")
    }

    @JvmStatic fun assertEquals(msg: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$msg expected:<$expected> but was:<$actual>")
    }

    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("expected:<$expected> but was:<$actual> (delta $delta)")
    }

    @JvmStatic fun assertEquals(expected: Float, actual: Float, delta: Float) {
        if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("expected:<$expected> but was:<$actual> (delta $delta)")
    }

    @JvmStatic fun assertEquals(expected: Long, actual: Long) = assertEquals(expected as Any?, actual as Any?)
    @JvmStatic fun assertEquals(expected: Int, actual: Int) = assertEquals(expected as Any?, actual as Any?)

    @JvmStatic fun assertNotNull(value: Any?) { if (value == null) throw AssertionError("expected not null") }
    @JvmStatic fun assertNotNull(msg: String, value: Any?) { if (value == null) throw AssertionError(msg) }
    @JvmStatic fun assertNull(value: Any?) { if (value != null) throw AssertionError("expected null but was <$value>") }
    @JvmStatic fun assertSame(expected: Any?, actual: Any?) { if (expected !== actual) throw AssertionError("expected same instance") }
    @JvmStatic fun assertNotSame(expected: Any?, actual: Any?) { if (expected === actual) throw AssertionError("expected different instances") }
    @JvmStatic fun assertEquals(msg: String, expected: Float, actual: Float, delta: Float) {
        if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("$msg expected:<$expected> but was:<$actual>")
    }

    @JvmStatic fun assertEquals(msg: String, expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("$msg expected:<$expected> but was:<$actual>")
    }

    @JvmStatic fun assertArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float) {
        assertEquals("array length", expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i], delta)
    }
    @JvmStatic fun <T : Throwable> assertThrows(cls: Class<T>, block: () -> Unit): T {
        try { block() } catch (t: Throwable) {
            if (cls.isInstance(t)) return cls.cast(t)
            throw AssertionError("expected ${cls.name} but threw ${t::class.java.name}: ${t.message}")
        }
        throw AssertionError("expected ${cls.name} but nothing was thrown")
    }
}
