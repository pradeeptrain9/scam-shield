# Scam Shield

**Catches scam messages on your own device. Only redacted text ever reaches the cloud.**

▶ **Demo video (3 min):** https://www.youtube.com/watch?v=6ADIEzE3W6M

Online fraud in India keeps finding new shapes: "digital arrest" calls, fake KYC alerts,
task-based job scams. The victims are often the least technical person in the family.
Scam Shield checks a message and says *scam / suspicious / safe* in plain language, on two platforms:

- **Chrome extension**: an email open in Gmail, text you select on WhatsApp Web, Messages for
  Web, or anywhere else, or something you paste.
- **Android app**: share a message to Scam Shield, select text in any app and pick
  **Check with Scam Shield**, or paste it.

Both run the same router, rules and redactor, and both use the same Firebase backend.

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
- Android (`android/app/src/main/java/app/scamshield/`) uses the same design with the Firebase AI
  Logic Android SDK (`firebase-ai` + `firebase-ai-ondevice`):
  - `Shield.kt` runs Gemini Nano through AICore in `ONLY_ON_DEVICE` mode, and the cloud in
    `PREFER_IN_CLOUD` mode.
  - The structured output schema comes from a KSP-generated `@Generable` class, used with `generateObject()`.
  - `warmUp()` loads Nano before the first check.
  - `Rules.kt` and `Router.kt` are ports of the JS engine. `EngineTest.kt` runs the same cases as
    `test/engine.test.js`, so the two can't drift apart.
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

### Android app

1. Add an **Android app** with package `app.scamshield` to the same Firebase project.
2. Save its `google-services.json` as `android/app/google-services.json`. The file is gitignored.
3. Build and run the tests (JDK 17+, Android SDK platform 37):
   ```bash
   cd android && ./gradlew testDebugUnitTest assembleDebug
   ```
4. Install and run `app/build/outputs/apk/debug/app-debug.apk`.
5. Find the App Check debug token in logcat (`DebugAppCheckProvider`). Register it under
   **App Check > Apps > Manage debug tokens**.
6. On-device Gemini Nano needs a phone with AICore, such as a recent Pixel or Galaxy. See the
   [ML Kit Prompt API device list](https://developers.google.com/ml-kit/genai). On an emulator or an
   unsupported phone, the app runs on rules plus the cloud second opinion.

## License

Apache-2.0
