package com.example.codresan

import java.security.MessageDigest
import java.util.Locale

/** Conservative marriage-service parser. It classifies but never decides a recipient. */
object LoanRules {
    const val DEFAULT_PATTERN = "کد\\s+ت(?:ایید|أیید|ائید)\\s+مربوط\\s+به\\s+سامانه\\s+ازدواج\\s*[:：]?\\s*([0-9]{4,8})(?![0-9])"
    fun digits(text: String): String = text.map { c ->
        when (c) { in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); else -> c }
    }.joinToString("")
    fun normalized(text: String): String = digits(text).replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), " ")
        .replace('أ', 'ا').replace('إ', 'ا').replace("تائید", "تایید")
        .replace(Regex("[\\p{M}ـ]"), "").lowercase(Locale.ROOT).replace(Regex("[\\s\\u00A0]+"), " ").trim()
    fun sender(text: String): String = normalized(text).replace(" ", "").removePrefix("+")
    fun senders(raw: String): Set<String> = raw.split(',', '،', '\n', ';').map(::sender).filter(String::isNotBlank).toSet()
    fun validNid(raw: String): Boolean {
        val nid = digits(raw).trim()
        if (!nid.matches(Regex("[0-9]{10}")) || nid.toSet().size == 1) return false
        val remainder = nid.take(9).mapIndexed { i, c -> (c - '0') * (10 - i) }.sum() % 11
        return (nid.last() - '0') == if (remainder < 2) remainder else 11 - remainder
    }
    fun extract(body: String, sender: String, allowed: Set<String>, keyword: String,
                min: Int = 4, max: Int = 8): String? {
        val result = parse(body, min, max) ?: return null
        // Compatibility API: never return recovery, deletion or bare codes to legacy login callers.
        return result.code.takeIf { result.type == OtpType.REGISTRATION_OTP && keyword.isNotBlank() && normalized(body).contains(normalized(keyword)) }
    }
    fun parse(body: String, min: Int = 4, max: Int = 8): ParsedOtp? {
        if (body.length > 2000 || min !in 4..8 || max !in min..8) return null
        if (body.any { (it.isISOControl() && it !in "\r\n\t") || it == '\uFFFD' }) return null
        val bare = digits(body).replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "").trim()
        if (Regex("[0-9]{6}").matches(bare)) return ParsedOtp(bare, OtpType.UNLABELED_SIX_DIGIT_CODE)
        val text = normalized(body)
        if (!text.contains("ازدواج") || !Regex("سامانه|تسهیلات|وام").containsMatchIn(text)) return null
        if (Regex("رمز پویا|خرید|برداشت|انتقال وجه|بانک|تلگرام|واتساپ|اینستاگرام|تغییر رمز|telegram|whatsapp|password reset").containsMatchIn(text)) return null
        val numbers = Regex("[0-9]+").findAll(text).map { it.value }.toList()
        if (numbers.size != 1 || numbers.single().length !in min..max) return null
        val patterns = listOf(
            OtpType.TRACKING_RECOVERY_OTP to "کد تایید (?:جهت|برای) بازیابی کد رهگیری ثبت ?نام (?:تسهیلات|وام) ازدواج",
            OtpType.REGISTRATION_DELETE_OTP to "کد تایید (?:جهت|برای) حذف ثبت ?نام (?:تسهیلات|وام) ازدواج",
            OtpType.REGISTRATION_OTP to "کد تایید مربوط به سامانه ازدواج",
            OtpType.REGISTRATION_OTP to "کد تایید (?:جهت|برای) (?:ورود|ثبت ?نام|تایید شماره) (?:در |به )?(?:سامانه |تسهیلات |وام )+ازدواج"
        )
        val matches = patterns.mapNotNull { (type, pattern) ->
            Regex("$pattern *[:：]? *([0-9]{4,8})(?![0-9])").find(text)?.let { ParsedOtp(it.groupValues[1], type) }
        }.distinct()
        return matches.singleOrNull()
    }
    fun messageId(body: String, sender: String, time: Long, sim: Int): String =
        // Preserve the 2.1.0 event-id algorithm for messages delivered during an upgrade.
        MessageDigest.getInstance("SHA-256").digest("${legacyIdText(body)}|${LoanRules.sender(sender)}|$time|$sim".toByteArray())
            .joinToString("") { "%02x".format(it) }
    private fun legacyIdText(text: String) = digits(text).replace('ي', 'ی').replace('ك', 'ک')
        .replace('\u200c', ' ').lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    fun fresh(received: Long, now: Long, ttl: Int): Boolean = received <= now + 30_000 && now - received < ttl * 1000L
    fun phoneHash(value: String): String {
        var phone = sender(value)
        if (phone.startsWith("0098")) phone = "0" + phone.drop(4)
        else if (phone.startsWith("98")) phone = "0" + phone.drop(2)
        if (!phone.matches(Regex("09[0-9]{9}"))) return ""
        return MessageDigest.getInstance("SHA-256").digest(phone.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
