// Gmail: when an email body opens, ask the side panel for a verdict and pin a banner above it.
// shortcut: relies on Gmail's `div.a3s` body class; breaks if Gmail renames it - update the selector then.
const COLORS = { scam: '#b3261e', suspicious: '#a15c00' };
const done = new WeakSet(); // got a verdict (or too short) - never re-check
const busy = new WeakSet(); // check in flight

async function scan(body) {
  const text = body.innerText.trim().slice(0, 4000);
  if (text.length < 20) return done.add(body);
  busy.add(body);
  let r;
  try {
    r = await chrome.runtime.sendMessage({ type: 'check', text });
  } catch {
    // Side panel closed (nobody listening) or extension reloaded: leave unchecked, retry later.
  } finally {
    busy.delete(body);
  }
  if (!r || r.error) return;
  done.add(body);
  console.debug('[Scam Shield]', r.verdict, r.source);
  if (r.verdict === 'safe') return;
  const banner = document.createElement('div');
  banner.setAttribute('role', 'alert');
  banner.style.cssText = `background:${COLORS[r.verdict]};color:#fff;padding:10px 14px;border-radius:8px;margin:0 0 12px;font:14px/1.4 system-ui,sans-serif`;
  const head = document.createElement('strong');
  head.textContent = r.verdict === 'scam' ? 'Scam Shield: likely scam. ' : 'Scam Shield: suspicious. ';
  banner.append(head, r.explanation ?? 'Matches known scam patterns. Do not share OTPs or pay anyone.');
  body.before(banner);
}

function sweep() {
  // Extension reloaded: this copy of the script is orphaned; stop and let the refreshed tab take over.
  if (!chrome.runtime?.id) return stop();
  document.querySelectorAll('div.a3s').forEach((b) => done.has(b) || busy.has(b) || scan(b));
}

const observer = new MutationObserver(sweep);
observer.observe(document.body, { childList: true, subtree: true });
// Also retry on a timer, so an email opened before the side panel gets checked once the panel opens.
const timer = setInterval(sweep, 3000);
function stop() {
  observer.disconnect();
  clearInterval(timer);
}
sweep();
