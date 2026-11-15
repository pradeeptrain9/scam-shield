package app.scamshield

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

// Second-opinion router. Port of src/router.js:
// 1. Rules score the raw text (deterministic, offline, instant).
// 2. Gemini Nano judges the RAW text on-device - nothing leaves the phone.
// 3. Only if Nano is unavailable, unsure, or disagrees with the rules does the message
//    escalate - and then only the REDACTED text goes to the cloud model.
// 4. Final verdict = the worse of rules and model: a model can raise alarm, never clear a rule hit.

data class ModelVerdict(val verdict: Verdict, val confidence: Double, val tactics: List<String>, val explanation: String)

data class CloudResult(val result: ModelVerdict, val source: String) // source: IN_CLOUD | ON_DEVICE (offline fallback)

data class Step(val step: String, val detail: String)

data class Classification(
    val verdict: Verdict,
    val confidence: Double?,
    val tactics: List<String>,
    val explanation: String?,
    val source: String, // ON_DEVICE | IN_CLOUD | RULES_ONLY
    val rules: RuleResult,
    val sent: Redacted?, // exactly what left the device, or null
    val trail: List<Step>,
)

private fun ModelVerdict.describe() = "${verdict.name.lowercase()} (confidence ${"%.2f".format(confidence)}"

suspend fun classify(
    text: String,
    local: (suspend (String) -> ModelVerdict)?,
    cloud: (suspend (String) -> CloudResult)?,
    cfg: Cfg = Cfg(),
    localTimeoutMs: Long = 20_000, // Nano's first prompt can be slow; don't hold the user hostage.
): Classification {
    val rules = scoreRules(text, cfg)
    val trail = mutableListOf(Step("rules", "score ${rules.score} → ${rules.verdict.name.lowercase()}"))

    var model: ModelVerdict? = null
    var source = "RULES_ONLY"
    if (local != null) {
        try {
            val t0 = System.currentTimeMillis()
            model = withTimeoutOrNull(localTimeoutMs) { local(text) } ?: error("timed out after ${localTimeoutMs / 1000}s")
            source = "ON_DEVICE"
            trail += Step("on-device", "${model.describe()}, ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)}s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trail += Step("on-device", "unavailable: ${e.message}")
        }
    }

    val settledLocally = model != null && model.confidence >= cfg.localConfidence && model.verdict == rules.verdict
    var sent: Redacted? = null
    if (!settledLocally && cloud != null) {
        val why = when {
            model == null -> "no on-device verdict"
            model.verdict != rules.verdict -> "model and rules disagree"
            else -> "low confidence"
        }
        trail += Step("escalate", why)
        sent = redact(text)
        try {
            val r = cloud(sent.text)
            model = r.result
            source = r.source
            trail += Step(if (r.source == "IN_CLOUD") "cloud" else "on-device (cloud unreachable)", "${r.result.describe()})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trail += Step("cloud", "failed: ${e.message}")
        }
    }

    // Small models sometimes tag tactics on a 'safe' answer; only trust tags that back an alarm.
    val modelTactics = if (model != null && model.verdict != Verdict.SAFE) model.tactics else emptyList()
    return Classification(
        verdict = worse(rules.verdict, model?.verdict ?: Verdict.SAFE),
        confidence = model?.confidence,
        tactics = (rules.tactics + modelTactics).distinct(),
        explanation = model?.explanation,
        source = source,
        rules = rules,
        sent = sent,
        trail = trail,
    )
}
