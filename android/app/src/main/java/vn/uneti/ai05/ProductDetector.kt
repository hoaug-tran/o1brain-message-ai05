package vn.uneti.ai05

import java.text.Normalizer

internal const val PRODUCT_POLICY_VERSION = "policy-9"

internal fun assessmentNeedsRefresh(version: String?): Boolean =
    version == null ||
        version.startsWith("en_") ||
        (version.startsWith("policy-") && !version.startsWith("$PRODUCT_POLICY_VERSION:"))

data class MessageRisk(
    val decision: String,
    val prediction: LocalPrediction,
    val reasons: List<String>,
    val language: String,
    val folderThreshold: Double,
) {
    val version: String
        get() = "$PRODUCT_POLICY_VERSION:${prediction.modelVersion}"
}

class ProductDetector(private val english: PortableClassifier) {
    fun assess(original: String): MessageRisk {
        require(inputStatus(original) == null)
        val normalized = Normalizer.normalize(original, Normalizer.Form.NFKC)
        val canonical = Normalizer.normalize(original, Normalizer.Form.NFC)
        var outsideTrainingLanguage =
            canonical.codePoints().anyMatch { Character.isLetter(it) && it > 127 }
        val prediction = english.classify(original)
        val reasons = mutableListOf<String>()
        val links =
            Regex(
                    "(?i)(?:https?://|www\\.)[^\\s<>\\[\\]]+|(?:[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?\\.)+[a-z]{2,24}(?:/[^\\s<>]*)?"
                )
                .findAll(normalized)
                .map { it.value }
                .toList()
        val disguisedScheme = Regex("(?i)\\b(?:https?|hxxps?)\\s*:\\s*/\\s*/\\s*(?=\\S)")
            .findAll(normalized).any { it.value.any { char -> char.isWhitespace() } || it.value.startsWith("hxxp", ignoreCase = true) }
        val defangedDomain = Regex("(?i)\\b[a-z0-9-]+\\s*(?:\\[\\s*(?:\\.|dot)\\s*\\]|\\(\\s*(?:\\.|dot)\\s*\\))\\s*[a-z]{2,24}\\b")
            .containsMatchIn(normalized)
        val spacedDomain = Regex("(?i)\\b(?:https?://|www\\.)[a-z0-9-]+(?:\\s+\\.\\s*|\\.\\s+)(?:[a-z0-9-]+\\.)*[a-z]{2,24}\\b")
            .containsMatchIn(normalized)
        if (disguisedScheme || defangedDomain || spacedDomain) reasons += "obfuscated_link"
        if (original.codePoints().anyMatch { Character.getType(it) == Character.FORMAT.toInt() })
            reasons += "hidden_unicode"
        if (
            links.any {
                it.contains("@") ||
                    it.contains("xn--", true) ||
                    Regex("https?://(?:[0-9]{1,3}\\.){3}[0-9]{1,3}").containsMatchIn(it)
            }
        )
            reasons += "url_structure"
        if (links.isNotEmpty() && !original.contains(Regex("\\s")) && original.length >= 32)
            reasons += "obfuscated_link"
        val scripts =
            original
                .codePoints()
                .filter { Character.isLetter(it) }
                .mapToObj { Character.UnicodeScript.of(it) }
                .toArray()
                .toSet()
        if (
            scripts.contains(Character.UnicodeScript.LATIN) &&
                (scripts.contains(Character.UnicodeScript.CYRILLIC) ||
                    scripts.contains(Character.UnicodeScript.GREEK))
        )
            reasons += "mixed_script"
        if (normalized != original && links.isNotEmpty()) reasons += "disguised_characters"
        val plain =
            Normalizer.normalize(canonical, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace('đ', 'd')
                .lowercase(java.util.Locale.ROOT)
        outsideTrainingLanguage =
            outsideTrainingLanguage ||
                Regex(
                        "\\b(xin chao|tai khoan|ma xac thuc|chuyen khoan|trung thuong|nhan thuong|khong chia se|giao hang|buu kien)\\b"
                    )
                    .containsMatchIn(plain)
        if (outsideTrainingLanguage) reasons += "language_scope"
        if (
            Regex("\\b(gui|chia se|cung cap)\\b.{0,30}\\b(otp|ma xac thuc|ma bao mat)\\b")
                .containsMatchIn(plain) &&
                !Regex("\\b(khong|dung|tuyet doi khong)\\s+(gui|chia se|cung cap)\\b")
                    .containsMatchIn(plain)
        )
            reasons += "credential_request"
        if (
            links.isNotEmpty() &&
                Regex("account|tai khoan").containsMatchIn(plain) &&
                Regex("suspend|disabled|closed|khoa|vo hieu").containsMatchIn(plain) &&
                Regex("verify|confirm|password|xac minh|mat khau").containsMatchIn(plain)
        )
            reasons += "account_threat_link"
        if (
            Regex("flash sale|limited[ -]time offer|khuyen mai|uu dai|giam gia")
                .containsMatchIn(plain) &&
                Regex("%|\\boff\\b").containsMatchIn(plain) &&
                Regex("shop now|don.t miss|before.{0,30}end|expires|ends|hom nay|ngay|chi con")
                    .containsMatchIn(plain)
        )
            reasons += "promotion_pressure"
        if (
            Regex("\\b(mom|mum|dad|me|bo|ba)\\b").containsMatchIn(plain) &&
                Regex("new number|lost my phone|new phone|so moi|mat dien thoai")
                    .containsMatchIn(plain) &&
                Regex("\\bsend\\b.{0,30}[$£€]|\\btransfer\\b|chuyen.{0,20}tien|gui.{0,20}tien")
                    .containsMatchIn(plain)
        )
            reasons += "familiar_money_request"
        if ("vi" in english.supportedLanguages)
            outsideTrainingLanguage = canonical.codePoints().anyMatch {
                Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
            }
        if (!outsideTrainingLanguage) reasons.remove("language_scope")
        if (!outsideTrainingLanguage && prediction.margin > english.warningThreshold) reasons += "content_model"
        if (
            Regex(
                    "\\b(send|share|provide|reply)\\b.{0,55}\\b(verification|security|otp|authentication)\\b.{0,20}\\bcode\\b"
                )
                .containsMatchIn(plain) &&
                !Regex("\\b(do not|don.t|never)\\s+(send|share|provide|reply)\\b")
                    .containsMatchIn(plain)
        )
            reasons += "credential_request"
        if (
            links.isNotEmpty() &&
                Regex("parcel|package|delivery|buu kien|giao hang").containsMatchIn(plain) &&
                Regex("unpaid|fee|chua thanh toan|phi").containsMatchIn(plain) &&
                Regex("update.{0,30}payment|cap nhat.{0,30}thanh toan").containsMatchIn(plain)
        )
            reasons += "delivery_payment"
        val decision =
            when {
                !outsideTrainingLanguage && prediction.margin > english.automaticThreshold -> "spam"
                reasons.any { it != "language_scope" } -> "review"
                outsideTrainingLanguage -> "unassessed"
                else -> "normal"
            }
        return MessageRisk(
            decision,
            prediction,
            reasons.distinct(),
            if (outsideTrainingLanguage) "und" else if ("vi" in english.supportedLanguages) "en_vi" else "en",
            english.automaticThreshold,
        )
    }
}

internal fun riskTitle(decision: String): Int =
    when (decision) {
        "spam" -> R.string.spam_result
        "review" -> R.string.needs_review
        "normal" -> R.string.ham_result
        "unassessed" -> R.string.language_not_assessed
        else -> R.string.not_evaluated
    }

internal fun riskReason(reason: String): Int =
    when (reason) {
        "community_reported" -> R.string.risk_community_reported
        "language_scope" -> R.string.risk_language_scope
        "account_threat_link" -> R.string.risk_account_threat_link
        "credential_request" -> R.string.risk_credential_request
        "delivery_payment" -> R.string.risk_delivery_payment
        "promotion_pressure" -> R.string.risk_promotion_pressure
        "familiar_money_request" -> R.string.risk_familiar_money_request
        "hidden_unicode" -> R.string.risk_hidden_unicode
        "url_structure" -> R.string.risk_url_structure
        "obfuscated_link" -> R.string.risk_obfuscated_link
        "mixed_script" -> R.string.risk_mixed_script
        "disguised_characters" -> R.string.risk_disguised_characters
        else -> R.string.risk_content
    }
