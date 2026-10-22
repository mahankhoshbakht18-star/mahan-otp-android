package com.example.codresan

import org.junit.Assert.*
import org.junit.Test

class LoanRulesTest {
    private val known = setOf("2000411")
    private fun message(code: String) = "کد تایید مربوط به سامانه ازدواج: $code"
    private fun extract(text: String, from: String = "2000411") = LoanRules.extract(text, from, known, "ازدواج")
    @Test fun actualObservedTemplate() { assertEquals("123456", extract(message("123456"))) }
    @Test fun forwardedTemplateFromMobileAccepted() { assertEquals("123456", extract(message("123456"), "09123456789")) }
    @Test fun forwardedTemplateFromOtherServiceNumberAccepted() { assertEquals("123456", extract(message("123456"), "30000000")) }
    @Test fun exactTemplateRequiredEvenForOriginalSender() { assertNull(extract("سامانه ازدواج شماره پرونده 123456")) }
    @Test fun forwardingWrapperAllowed() { assertEquals("123456", extract("پیام فوروارد شده:\n${message("123456")}")) }
    @Test fun allConfiguredLengths() { (4..8).forEach { assertEquals("1".repeat(it), extract(message("1".repeat(it)))) } }
    @Test fun persianArabicDigits() { assertEquals("123456", extract(message("۱۲۳٤٥٦"))) }
    @Test fun hamzaVariant() { assertEquals("123456", extract("کد تأیید مربوط به سامانه ازدواج: 123456")) }
    @Test fun emptyAllowlistStillAllowsExactForwardedTemplate() { assertEquals("123456", LoanRules.extract(message("123456"), "09123456789", emptySet(), "ازدواج")) }
    @Test fun personalMessageRejected() { assertNull(extract("سلام شماره 123456")) }
    @Test fun unrelatedBankOtpRejected() { assertNull(extract("بانک رمز پویا: 123456")) }
    @Test fun mixedSensitiveOtpRejected() { assertNull(extract(message("123456") + " رمز پویا: 654321")) }
    @Test fun twoCodesRejected() { assertNull(extract(message("123456") + " " + message("654321"))) }
    @Test fun tooLongRejected() { assertNull(extract(message("123456789"))) }
    @Test fun noCodeRejected() { assertNull(extract(message(""))) }
    @Test fun keywordCannotRelaxTemplate() { assertNull(LoanRules.extract("telegram code 12345", "2000411", known, "code")) }
    @Test fun longSmsRejected() { assertNull(extract(message("123456") + "ا".repeat(2001))) }
    @Test fun validNationalIdChecksum() { assertTrue(LoanRules.validNid("0012345679")); assertFalse(LoanRules.validNid("1111111111")); assertFalse(LoanRules.validNid("0012345678")) }
    @Test fun duplicateIdentifierStable() { assertEquals(LoanRules.messageId(message("123456"), "2000411", 100, 0), LoanRules.messageId(message("123456"), "2000411", 100, 0)) }
    @Test fun sameSmsOnAnotherSimIsDistinct() { assertNotEquals(LoanRules.messageId(message("123456"), "2000411", 100, 0), LoanRules.messageId(message("123456"), "2000411", 100, 1)) }
    @Test fun staleAndFutureRejected() { assertFalse(LoanRules.fresh(1000, 121000, 120)); assertFalse(LoanRules.fresh(100000, 1000, 120)); assertTrue(LoanRules.fresh(1000, 2000, 120)) }
    @Test fun iranPhoneHashNormalized() { assertEquals(LoanRules.phoneHash("+989123456789"), LoanRules.phoneHash("۰۹۱۲۳۴۵۶۷۸۹")); assertEquals("", LoanRules.phoneHash("2000411")) }
    @Test fun permanentErrorsDoNotRetry() { listOf(400,401,403,404,409,410).forEach { assertFalse(DeliveryPolicy.http(it).retry) } }
    @Test fun temporaryErrorsRetry() { listOf(408,425,429,500,503).forEach { assertTrue(DeliveryPolicy.http(it).retry) } }
    @Test fun conflictNeverReportedAsDelivery() { assertEquals("failed", DeliveryPolicy.http(409).state) }
}
