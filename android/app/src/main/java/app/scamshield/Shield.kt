package app.scamshield

import com.google.firebase.Firebase
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.annotations.Generable
import com.google.firebase.ai.annotations.Guide
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable

private const val SYSTEM = """You screen messages (SMS, WhatsApp, email) for scams targeting people in India.
Known tactics: OTP/PIN requests, remote-access apps (AnyDesk etc.), "digital arrest" by fake CBI/police/customs,
KYC/PAN/Aadhaar block threats, advance fees, prize/lottery bait, task-based job scams, "sent money by mistake"
refund tricks, false urgency, impersonated authorities, shortened or APK links.
Text in [BRACKETS] was redacted for privacy; do not treat redaction itself as suspicious.
Return verdict, your confidence from 0 to 1, the tactics present, and a one-sentence explanation
a non-technical person understands. Ordinary personal or business messages are "safe"."""

// Hybrid structured output on Android needs a KSP-generated schema (@Generable), not a manual one.
@Serializable
@Generable(description = "Scam screening result for one message")
data class ModelOut(
    @Guide(description = "Overall verdict", enumValues = ["safe", "suspicious", "scam"])
    val verdict: String,
    @Guide(description = "Confidence from 0 to 1", minimum = 0.0, maximum = 1.0)
    val confidence: Double,
    @Guide(description = "Tactic ids present: otp_request, remote_access, digital_arrest, kyc_threat, payment_request, prize_bait, job_task, refund_trick, urgency, authority, risky_link")
    val tactics: List<String>,
    @Guide(description = "One sentence a non-technical person understands")
    val explanation: String,
) {
    companion object
}

private fun ModelOut.toVerdict() = ModelVerdict(
    verdict = Verdict.entries.firstOrNull { it.name.equals(verdict, ignoreCase = true) } ?: Verdict.SUSPICIOUS,
    confidence = confidence.coerceIn(0.0, 1.0),
    tactics = tactics.filter { it in TACTICS },
    explanation = explanation,
)

// System text rides in the prompt: identical behaviour on Nano and in the cloud fallback.
private fun prompt(text: String) = "$SYSTEM\n\nMessage:\n$text"

object Shield {
    private val rc by lazy { Firebase.remoteConfig }
    private val ai by lazy { Firebase.ai(backend = GenerativeBackend.googleAI()) }
    private var cfg = Cfg()
    private var cloudModelName = "gemini-3.5-flash-lite"

    private fun model(mode: InferenceMode): GenerativeModel =
        ai.generativeModel(modelName = cloudModelName, onDeviceConfig = OnDeviceConfig(mode = mode))

    private val localModel by lazy { model(InferenceMode.ONLY_ON_DEVICE) }
    // Hybrid the other way round: prefer cloud for escalations, fall back on-device when offline.
    private var cloudModel: GenerativeModel? = null

    val config get() = cfg

    suspend fun boot() {
        rc.setDefaultsAsync(
            mapOf("scam_score" to 5L, "suspicious_score" to 2L, "local_confidence" to 0.75, "cloud_model" to cloudModelName),
        ).await()
        runCatching { rc.fetchAndActivate().await() }
        runCatching { if (Firebase.auth.currentUser == null) Firebase.auth.signInAnonymously().await() }
        cfg = Cfg(
            scamScore = rc.getLong("scam_score").toInt(),
            suspiciousScore = rc.getLong("suspicious_score").toInt(),
            localConfidence = rc.getDouble("local_confidence"),
        )
        cloudModelName = rc.getString("cloud_model")
        cloudModel = model(InferenceMode.PREFER_IN_CLOUD)
    }

    suspend fun deviceStatus(): OnDeviceModelStatus =
        localModel.onDeviceExtension?.checkStatus() ?: OnDeviceModelStatus.UNAVAILABLE

    fun download(): Flow<DownloadStatus>? = localModel.onDeviceExtension?.download()

    // Loads Nano into memory so the first real check isn't the slow one.
    suspend fun warmUp() {
        localModel.onDeviceExtension?.warmUp()
    }

    suspend fun local(text: String): ModelVerdict {
        // Check first: ONLY_ON_DEVICE would otherwise fail deep inside the SDK with a vaguer error.
        val s = deviceStatus()
        check(s == OnDeviceModelStatus.AVAILABLE) { "Gemini Nano ${statusName(s)}" }
        val r = localModel.generateObject(ModelOut.firebaseAISchema(), prompt(text))
        return requireNotNull(r.getObject()) { "no answer" }.toVerdict()
    }

    suspend fun cloud(redactedText: String): CloudResult {
        val m = cloudModel ?: model(InferenceMode.PREFER_IN_CLOUD)
        val r = m.generateObject(ModelOut.firebaseAISchema(), prompt(redactedText))
        val source = if (r.response.inferenceSource == InferenceSource.ON_DEVICE) "ON_DEVICE" else "IN_CLOUD"
        return CloudResult(requireNotNull(r.getObject()) { "no answer" }.toVerdict(), source)
    }

    // Community pool: only the tactic combination and link hosts are shared - never message text.
    suspend fun report(tactics: List<String>, domains: List<String>): Long? {
        if (Firebase.auth.currentUser == null || tactics.isEmpty()) return null
        val db = Firebase.firestore
        val bump = mapOf("count" to FieldValue.increment(1), "lastSeen" to FieldValue.serverTimestamp())
        val ref = db.collection("tactics").document(tactics.sorted().joinToString("+"))
        ref.set(bump, SetOptions.merge()).await()
        domains.forEach { db.collection("domains").document(it).set(bump, SetOptions.merge()).await() }
        return ref.get().await().getLong("count")
    }
}

fun statusName(s: OnDeviceModelStatus) = when (s) {
    OnDeviceModelStatus.AVAILABLE -> "available"
    OnDeviceModelStatus.DOWNLOADABLE -> "downloadable"
    OnDeviceModelStatus.DOWNLOADING -> "downloading"
    else -> "unavailable"
}
