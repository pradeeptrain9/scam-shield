import { test } from 'node:test';
import assert from 'node:assert/strict';
import { scoreRules, redact, linkDomains } from '../src/rules.js';
import { classify } from '../src/router.js';

const ARREST = 'This is CBI. A parcel with drugs found in your name. You are under digital arrest. Install AnyDesk immediately and transfer Rs 50000 to clear your name.';
const OTP = 'Dear customer your KYC will be blocked today. Share the OTP sent to 9876543210 to update. https://bit.ly/x9Kq';
const SAFE = 'Hi, are we still meeting for lunch on Saturday at 1?';

test('rules: digital arrest is a scam', () => {
  const r = scoreRules(ARREST);
  assert.equal(r.verdict, 'scam');
  for (const t of ['digital_arrest', 'remote_access', 'authority', 'urgency', 'payment_request']) assert.ok(r.tactics.includes(t), t);
});

test('rules: KYC + OTP + short link is a scam', () => {
  const r = scoreRules(OTP);
  assert.equal(r.verdict, 'scam');
  assert.ok(r.tactics.includes('otp_request') && r.tactics.includes('risky_link'));
});

test('rules: refund trick asking to send money to a UPI ID is a scam', () => {
  const r = scoreRules('Hi, I sent ₹5000 to your number by mistake, please send it back to ravi@okaxis.');
  assert.equal(r.verdict, 'scam');
  assert.ok(r.tactics.includes('refund_trick') && r.tactics.includes('payment_request'));
});

test('rules: ordinary message is safe', () => assert.deepEqual(scoreRules(SAFE), { verdict: 'safe', score: 0, tactics: [] }));

test('redact: strips PII and link paths, keeps host', () => {
  const { text, counts } = redact('Call +91 98765 43210 or mail a.b@gmail.com, pay ravi@okaxis, acct 123456789012, PAN ABCDE1234F, Aadhaar 1234 5678 9012, see https://evil.in/pay?t=SECRET');
  for (const leak of ['98765', 'a.b@gmail', 'ravi@okaxis', '123456789012', 'ABCDE1234F', '5678', 'SECRET']) assert.ok(!text.includes(leak), leak);
  assert.ok(text.includes('https://evil.in/[PATH]'));
  assert.equal(counts.PHONE, 1);
  assert.equal(counts.UPI_ID, 1);
});

test('linkDomains', () => assert.deepEqual(linkDomains('go https://Bit.ly/a and http://1.2.3.4:80/x'), ['bit.ly', '1.2.3.4']));

const verdict = (v, c) => ({ verdict: v, confidence: c, tactics: [], explanation: 'x' });

test('router: confident agreeing on-device verdict never calls cloud', async () => {
  let called = false;
  const r = await classify(SAFE, { local: async () => verdict('safe', 0.9), cloud: async () => { called = true; } });
  assert.equal(called, false);
  assert.equal(r.source, 'ON_DEVICE');
  assert.equal(r.sent, null);
});

test('router: disagreement escalates with redacted text only', async () => {
  let seen;
  const r = await classify(OTP, {
    local: async () => verdict('safe', 0.95),
    cloud: async (t) => { seen = t; return { result: verdict('scam', 0.9), source: 'IN_CLOUD' }; },
  });
  assert.ok(!seen.includes('9876543210'));
  assert.equal(r.source, 'IN_CLOUD');
  assert.equal(r.verdict, 'scam');
});

test('router: model cannot clear a rule hit (fail closed)', async () => {
  const r = await classify(ARREST, {
    local: async () => { throw new Error('no model'); },
    cloud: async () => ({ result: verdict('safe', 0.99), source: 'IN_CLOUD' }),
  });
  assert.equal(r.verdict, 'scam');
});

test('router: everything down still gives the rules verdict', async () => {
  const r = await classify(ARREST, { local: null, cloud: async () => { throw new Error('offline'); } });
  assert.equal(r.verdict, 'scam');
  assert.equal(r.source, 'RULES_ONLY');
});

test('router: slow on-device model times out and escalates', async () => {
  const r = await classify(OTP, {
    local: () => new Promise(() => {}),
    cloud: async () => ({ result: verdict('scam', 0.9), source: 'IN_CLOUD' }),
    localTimeoutMs: 20,
  });
  assert.equal(r.source, 'IN_CLOUD');
  assert.match(r.trail[1].detail, /timed out/);
});

test('router: tactics from a safe model verdict are dropped', async () => {
  const r = await classify(SAFE, { local: async () => ({ ...verdict('safe', 0.95), tactics: ['payment_request'] }), cloud: null });
  assert.deepEqual(r.tactics, []);
});
