# Scam Shield

**Catches scam messages on your own computer. Only redacted text ever reaches the cloud.**

India lost thousands of crores to online fraud in 2024: "digital arrest" calls, fake KYC
alerts, task-based job scams. The victims are often the least technical person in the family.
Scam Shield is a Chrome extension that checks any message (an email open in Gmail, text you
select on WhatsApp Web, Messages for Web, or anywhere else, or something you paste) and
says *scam / suspicious / safe* in plain language.

## How Firebase AI Logic hybrid inference is used

Hybrid here is a **privacy boundary**, not just a fallback.

```
message ──► rules engine (deterministic, offline) ─┐
        └─► Gemini Nano, ON-DEVICE, raw text ──────┤
                                                   ▼
         confident AND agrees with rules? ── yes ──► verdict (nothing left the device)
                         │ no
                         ▼
           redact (phones, UPI IDs, Aadhaar, PAN, accounts, emails, link paths)
                         ▼
           cloud Gemini, PREFER_IN_CLOUD ──(offline)──► falls back on-device
                         ▼
           final = worse of (rules, model)   ← a model can raise alarm, never clear a rule hit
```

- `src/firebase.js`: two `GenerativeModel`s from `firebase/ai`.
  - `ONLY_ON_DEVICE` sees raw text and never touches the network.
  - `PREFER_IN_CLOUD` gets only redacted text. When offline it falls back to the device.
  - Both share one structured-output schema (`responseConstraint` on-device,
    `responseSchema` in cloud). `response.inferenceSource` is shown to the user.
- `src/router.js`: the second-opinion router.
- `src/rules.js`: scam signals, verdict bands, and the redactor. Unit-tested.
- The panel's **"What left your device"** box shows exactly what was sent, or "Nothing".

Other Firebase products:

| Product | Use |
|---|---|
| App Check | Debug provider. MV3 forbids remote scripts, so reCAPTCHA can't load in an extension page. |
| Remote Config | Tunes score thresholds, the on-device confidence bar and the cloud model without a release. |
| Auth (anonymous, `firebase/auth/web-extension`) | Gates community writes. |
| Firestore | Community counters per tactic combination and link host. No message text, enforced by `firestore.rules`. |

## Run it

1. Create a Firebase project.
2. In the console, go to **AI Logic** and click **Get started**. Choose the Gemini Developer API.
3. Add a **Web app** to the project and copy its config into `.env` (see `.env.example`).
4. Enable **Authentication > Anonymous** and create a **Firestore** database.
5. Deploy the rules and Remote Config template:
   ```bash
   npx firebase-tools deploy --only firestore:rules,remoteconfig
   ```
6. App Check:
   1. Register the web app.
   2. Under **App Check > Apps > Manage debug tokens**, add a token.
   3. Put that token in `.env` as `APPCHECK_DEBUG_TOKEN`.
7. Build:
   ```bash
   npm install && npm test && npm run build
   ```
8. Chrome 139+ on desktop:
   1. Enable `chrome://flags/#prompt-api-for-gemini-nano-multimodal-input` and restart Chrome.
   2. Open `chrome://extensions`, turn on **Developer mode**, click **Load unpacked**, and pick `dist/`.
9. Click the toolbar icon, then click **Enable on-device model** once to download Gemini Nano.

## License

Apache-2.0
