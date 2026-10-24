package com.example.codresan

import org.junit.Assert.*
import org.junit.Test
import ir.smsforwardmanager.ui.update.DownloadPolicy
import ir.smsforwardmanager.ui.update.UpdateFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class TypedOtpTest {
    private val now = 1_000_000L
    private val nid = "0012345679"
    private fun record(type: OtpType = OtpType.REGISTRATION_OTP, id: String = nid, trusted: Boolean = true, sender: String = "") =
        OtpRecord("fixture-message", "123456", id, 0, now-1000, now+120000, otpType = type.name, trustedSender = trusted, senderHash = sender)
    private fun target(type: OtpType = OtpType.REGISTRATION_OTP, id: String = nid, approved: Boolean = false, hash: String = "") =
        OtpTarget(id, "fixture-request", hash, now-2000, now+120000, type.name, approved, true)
    private fun classify(body: String) = LoanRules.parse(body)?.type
    @Test fun T01_registrationEnglish() { assertEquals(OtpType.REGISTRATION_OTP, classify("کد تایید مربوط به سامانه ازدواج: 123456")) }
    @Test fun T02_registrationPersian() { assertEquals("123456", LoanRules.parse("کد تأیید مربوط به سامانه ازدواج: ۱۲۳۴۵۶")?.code) }
    @Test fun T03_trackingRecovery() { assertEquals(OtpType.TRACKING_RECOVERY_OTP, classify("کد تایید جهت بازیابی کد رهگیری ثبت نام تسهیلات ازدواج: 123456")) }
    @Test fun T04_deletion() { assertEquals(OtpType.REGISTRATION_DELETE_OTP, classify("کد تایید جهت حذف ثبت نام تسهیلات ازدواج: ۱۲۳۴۵۶")) }
    @Test fun T05_bareEnglishNeedsConfirmation() { assertEquals(OtpType.UNLABELED_SIX_DIGIT_CODE, classify(" 123456 ")); assertEquals("needs_confirmation", OtpCorrelation.decide(record(OtpType.UNLABELED_SIX_DIGIT_CODE), listOf(target()), now).state) }
    @Test fun T06_barePersian() { assertEquals("123456", LoanRules.parse("۱۲۳۴۵۶")?.code) }
    @Test fun T07_bareArabic() { assertEquals("123456", LoanRules.parse("١٢٣٤٥٦")?.code) }
    @Test fun T08_halfSpaces() { assertEquals(OtpType.TRACKING_RECOVERY_OTP, classify("کد‌تأیید جهت بازیابی کد رهگیری ثبت‌نام تسهیلات ازدواج: ۱۲۳۴۵۶")) }
    @Test fun T09_directionControlsAndInvisible() { assertEquals("123456", LoanRules.parse("\u2066۱۲۳\u200f۴۵۶\u2069")?.code); assertNull(LoanRules.parse("123\u0000456")) }
    @Test fun T10_multipleNumbersNeverExtracted() { assertNull(LoanRules.parse("کد تایید جهت بازیابی کد رهگیری ثبت نام تسهیلات ازدواج: 123456 شماره 98765")) }
    @Test fun T11_unrelatedBankOrService() { listOf("بانک رمز پویا 123456", "کد ورود تلگرام 123456", "شماره تماس 09123456789", "کد رهگیری شما 1234567890").forEach { assertNull(LoanRules.parse(it)) } }
    @Test fun T12_noTrustedSourceNeverAutoAssigns() { assertEquals("needs_confirmation", OtpCorrelation.decide(record(id="", trusted=false), listOf(target()), now).state) }
    @Test fun T13_simultaneousRequestsAmbiguous() { assertEquals("needs_confirmation", OtpCorrelation.decide(record(id="", trusted=false), listOf(target(),target(id="0000000019")), now).state) }
    @Test fun T14_expiredMessageNeverMatched() { assertEquals("expired", OtpCorrelation.decide(record().copy(expires=now), listOf(target()), now).state) }
    @Test fun T15_duplicateIdStableAcrossDigitVariants() { assertEquals(LoanRules.messageId("۱۲۳۴۵۶","2000411",now,0),LoanRules.messageId("123456","2000411",now,0)) }
    @Test fun T16_queuedMessageCannotMoveToNextRequest() { assertEquals("retry", OtpCorrelation.decide(record().copy(requestId="old-request"), listOf(target()), now).state) }
    @Test fun T17_backendUnavailableDoesNotChooseFakeTarget() { assertEquals("retry", OtpCorrelation.decide(record(), emptyList(), now).state) }
    @Test fun T18_otherApplicantsRecoveryCannotMatch() { assertEquals("retry", OtpCorrelation.decide(record(OtpType.TRACKING_RECOVERY_OTP), listOf(target(OtpType.TRACKING_RECOVERY_OTP,id="0000000019")), now).state) }
    @Test fun T19_deletionNeedsAuthorizedRequest() { assertEquals("retry", OtpCorrelation.decide(record(OtpType.REGISTRATION_DELETE_OTP), listOf(target(OtpType.REGISTRATION_DELETE_OTP)), now).state); assertEquals("matched", OtpCorrelation.decide(record(OtpType.REGISTRATION_DELETE_OTP), listOf(target(OtpType.REGISTRATION_DELETE_OTP,approved=true)), now).state) }
    @Test fun T20_legacyBackendOnlyRegistration() { val old = target().copy(typedProtocol=false); assertEquals("matched", OtpCorrelation.decide(record(),listOf(old),now).state); assertEquals("retry", OtpCorrelation.decide(record(OtpType.UNLABELED_SIX_DIGIT_CODE),listOf(old),now).state) }
    @Test fun unrelatedBareCannotEvenBeSelected() { assertTrue(OtpCorrelation.eligible(record(OtpType.UNLABELED_SIX_DIGIT_CODE,id="",trusted=false,sender="a".repeat(64)),listOf(target(hash="b".repeat(64))),now).isEmpty()) }
    @Test fun recoveryNeverGoesToLogin() { assertEquals("retry", OtpCorrelation.decide(record(OtpType.TRACKING_RECOVERY_OTP),listOf(target()),now).state) }
    @Test fun authorizedBareOnlyAfterExplicitConfirmation() { val r=record(OtpType.UNLABELED_SIX_DIGIT_CODE).copy(confirmed=true,confirmedType=OtpType.REGISTRATION_OTP.name,requestId="fixture-request"); assertEquals("matched", OtpCorrelation.decide(r,listOf(target()),now).state) }
    @Test fun embeddedSpacesAndLongBareAreNotCodes() { listOf("123 456", "1234567", "12345", "+123456", "۰۹۱۲۳۴۵۶۷۸۹").forEach { assertNull(LoanRules.parse(it)) } }
    @Test fun cancelledOrExpiredTargetIsNotEligible() { assertTrue(OtpCorrelation.eligible(record(),listOf(target().copy(expires=now)),now).isEmpty()) }
    @Test fun slightlyEarlierSmsOrReplacedRequestNeverAutoAssigns() {
        assertEquals("needs_confirmation",OtpCorrelation.decide(record(),listOf(target().copy(waitingSince=now)),now).state)
        assertEquals("needs_confirmation",OtpCorrelation.decide(record(),listOf(target().copy(requiresConfirmation=true)),now).state)
    }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun validDownloadIsCopiedAndVerified() { val bytes="fixture-apk-stream".toByteArray();val output=ByteArrayOutputStream();assertEquals(bytes.size.toLong(),DownloadPolicy.copy(ByteArrayInputStream(bytes),output,sha(bytes),bytes.size.toLong()));assertArrayEquals(bytes,output.toByteArray()) }
    @Test fun interruptedDownloadRejected() { val bytes="partial".toByteArray();assertTrue(runCatching{DownloadPolicy.copy(ByteArrayInputStream(bytes),ByteArrayOutputStream(),sha(bytes),bytes.size+10L)}.isFailure) }
    @Test fun wrongHashRejected() { assertTrue(runCatching{DownloadPolicy.copy(ByteArrayInputStream(byteArrayOf(1)),ByteArrayOutputStream(),"a".repeat(64))}.isFailure) }
    @Test fun downloadCancelHonored() { assertEquals("CANCELED", (runCatching{DownloadPolicy.copy(ByteArrayInputStream(byteArrayOf(1)),ByteArrayOutputStream(),"a".repeat(64),canceled={true})}.exceptionOrNull() as UpdateFailure).reason) }
    @Test fun oversizedOrEmptyFileRejected() { assertTrue(runCatching{DownloadPolicy.copy(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),"a".repeat(64),0)}.isFailure);assertTrue(runCatching{DownloadPolicy.copy(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),"a".repeat(64),DownloadPolicy.MAX_BYTES+1)}.isFailure) }
    @Test fun wrongSignatureOrPackageOrDowngradeRejected() {
        assertTrue(runCatching { DownloadPolicy.verifyIdentity("com.example.codresan","com.example.codresan",6,6,5,setOf("wrong"),setOf("right"),"right") }.isFailure)
        assertTrue(runCatching { DownloadPolicy.verifyIdentity("wrong.package","com.example.codresan",6,6,5,setOf("a"),setOf("a"),"a") }.isFailure)
        assertTrue(runCatching { DownloadPolicy.verifyIdentity("com.example.codresan","com.example.codresan",4,4,5,setOf("a"),setOf("a"),"a") }.isFailure)
    }
}
