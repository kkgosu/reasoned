package android.util

/** Only logging is a no-op; unexpected Android framework calls must still fail JVM tests. */
@Suppress("UNUSED_PARAMETER")
object Log {
    @JvmStatic
    fun d(tag: String, message: String): Int = 0

    @JvmStatic
    fun isLoggable(tag: String, level: Int): Boolean = false
}
