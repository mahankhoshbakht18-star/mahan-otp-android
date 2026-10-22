package ir.smsforwardmanager

import ir.smsforwardmanager.data.remote.model.AppVersionDto
import org.junit.Assert.*
import org.junit.Test

class AppVersionDtoTest {
    private fun version(code: Int = 4, minimum: Int = 1) = AppVersionDto(code, "2.1.0", minimum,
        "https://otp.mahanvip.ir/downloads/mahan-otp-2.1.0.apk", "release", "a".repeat(64))
    @Test fun currentVersionDoesNotPrompt() { assertFalse(version().hasUpdate(4)); assertFalse(version().isMandatory(4)) }
    @Test fun olderRemoteNeverDowngrades() { assertFalse(version().hasUpdate(5)) }
    @Test fun optionalUpdateForSupportedVersion() { assertTrue(version().hasUpdate(3)); assertFalse(version().isMandatory(3)) }
    @Test fun mandatoryUpdateBelowMinimum() { assertTrue(version(minimum = 3).isMandatory(2)) }
    @Test fun minimumBoundaryRemainsOptional() { assertFalse(version(minimum = 3).isMandatory(3)) }
    @Test fun validReleaseMetadataAccepted() { assertEquals(4, version().validate().versionCode) }
    @Test fun invalidMinimumRejected() { assertTrue(runCatching { version(minimum = 5).validate() }.isFailure) }
    @Test fun noInvalidChecksum() { assertTrue(runCatching { version().copy(apkSha256 = "missing").validate() }.isFailure) }
    @Test fun untrustedDownloadUrlsRejected() {
        listOf("http://otp.mahanvip.ir/downloads/a.apk", "https://evil.test/downloads/a.apk",
            "https://otp.mahanvip.ir.evil.test/downloads/a.apk", "https://otp.mahanvip.ir@evil.test/downloads/a.apk",
            "https://otp.mahanvip.ir:444/downloads/a.apk", "https://otp.mahanvip.ir/downloads/../secret.apk",
            "https://otp.mahanvip.ir/downloads/a.apk?token=secret", "https://otp.mahanvip.ir/downloads/a.apk#x",
            "file:///downloads/a.apk", "https://otp.mahanvip.ir/downloads/a.html").forEach {
            assertFalse(it, AppVersionDto.trustedDownloadUrl(it))
        }
    }
    @Test fun excessiveReleaseNotesRejected() { assertTrue(runCatching { version().copy(releaseNotes = "a".repeat(4001)).validate() }.isFailure) }
}
