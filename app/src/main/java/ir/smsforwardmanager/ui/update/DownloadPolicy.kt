package ir.smsforwardmanager.ui.update

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class UpdateFailure(val reason: String) : Exception(reason)
object DownloadPolicy {
    const val MAX_BYTES = 100L * 1024 * 1024
    fun copy(input: InputStream, output: OutputStream, expectedSha: String, length: Long = -1,
             canceled: () -> Boolean = { false }, progress: (Long, Long) -> Unit = { _, _ -> }): Long {
        if (!Regex("[a-fA-F0-9]{64}").matches(expectedSha) || length > MAX_BYTES || length == 0L) throw UpdateFailure("INVALID_FILE")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32768); var total = 0L
        while (true) {
            if (canceled()) throw UpdateFailure("CANCELED")
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_BYTES) throw UpdateFailure("INVALID_FILE")
            output.write(buffer, 0, count); digest.update(buffer, 0, count); progress(total, length)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (total == 0L || (length >= 0 && total != length) || !actual.equals(expectedSha, true)) throw UpdateFailure("INVALID_FILE")
        return total
    }
    fun verifyIdentity(packageName: String, expectedPackage: String, version: Long, expectedVersion: Int,
                       installedVersion: Int, signers: Set<String>, installedSigners: Set<String>, expectedCertificate: String) {
        if (packageName != expectedPackage || version != expectedVersion.toLong() || version <= installedVersion)
            throw UpdateFailure("WRONG_VERSION")
        if (signers.isEmpty() || signers != installedSigners || expectedCertificate.lowercase() !in signers)
            throw UpdateFailure("WRONG_SIGNATURE")
    }
}
