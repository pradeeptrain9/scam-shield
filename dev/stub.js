// Dev-only: fakes the chrome.* APIs so panel.html runs as a plain localhost page.
const store = {};
window.chrome = {
  storage: {
    session: { get: async (k) => ({ [k]: store[k] }), set: async (o) => Object.assign(store, o), remove: async (k) => delete store[k] },
    onChanged: { addListener() {} },
  },
  runtime: { onMessage: { addListener() {} } },
};
