import { boot, deviceStatus, downloadDeviceModel, local, cloud, getCfg, report } from './firebase.js';
import { classify } from './router.js';
import { linkDomains } from './rules.js';

const $ = (id) => document.getElementById(id);
const LABEL = { safe: 'Looks safe', suspicious: 'Suspicious', scam: 'Likely scam' };
const SOURCE = { ON_DEVICE: 'decided on-device', IN_CLOUD: 'cloud second opinion', RULES_ONLY: 'rules only' };

const ready = boot().catch((e) => console.warn('boot', e));

async function refreshDevice() {
  const s = await deviceStatus().catch(() => 'unavailable');
  $('device').textContent = { available: 'Gemini Nano: on-device ready', downloadable: 'Gemini Nano: not downloaded', downloading: 'Gemini Nano: downloading…', unavailable: 'Gemini Nano: not supported here (cloud + rules only)' }[s] ?? s;
  $('download').hidden = !['downloadable', 'downloading'].includes(s);
}

$('download').onclick = async () => {
  $('download').disabled = true;
  try {
    await downloadDeviceModel((p) => ($('device').textContent = `Gemini Nano: downloading ${Math.round(p * 100)}%`));
  } finally {
    $('download').disabled = false;
    refreshDevice();
  }
};

async function check(text) {
  await ready;
  const r = await classify(text, { local, cloud, cfg: getCfg() });
  const count = r.verdict === 'scam' ? await report(r.tactics, linkDomains(text)).catch((e) => (console.warn('community report failed:', e), null)) : null;
  return { ...r, count };
}

function render(r) {
  $('result').hidden = false;
  $('verdict').className = `v-${r.verdict}`;
  $('verdict').textContent = LABEL[r.verdict];
  const meta = document.createElement('small');
  meta.textContent = SOURCE[r.source] ?? r.source;
  $('verdict').append(meta);
  $('explain').textContent = r.explanation ?? (r.tactics.length ? 'Matched known scam patterns.' : 'No known scam patterns found.');
  $('tactics').replaceChildren(...r.tactics.map((t) => Object.assign(document.createElement('li'), { textContent: t.replaceAll('_', ' ') })));
  $('community').textContent = r.count ? `This tactic mix has been flagged ${r.count} time${r.count > 1 ? 's' : ''} by Scam Shield users.` : '';
  $('trail').replaceChildren(...r.trail.map((s) => Object.assign(document.createElement('li'), { textContent: `${s.step}: ${s.detail}` })));
  $('sentbox').hidden = false;
  $('sent').textContent = r.sent ? r.sent.text : 'Nothing. This message never left your device.';
}

async function run(text) {
  if (!text.trim()) return;
  $('check').disabled = true;
  try {
    render(await check(text));
  } finally {
    $('check').disabled = false;
  }
}

$('check').onclick = () => run($('msg').value);
$('sample').onchange = (e) => { $('msg').value = e.target.value; e.target.value = ''; };

// Right-click "Check with Scam Shield" hands text over through session storage.
async function takePending() {
  const { pending } = await chrome.storage.session.get('pending');
  if (!pending) return;
  await chrome.storage.session.remove('pending');
  $('msg').value = pending.text;
  run(pending.text);
}
chrome.storage.onChanged.addListener((c, area) => area === 'session' && c.pending?.newValue && takePending());

// Gmail content script asks the open panel for a verdict; no panel open = no check.
chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg?.type !== 'check') return;
  check(msg.text).then(
    (r) => reply({ verdict: r.verdict, explanation: r.explanation, source: r.source }),
    (e) => reply({ error: e.message }),
  );
  return true;
});

refreshDevice();
takePending();
