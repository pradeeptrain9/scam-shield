chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true });

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({ id: 'check', title: 'Check with Scam Shield', contexts: ['selection'] });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  // open() must run synchronously inside the click handler to keep the user gesture.
  chrome.sidePanel.open({ windowId: tab.windowId });
  chrome.storage.session.set({ pending: { text: info.selectionText, at: Date.now() } });
});
