// Deterministic layer: scam signals, verdict bands, and PII redaction.
// Runs before any model and can never be overruled downward by one (fail closed).

export const VERDICTS = ['safe', 'suspicious', 'scam'];
export const TACTICS = [
  'otp_request', 'remote_access', 'digital_arrest', 'kyc_threat', 'payment_request',
  'prize_bait', 'job_task', 'refund_trick', 'urgency', 'authority', 'risky_link',
];

const SIGNALS = [
  { id: 'otp_request', weight: 3, re: /\b(share|send|tell|forward|give)\b[^.\n]{0,40}\b(otp|one[- ]time password|verification code|cvv|upi pin|pin)\b|\b(otp|cvv|upi pin)\b[^.\n]{0,30}\b(share|send|tell)\b/i },
  { id: 'remote_access', weight: 3, re: /\b(anydesk|teamviewer|quick ?support|rustdesk|screen ?shar(e|ing))\b/i },
  { id: 'digital_arrest', weight: 3, re: /\b(digital arrest|arrest warrant|money laundering|narcotics|drugs? (were )?(found|parcel)|illegal parcel)\b/i },
  { id: 'kyc_threat', weight: 2, re: /\b(kyc|pan( card)?|aadhaa?r)\b[^.\n]{0,50}\b(update|expir\w*|block\w*|suspend\w*|deactivat\w*)\b/i },
  { id: 'payment_request', weight: 2, re: /\b(processing fee|registration fee|security deposit|clearance fee)\b|\b(pay|transfer|deposit|send)\b[^.\n]{0,30}(₹|\brs\.?\s?\d|\binr\b|\bupi\b|\b[\w.-]+@(ok\w+|ybl|paytm|upi|axl|ibl|apl)\b)/i },
  { id: 'prize_bait', weight: 2, re: /\b(lottery|lucky draw|you have won|winner|cashback|reward|prize)\b[^.\n]{0,60}\b(claim|collect|redeem)\b/i },
  { id: 'job_task', weight: 2, re: /\b(work from home|part[- ]time)\b[^.\n]{0,60}\b(earn|per day|daily)\b|\b(like|subscribe to)\b[^.\n]{0,20}\b(videos?|channels?)\b[^.\n]{0,40}\b(earn|paid|₹|rs)\b|\btask\b[^.\n]{0,30}\bcommission\b/i },
  { id: 'refund_trick', weight: 3, re: /\b(sent|transferred|credited)\b[^.\n]{0,30}\b(by mistake|wrongly|accidentally)\b|\bwrong(ly)? (sent|transferred)\b/i },
  { id: 'urgency', weight: 1, re: /\b(immediately|urgent(ly)?|within \d+ ?(hours?|hrs?|minutes?|mins?)|today only|last chance|final notice|will be (blocked|suspended|closed|deactivated))\b/i },
  { id: 'authority', weight: 1, re: /\b(cbi|police|customs|enforcement directorate|rbi|income tax|trai|cyber ?cell|supreme court|high court|fedex|dhl)\b/i },
  { id: 'risky_link', weight: 2, re: /\b(bit\.ly|tinyurl\.com|cutt\.ly|t\.co|is\.gd|rb\.gy)\/|https?:\/\/\d{1,3}(\.\d{1,3}){3}|\.apk\b|http:\/\/(?!localhost)/i },
];

export const DEFAULT_CFG = { scamScore: 5, suspiciousScore: 2, localConfidence: 0.75 };

export function scoreRules(text, cfg = DEFAULT_CFG) {
  const hits = SIGNALS.filter((s) => s.re.test(text));
  const score = hits.reduce((n, s) => n + s.weight, 0);
  const verdict = score >= cfg.scamScore ? 'scam' : score >= cfg.suspiciousScore ? 'suspicious' : 'safe';
  return { verdict, score, tactics: hits.map((s) => s.id) };
}

export const worse = (a, b) => (VERDICTS.indexOf(a) >= VERDICTS.indexOf(b) ? a : b);

// Order matters: specific IDs before generic digit runs.
const REDACTIONS = [
  ['EMAIL', /\b[\w.+-]+@[\w-]+\.[\w.-]+\b/g],
  ['UPI_ID', /\b[\w.-]{2,}@[a-z]{2,}\b/gi],
  ['PAN', /\b[A-Z]{5}\d{4}[A-Z]\b/g],
  ['IFSC', /\b[A-Z]{4}0[A-Z0-9]{6}\b/g],
  ['AADHAAR', /\b\d{4}[ -]\d{4}[ -]\d{4}\b/g],
  ['PHONE', /(\+?91[ -]?)?\b[6-9]\d{4}[ -]?\d{5}\b/g],
  ['NUMBER', /\b\d{9,18}\b/g],
];

// Keeps a link's host (the scam signal) and drops its path/query (may carry tokens or IDs).
// shortcut: personal names are not redacted (needs NER); add an on-device NER pass if judges probe it.
export function redact(text) {
  const counts = {};
  let out = text.replace(/\bhttps?:\/\/([^\s/?#]+)[^\s]*/gi, (_, host) => `https://${host}/[PATH]`);
  for (const [label, re] of REDACTIONS) {
    out = out.replace(re, () => {
      counts[label] = (counts[label] ?? 0) + 1;
      return `[${label}]`;
    });
  }
  return { text: out, counts };
}

export const linkDomains = (text) =>
  [...new Set([...text.matchAll(/\bhttps?:\/\/([^\s/?#:]+)/gi)].map((m) => m[1].toLowerCase()))];
