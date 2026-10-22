package com.example.codresan

import java.security.MessageDigest
import java.util.Locale

/** Only the observed marriage-service template is accepted, including forwarded SMS. */
object LoanRules {
    const val DEFAULT_PATTERN = "کد\\s+ت(?:ایید|أیید|ائید)\\s+مربوط\\s+به\\s+سامانه\\s+ازدواج\\s*[:：]?\\s*([0-9]{4,8})(?![0-9])"
    fun digits(text: String): String = text.map { c ->
        when (c) { in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); else -> c }
    }.joinToString("")
    fun normalized(text: String): String = digits(text).replace('ي', 'ی').replace('ك', 'ک')
        .replace('\u200c', ' ').lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
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
        val text = normalized(body)
        if (!text.contains("ازدواج") || !Regex("سامانه|تسهیلات|وام").containsMatchIn(text)) return null
        if (keyword.isBlank() || !text.contains(normalized(keyword))) return null
        if (Regex("رمز پویا|خرید|برداشت|انتقال وجه|تلگرام|واتساپ|اینستاگرام|بازیابی|تغییر رمز|telegram|whatsapp|password reset").containsMatchIn(text)) return null
        if (min !in 4..8 || max !in min..8 || text.length > 2000) return null
        val codes = Regex(DEFAULT_PATTERN, RegexOption.IGNORE_CASE).findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }.filter { it.length in min..max }.toSet()
        val allCodes = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])").findAll(text).map { it.value }.toSet()
        if (allCodes != codes) return null
        return codes.singleOrNull()
    }
    fun messageId(body: String, sender: String, time: Long, sim: Int): String =
        MessageDigest.getInstance("SHA-256").digest("${normalized(body)}|${LoanRules.sender(sender)}|$time|$sim".toByteArray())
            .joinToString("") { "%02x".format(it) }
    fun fresh(received: Long, now: Long, ttl: Int): Boolean = received <= now + 30_000 && now - received < ttl * 1000L
    fun phoneHash(value: String): String {
        var phone = sender(value)
        if (phone.startsWith("0098")) phone = "0" + phone.drop(4)
        else if (phone.startsWith("98")) phone = "0" + phone.drop(2)
        if (!phone.matches(Regex("09[0-9]{9}"))) return ""
        return MessageDigest.getInstance("SHA-256").digest(phone.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
