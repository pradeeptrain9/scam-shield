package app.scamshield

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.OnDeviceModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val SAMPLES = listOf(
    "Digital arrest" to "This is CBI officer Sharma. A parcel with drugs was found in your name. You are under digital arrest. Install AnyDesk immediately and do not tell your family.",
    "KYC / OTP" to "Dear customer your SBI KYC will be blocked today. Share the OTP sent to 9876543210 to update: https://bit.ly/sbi-kyc",
    "Refund trick" to "Hi, I sent ₹5000 to your number by mistake, please send it back to ravi@okaxis. My child is in hospital.",
    "Job task" to "Part time job! Like YouTube videos and earn ₹3000 per day. Contact HR on WhatsApp.",
    "Lunch" to "Hey, are we still on for lunch Saturday at 1? I booked the place near your office.",
)
private val LABEL = mapOf(Verdict.SAFE to "Looks safe", Verdict.SUSPICIOUS to "Suspicious", Verdict.SCAM to "Likely scam")
private val SOURCE = mapOf("ON_DEVICE" to "decided on-device", "IN_CLOUD" to "cloud second opinion", "RULES_ONLY" to "rules only")

private data class Shown(val r: Classification, val count: Long?)

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming = sharedText(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8FB0F0)) else lightColorScheme(primary = Color(0xFF2B4A8B))) {
                Surface(Modifier.fillMaxSize()) { Screen(incoming) { incoming = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedText(intent)?.let { incoming = it }
    }

    private fun sharedText(i: Intent?): String? = when (i?.action) {
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }
}

@Composable
private fun Screen(incoming: String?, consumed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var booted by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf<OnDeviceModelStatus?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Shown?>(null) }

    suspend fun refresh() {
        device = runCatching { Shield.deviceStatus() }.getOrDefault(OnDeviceModelStatus.UNAVAILABLE)
        if (device == OnDeviceModelStatus.AVAILABLE) runCatching { Shield.warmUp() }
    }

    fun check(text: String) {
        if (text.isBlank() || busy) return
        busy = true
        scope.launch {
            try {
                val r = classify(text, local = Shield::local, cloud = Shield::cloud, cfg = Shield.config)
                val count = if (r.verdict == Verdict.SCAM) {
                    try { Shield.report(r.tactics, linkDomains(text)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        Log.w("ScamShield", "community report failed", e); null
                    }
                } else null
                shown = Shown(r, count)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { Shield.boot() }.onFailure { Log.w("ScamShield", "boot", it) }
        booted = true
        refresh()
    }
    LaunchedEffect(incoming, booted) {
        if (incoming != null && booted) { msg = incoming; consumed(); check(msg) }
    }

    Column(
        Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Scam Shield", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Paste or share a message. Gemini Nano checks it on your phone; only redacted text ever reaches the cloud.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = { scope.launch { refresh() } }, label = {
                Text(progress ?: when (device) {
                    null -> "Gemini Nano: checking…"
                    OnDeviceModelStatus.AVAILABLE -> "Gemini Nano: on-device ready"
                    OnDeviceModelStatus.DOWNLOADABLE -> "Gemini Nano: not downloaded"
                    OnDeviceModelStatus.DOWNLOADING -> "Gemini Nano: downloading…"
                    else -> "Gemini Nano: not supported here (cloud + rules only)"
                })
            })
            if (device == OnDeviceModelStatus.DOWNLOADABLE && progress == null) {
                TextButton(onClick = {
                    scope.launch {
                        Shield.download()?.collect { s ->
                            progress = when (s) {
                                is DownloadStatus.DownloadInProgress -> "Downloading… ${s.totalBytesDownloaded / 1_000_000} MB"
                                is DownloadStatus.DownloadFailed -> "Download failed"
                                is DownloadStatus.DownloadCompleted -> null
                                else -> "Starting download…"
                            }
                        }
                        refresh()
                    }
                }) { Text("Enable on-device model") }
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SAMPLES.forEach { (name, text) -> AssistChip(onClick = { msg = text; check(text) }, label = { Text(name) }) }
        }

        OutlinedTextField(
            value = msg, onValueChange = { msg = it }, label = { Text("Message to check") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )
        Button(onClick = { check(msg) }, enabled = !busy && msg.isNotBlank() && booted) {
            Text(if (busy) "Checking…" else "Check")
        }

        shown?.let { Result(it) }
    }
}

@Composable
private fun Result(s: Shown) {
    val r = s.r
    val dark = isSystemInDarkTheme()
    val color = when (r.verdict) {
        Verdict.SAFE -> if (dark) Color(0xFF5CC28D) else Color(0xFF1F7A4D)
        Verdict.SUSPICIOUS -> if (dark) Color(0xFFE0A050) else Color(0xFFA15C00)
        Verdict.SCAM -> if (dark) Color(0xFFF07067) else Color(0xFFB3261E)
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(LABEL.getValue(r.verdict), color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(SOURCE[r.source] ?: r.source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(r.explanation ?: if (r.tactics.isNotEmpty()) "Matched known scam patterns." else "No known scam patterns found.")
            if (r.tactics.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.tactics.forEach { SuggestionChip(onClick = {}, label = { Text(it.replace('_', ' ')) }) }
                }
            }
            s.count?.let { Text("This tactic mix has been flagged $it time${if (it > 1) "s" else ""} by Scam Shield users.", style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text("How this was decided", fontWeight = FontWeight.SemiBold)
            r.trail.forEachIndexed { i, step -> Text("${i + 1}. ${step.step}: ${step.detail}", style = MaterialTheme.typography.bodySmall) }
            Text("What left your device", fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                Text(r.sent?.text ?: "Nothing. This message never left your device.", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
