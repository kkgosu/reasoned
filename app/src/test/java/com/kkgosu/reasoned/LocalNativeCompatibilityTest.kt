package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.NativeResponses
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeTeaching
import com.kkgosu.reasoned.model.RuntimeSession
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in, local read-only validation. Never log content, copy a private record into fixtures or call a model. */
class LocalNativeCompatibilityTest {
    @Test fun explicitlySelectedLocalRecordProjectsWithoutChangingItsBytes() {
        val path = System.getenv("REASONED_NATIVE_LOCAL_RECORD")
        assumeTrue(!path.isNullOrBlank())
        val file = File(requireNotNull(path))
        require(file.isFile && file.length() in 1..NativeChat.MAX_BYTES.toLong())
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
        val bytes = file.readBytes()
        try {
            val native = NativeChat.parse(bytes.toString(Charsets.UTF_8))
            val session = RuntimeSession(id = native.id, goal = "[Private local compatibility check]", model = "fixture",
                nativeRecord = native, nativeDisplayIds = native.parts().mapIndexed { index, part -> part to index + 1L }.toMap())
            val projected = NativeTeaching.project(session)
            assertTrue(native.state().pending?.id == projected.question?.callId)
            assertTrue(projected.items.isNotEmpty())
            assertTrue(NativeResponses.input(native, "fixture", "api").length() > 0)
            assertTrue(hash(bytes) == hash(projected.nativeRecord!!.raw.toByteArray(Charsets.UTF_8)))
            assertTrue(hash(bytes) == hash(file.readBytes()))
            println("Local record: native/UI/wire projection passed; original bytes unchanged. No provider called.")
        } catch (error: Exception) {
            // Parser diagnostics may quote private input. Keep it out of JUnit reports and console logs.
            throw AssertionError("Local native check failed (${error.javaClass.simpleName}); details withheld.")
        }
    }
}
