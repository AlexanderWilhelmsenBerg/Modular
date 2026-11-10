package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal object VerifiedCopy {
    fun transfer(input: InputStream, output: OutputStream, cancel: Cancellation, progress: (Long) -> Unit): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            if (cancel.isCanceled()) throw InterruptedException("Copy canceled")
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            hash.update(buffer, 0, count)
            progress(count.toLong())
        }
        output.flush()
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(destination: InputStream, expectedSha256: String) {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = destination.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        require(hash.digest().joinToString("") { "%02x".format(it) } == expectedSha256) { "Copied bytes failed verification" }
    }
}
