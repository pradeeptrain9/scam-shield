import { scoreRules, redact, worse, DEFAULT_CFG } from './rules.js';

// Second-opinion router.
// 1. Rules score the raw text (deterministic, offline, instant).
// 2. Gemini Nano judges the RAW text on-device - nothing leaves the machine.
// 3. Only if Nano is unavailable, unsure, or disagrees with the rules does the message
//    escalate - and then only the REDACTED text goes to the cloud model.
// 4. Final verdict = the worse of rules and model: a model can raise alarm, never clear a rule hit.
//
// local(text)  -> {verdict, confidence, tactics, explanation}         (throws if no on-device model)
// cloud(text)  -> {result: {...same}, source: 'IN_CLOUD'|'ON_DEVICE'} (hybrid; may fall back on-device offline)
// Nano's first prompt loads the model into memory (can take a minute); don't hold the user hostage.
const deadline = (p, ms) => {
  let id;
  const timer = new Promise((_, no) => { id = setTimeout(() => no(new Error(`timed out after ${ms / 1000}s`)), ms); });
  return Promise.race([p, timer]).finally(() => clearTimeout(id));
};

export async function classify(text, { local, cloud, cfg = DEFAULT_CFG, localTimeoutMs = 20000 }) {
  const rules = scoreRules(text, cfg);
  const trail = [{ step: 'rules', detail: `score ${rules.score} → ${rules.verdict}`, tactics: rules.tactics }];

  let model = null;
  let source = 'RULES_ONLY';
  if (local) {
    try {
      const t0 = Date.now();
      model = await deadline(local(text), localTimeoutMs);
      source = 'ON_DEVICE';
      trail.push({ step: 'on-device', detail: `${model.verdict} (confidence ${model.confidence.toFixed(2)}, ${((Date.now() - t0) / 1000).toFixed(1)}s)` });
    } catch (e) {
      trail.push({ step: 'on-device', detail: `unavailable: ${e.message}` });
    }
  }

  const settledLocally = model && model.confidence >= cfg.localConfidence && model.verdict === rules.verdict;
  let sent = null;
  if (!settledLocally && cloud) {
    trail.push({ step: 'escalate', detail: !model ? 'no on-device verdict' : model.verdict !== rules.verdict ? 'model and rules disagree' : 'low confidence' });
    sent = redact(text);
    try {
      const r = await cloud(sent.text);
      model = r.result;
      source = r.source;
      trail.push({ step: r.source === 'IN_CLOUD' ? 'cloud' : 'on-device (cloud unreachable)', detail: `${model.verdict} (confidence ${model.confidence.toFixed(2)})` });
    } catch (e) {
      trail.push({ step: 'cloud', detail: `failed: ${e.message}` });
    }
  }

  return {
    verdict: worse(rules.verdict, model?.verdict ?? 'safe'),
    confidence: model?.confidence ?? null,
    // Small models sometimes tag tactics on a 'safe' answer; only trust tags that back an alarm.
    tactics: [...new Set([...rules.tactics, ...(model && model.verdict !== 'safe' ? model.tactics : [])])],
    explanation: model?.explanation ?? null,
    source,
    rules,
    sent, // exactly what left the device, or null
    trail,
  };
}
