// Gmail: when an email body opens, ask the side panel for a verdict and pin a banner above it.
// shortcut: relies on Gmail's `div.a3s` body class; breaks if Gmail renames it - update the selector then.
const COLORS = { scam: '#b3261e', suspicious: '#a15c00' };
const seen = new WeakSet();

async function scan(body) {
  seen.add(body);
  const text = body.innerText.trim().slice(0, 4000);
  if (text.length < 20) return;
  let r;
  try {
    r = await chrome.runtime.sendMessage({ type: 'check', text });
  } catch {
    return; // side panel closed: nothing listening
  }
  if (!r || r.error || r.verdict === 'safe') return;
  const banner = document.createElement('div');
  banner.setAttribute('role', 'alert');
  banner.style.cssText = `background:${COLORS[r.verdict]};color:#fff;padding:10px 14px;border-radius:8px;margin:0 0 12px;font:14px/1.4 system-ui,sans-serif`;
  const head = document.createElement('strong');
  head.textContent = r.verdict === 'scam' ? 'Scam Shield: likely scam. ' : 'Scam Shield: suspicious. ';
  banner.append(head, r.explanation ?? 'Matches known scam patterns. Do not share OTPs or pay anyone.');
  body.before(banner);
}

new MutationObserver(() => {
  document.querySelectorAll('div.a3s').forEach((b) => seen.has(b) || scan(b));
}).observe(document.body, { childList: true, subtree: true });
