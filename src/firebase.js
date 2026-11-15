import { initializeApp } from 'firebase/app';
import { initializeAppCheck, CustomProvider } from 'firebase/app-check';
import { getAI, getGenerativeModel, GoogleAIBackend, InferenceMode, Schema } from 'firebase/ai';
import { getAuth, signInAnonymously } from 'firebase/auth/web-extension';
import { getFirestore, doc, setDoc, getDoc, increment, serverTimestamp } from 'firebase/firestore';
import { getRemoteConfig, fetchAndActivate, getValue } from 'firebase/remote-config';
import { VERDICTS, TACTICS, DEFAULT_CFG } from './rules.js';

/* global __FIREBASE_CONFIG__, __APPCHECK_DEBUG_TOKEN__ */
const app = initializeApp(__FIREBASE_CONFIG__);

// MV3 forbids remote scripts, so reCAPTCHA can't load in an extension page. App Check runs on the
// debug provider (accepted for #BuildHybridAI); CustomProvider is never asked for a token in debug mode.
self.FIREBASE_APPCHECK_DEBUG_TOKEN = __APPCHECK_DEBUG_TOKEN__ || true;
initializeAppCheck(app, {
  provider: new CustomProvider({ getToken: () => Promise.reject(new Error('production attestation not configured')) }),
  isTokenAutoRefreshEnabled: true,
});

const ai = getAI(app, { backend: new GoogleAIBackend() });
const db = getFirestore(app);
const auth = getAuth(app);
const rc = getRemoteConfig(app);
rc.defaultConfig = {
  scam_score: DEFAULT_CFG.scamScore,
  suspicious_score: DEFAULT_CFG.suspiciousScore,
  local_confidence: DEFAULT_CFG.localConfidence,
  cloud_model: 'gemini-3.5-flash-lite',
};

const SYSTEM = `You screen messages (SMS, WhatsApp, email) for scams targeting people in India.
Known tactics: OTP/PIN requests, remote-access apps (AnyDesk etc.), "digital arrest" by fake CBI/police/customs,
KYC/PAN/Aadhaar block threats, advance fees, prize/lottery bait, task-based job scams, "sent money by mistake"
refund tricks, false urgency, impersonated authorities, shortened or APK links.
Text in [BRACKETS] was redacted for privacy; do not treat redaction itself as suspicious.
Return verdict, your confidence from 0 to 1, the tactics present, and a one-sentence explanation
a non-technical person understands. Ordinary personal or business messages are "safe".`;

const schema = Schema.object({
  properties: {
    verdict: Schema.enumString({ enum: VERDICTS }),
    confidence: Schema.number(),
    tactics: Schema.array({ items: Schema.enumString({ enum: TACTICS }) }),
    explanation: Schema.string(),
  },
});

const LANG = [{ type: 'text', languages: ['en'] }];
const onDeviceParams = {
  createOptions: {
    expectedInputs: LANG,
    expectedOutputs: LANG,
    initialPrompts: [{ role: 'system', content: [{ type: 'text', value: SYSTEM }] }],
  },
  promptOptions: { responseConstraint: schema },
};

let cfg = DEFAULT_CFG;
let localModel = getGenerativeModel(ai, { mode: InferenceMode.ONLY_ON_DEVICE, onDeviceParams });
let cloudModel;

export async function boot() {
  await Promise.allSettled([fetchAndActivate(rc), signInAnonymously(auth)]);
  cfg = {
    scamScore: getValue(rc, 'scam_score').asNumber(),
    suspiciousScore: getValue(rc, 'suspicious_score').asNumber(),
    localConfidence: getValue(rc, 'local_confidence').asNumber(),
  };
  // Hybrid the other way round: prefer cloud for escalations, fall back on-device when offline.
  cloudModel = getGenerativeModel(ai, {
    mode: InferenceMode.PREFER_IN_CLOUD,
    inCloudParams: {
      model: getValue(rc, 'cloud_model').asString(),
      systemInstruction: SYSTEM,
      generationConfig: { responseMimeType: 'application/json', responseSchema: schema },
    },
    onDeviceParams,
  });
  return cfg;
}

export const deviceStatus = async () =>
  typeof LanguageModel === 'undefined' ? 'unavailable' : LanguageModel.availability(onDeviceParams.createOptions);

// Must run from a click (Chrome user-activation rule for the model download).
export const downloadDeviceModel = (onProgress) => localModel.initializeDeviceModel(onProgress);

const parse = (result) => {
  const r = JSON.parse(result.response.text());
  return { ...r, confidence: Math.min(1, Math.max(0, Number(r.confidence) || 0)), tactics: r.tactics ?? [] };
};

export async function local(text) {
  // Check first: ONLY_ON_DEVICE would otherwise block for minutes while the model downloads.
  const s = await deviceStatus();
  if (s !== 'available') throw new Error(`Gemini Nano ${s}`);
  return parse(await localModel.generateContent(text));
}

export async function cloud(redactedText) {
  const result = await cloudModel.generateContent(redactedText);
  // SDK reports 'in_cloud' / 'on_device'; router and UI use upper case.
  return { result: parse(result), source: String(result.response.inferenceSource ?? 'IN_CLOUD').toUpperCase() };
}

export const getCfg = () => cfg;

// Community pool: only the tactic combination and link hosts are shared - never message text.
export async function report(tactics, domains) {
  if (!auth.currentUser || !tactics.length) return null;
  const key = [...tactics].sort().join('+');
  const ref = doc(db, 'tactics', key);
  const bump = { count: increment(1), lastSeen: serverTimestamp() };
  await Promise.all([
    setDoc(ref, bump, { merge: true }),
    ...domains.map((d) => setDoc(doc(db, 'domains', d), bump, { merge: true })),
  ]);
  return (await getDoc(ref)).data()?.count ?? null;
}
