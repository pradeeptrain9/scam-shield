package app.scamshield

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Same cases as test/engine.test.js so the Chrome and Android engines can't drift apart.
class EngineTest {
    private val arrest = "This is CBI. A parcel with drugs found in your name. You are under digital arrest. Install AnyDesk immediately and transfer Rs 50000 to clear your name."
    private val otp = "Dear customer your KYC will be blocked today. Share the OTP sent to 9876543210 to update. https://bit.ly/x9Kq"
    private val safe = "Hi, are we still meeting for lunch on Saturday at 1?"

    private fun verdict(v: Verdict, c: Double, tactics: List<String> = emptyList()) = ModelVerdict(v, c, tactics, "x")

    @Test fun digitalArrestIsScam() {
        val r = scoreRules(arrest)
        assertEquals(Verdict.SCAM, r.verdict)
        for (t in listOf("digital_arrest", "remote_access", "authority", "urgency", "payment_request")) assertTrue(t, t in r.tactics)
    }

    @Test fun kycOtpShortLinkIsScam() {
        val r = scoreRules(otp)
        assertEquals(Verdict.SCAM, r.verdict)
        assertTrue("otp_request" in r.tactics && "risky_link" in r.tactics)
    }

    @Test fun refundTrickToUpiIsScam() {
        val r = scoreRules("Hi, I sent ₹5000 to your number by mistake, please send it back to ravi@okaxis.")
        assertEquals(Verdict.SCAM, r.verdict)
        assertTrue("refund_trick" in r.tactics && "payment_request" in r.tactics)
    }

    @Test fun ordinaryMessageIsSafe() = assertEquals(RuleResult(Verdict.SAFE, 0, emptyList()), scoreRules(safe))

    @Test fun redactStripsPiiKeepsHost() {
        val (text, counts) = redact("Call +91 98765 43210 or mail a.b@gmail.com, pay ravi@okaxis, acct 123456789012, PAN ABCDE1234F, Aadhaar 1234 5678 9012, see https://evil.in/pay?t=SECRET")
        for (leak in listOf("98765", "a.b@gmail", "ravi@okaxis", "123456789012", "ABCDE1234F", "5678", "SECRET")) assertFalse(leak, leak in text)
        assertTrue("https://evil.in/[PATH]" in text)
        assertEquals(1, counts["PHONE"])
        assertEquals(1, counts["UPI_ID"])
    }

    @Test fun linkDomainsFound() = assertEquals(listOf("bit.ly", "1.2.3.4"), linkDomains("go https://Bit.ly/a and http://1.2.3.4:80/x"))

    @Test fun confidentAgreeingLocalNeverCallsCloud() = runTest {
        var called = false
        val r = classify(safe, local = { verdict(Verdict.SAFE, 0.9) }, cloud = { called = true; error("no") })
        assertFalse(called)
        assertEquals("ON_DEVICE", r.source)
        assertNull(r.sent)
    }

    @Test fun disagreementEscalatesRedactedOnly() = runTest {
        var seen = ""
        val r = classify(otp, local = { verdict(Verdict.SAFE, 0.95) }, cloud = { seen = it; CloudResult(verdict(Verdict.SCAM, 0.9), "IN_CLOUD") })
        assertFalse("9876543210" in seen)
        assertEquals("IN_CLOUD", r.source)
        assertEquals(Verdict.SCAM, r.verdict)
    }

    @Test fun modelCannotClearRuleHit() = runTest {
        val r = classify(arrest, local = { error("no model") }, cloud = { CloudResult(verdict(Verdict.SAFE, 0.99), "IN_CLOUD") })
        assertEquals(Verdict.SCAM, r.verdict)
    }

    @Test fun everythingDownStillGivesRulesVerdict() = runTest {
        val r = classify(arrest, local = null, cloud = { error("offline") })
        assertEquals(Verdict.SCAM, r.verdict)
        assertEquals("RULES_ONLY", r.source)
    }

    @Test fun slowLocalTimesOutAndEscalates() = runTest {
        val r = classify(otp, local = { awaitCancellation() }, cloud = { CloudResult(verdict(Verdict.SCAM, 0.9), "IN_CLOUD") }, localTimeoutMs = 20)
        assertEquals("IN_CLOUD", r.source)
        assertTrue(r.trail[1].detail.contains("timed out"))
    }

    @Test fun tacticsFromSafeModelVerdictDropped() = runTest {
        val r = classify(safe, local = { verdict(Verdict.SAFE, 0.95, listOf("payment_request")) }, cloud = null)
        assertEquals(emptyList<String>(), r.tactics)
    }
}
