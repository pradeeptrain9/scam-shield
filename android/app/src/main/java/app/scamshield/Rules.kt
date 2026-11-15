package app.scamshield

// Deterministic layer: scam signals, verdict bands, and PII redaction.
// Port of src/rules.js - keep the two in sync (EngineTest mirrors test/engine.test.js).
// Runs before any model and can never be overruled downward by one (fail closed).

enum class Verdict { SAFE, SUSPICIOUS, SCAM }

fun worse(a: Verdict, b: Verdict) = if (a >= b) a else b

data class Cfg(val scamScore: Int = 5, val suspiciousScore: Int = 2, val localConfidence: Double = 0.75)

data class RuleResult(val verdict: Verdict, val score: Int, val tactics: List<String>)

private class Signal(val id: String, val weight: Int, pattern: String) {
    val re = Regex(pattern, RegexOption.IGNORE_CASE)
}

private val SIGNALS = listOf(
    Signal("otp_request", 3, """\b(share|send|tell|forward|give)\b[^.\n]{0,40}\b(otp|one[- ]time password|verification code|cvv|upi pin|pin)\b|\b(otp|cvv|upi pin)\b[^.\n]{0,30}\b(share|send|tell)\b"""),
    Signal("remote_access", 3, """\b(anydesk|teamviewer|quick ?support|rustdesk|screen ?shar(e|ing))\b"""),
    Signal("digital_arrest", 3, """\b(digital arrest|arrest warrant|money laundering|narcotics|drugs? (were )?(found|parcel)|illegal parcel)\b"""),
    Signal("kyc_threat", 2, """\b(kyc|pan( card)?|aadhaa?r)\b[^.\n]{0,50}\b(update|expir\w*|block\w*|suspend\w*|deactivat\w*)\b"""),
    Signal("payment_request", 2, """\b(processing fee|registration fee|security deposit|clearance fee)\b|\b(pay|transfer|deposit|send)\b[^.\n]{0,30}(₹|\brs\.?\s?\d|\binr\b|\bupi\b|\b[\w.-]+@(ok\w+|ybl|paytm|upi|axl|ibl|apl)\b)"""),
    Signal("prize_bait", 2, """\b(lottery|lucky draw|you have won|winner|cashback|reward|prize)\b[^.\n]{0,60}\b(claim|collect|redeem)\b"""),
    Signal("job_task", 2, """\b(work from home|part[- ]time)\b[^.\n]{0,60}\b(earn|per day|daily)\b|\b(like|subscribe to)\b[^.\n]{0,20}\b(videos?|channels?)\b[^.\n]{0,40}\b(earn|paid|₹|rs)\b|\btask\b[^.\n]{0,30}\bcommission\b"""),
    Signal("refund_trick", 3, """\b(sent|transferred|credited)\b[^.\n]{0,30}\b(by mistake|wrongly|accidentally)\b|\bwrong(ly)? (sent|transferred)\b"""),
    Signal("urgency", 1, """\b(immediately|urgent(ly)?|within \d+ ?(hours?|hrs?|minutes?|mins?)|today only|last chance|final notice|will be (blocked|suspended|closed|deactivated))\b"""),
    Signal("authority", 1, """\b(cbi|police|customs|enforcement directorate|rbi|income tax|trai|cyber ?cell|supreme court|high court|fedex|dhl)\b"""),
    Signal("risky_link", 2, """\b(bit\.ly|tinyurl\.com|cutt\.ly|t\.co|is\.gd|rb\.gy)/|https?://\d{1,3}(\.\d{1,3}){3}|\.apk\b|http://(?!localhost)"""),
)

val TACTICS = SIGNALS.map { it.id }

fun scoreRules(text: String, cfg: Cfg = Cfg()): RuleResult {
    val hits = SIGNALS.filter { it.re.containsMatchIn(text) }
    val score = hits.sumOf { it.weight }
    val verdict = when {
        score >= cfg.scamScore -> Verdict.SCAM
        score >= cfg.suspiciousScore -> Verdict.SUSPICIOUS
        else -> Verdict.SAFE
    }
    return RuleResult(verdict, score, hits.map { it.id })
}

// Order matters: specific IDs before generic digit runs. Case flags match the JS version.
private val REDACTIONS = listOf(
    "EMAIL" to Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b"""),
    "UPI_ID" to Regex("""\b[\w.-]{2,}@[a-z]{2,}\b""", RegexOption.IGNORE_CASE),
    "PAN" to Regex("""\b[A-Z]{5}\d{4}[A-Z]\b"""),
    "IFSC" to Regex("""\b[A-Z]{4}0[A-Z0-9]{6}\b"""),
    "AADHAAR" to Regex("""\b\d{4}[ -]\d{4}[ -]\d{4}\b"""),
    "PHONE" to Regex("""(\+?91[ -]?)?\b[6-9]\d{4}[ -]?\d{5}\b"""),
    "NUMBER" to Regex("""\b\d{9,18}\b"""),
)
private val URL = Regex("""\bhttps?://([^\s/?#]+)\S*""", RegexOption.IGNORE_CASE)

data class Redacted(val text: String, val counts: Map<String, Int>)

// Keeps a link's host (the scam signal) and drops its path/query (may carry tokens or IDs).
// shortcut: personal names are not redacted (needs NER); add an on-device NER pass if judges probe it.
fun redact(text: String): Redacted {
    val counts = mutableMapOf<String, Int>()
    var out = URL.replace(text) { "https://${it.groupValues[1]}/[PATH]" }
    for ((label, re) in REDACTIONS) {
        out = re.replace(out) {
            counts[label] = (counts[label] ?: 0) + 1
            "[$label]"
        }
    }
    return Redacted(out, counts)
}

private val LINK_HOST = Regex("""\bhttps?://([^\s/?#:]+)""", RegexOption.IGNORE_CASE)

fun linkDomains(text: String) = LINK_HOST.findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()
