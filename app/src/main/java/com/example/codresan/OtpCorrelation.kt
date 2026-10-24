package com.example.codresan

enum class OtpType { REGISTRATION_OTP, TRACKING_RECOVERY_OTP, REGISTRATION_DELETE_OTP, UNLABELED_SIX_DIGIT_CODE }
data class ParsedOtp(val code: String, val type: OtpType)
data class CorrelationDecision(val state: String, val target: OtpTarget? = null)

/** Pure routing policy. A bare code never enters automatic delivery. */
object OtpCorrelation {
    fun eligible(record: OtpRecord, targets: List<OtpTarget>, now: Long): List<OtpTarget> = targets.filter { target ->
        target.requestId.isNotBlank() && now < target.expires && now < record.expires &&
            record.received <= now + 30_000 && record.received + 5_000 >= target.waitingSince &&
            (record.requestId.isBlank() || record.requestId == target.requestId) &&
            (record.nid.isBlank() || record.nid == target.nid) &&
            (record.otpType == OtpType.UNLABELED_SIX_DIGIT_CODE.name || record.otpType == target.otpType) &&
            (record.otpType == OtpType.REGISTRATION_OTP.name || target.typedProtocol) &&
            (record.otpType != OtpType.UNLABELED_SIX_DIGIT_CODE.name ||
                (record.trustedSender && record.nid.isNotBlank() && record.nid == target.nid) ||
                (record.senderHash.isNotBlank() && record.senderHash == target.senderHash)) &&
            (target.otpType != OtpType.REGISTRATION_DELETE_OTP.name || target.deleteApproved)
    }
    fun decide(record: OtpRecord, targets: List<OtpTarget>, now: Long): CorrelationDecision {
        if (record.code.isEmpty() || now >= record.expires) return CorrelationDecision("expired")
        val candidates = eligible(record, targets, now)
        if (candidates.isEmpty()) return CorrelationDecision("retry")
        val bound = candidates.singleOrNull { it.requestId == record.requestId && it.nid == record.nid }
        if (record.confirmed && bound != null && record.deliveryType == bound.otpType) return CorrelationDecision("matched", bound)
        if (record.otpType == OtpType.UNLABELED_SIX_DIGIT_CODE.name) return CorrelationDecision("needs_confirmation")
        val certain = candidates.filter { target ->
            (record.trustedSender && record.nid.isNotBlank() && record.nid == target.nid) ||
                (record.senderHash.isNotBlank() && target.senderHash == record.senderHash)
        }.singleOrNull()
        return if (certain != null && !certain.requiresConfirmation && record.received >= certain.waitingSince)
            CorrelationDecision("matched", certain) else CorrelationDecision("needs_confirmation")
    }
}
