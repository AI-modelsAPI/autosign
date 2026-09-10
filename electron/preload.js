import { contextBridge, ipcRenderer } from 'electron';
// 暴露给 UI（desktop.html）的最小桥
contextBridge.exposeInMainWorld('electronAPI', {
  authStart: (siteKey, accountKey, alias) => ipcRenderer.invoke('justsign:auth', { siteKey, accountKey, alias }),
  sites: () => ipcRenderer.invoke('justsign:sites'),
  siteSave: (b) => ipcRenderer.invoke('justsign:site-save', b),
  siteDelete: (siteKey) => ipcRenderer.invoke('justsign:site-delete', siteKey),
  accounts: () => ipcRenderer.invoke('justsign:accounts'),
  version: () => ipcRenderer.invoke('justsign:version'),
  /* v0.2.0：对齐安卓 v0.6.x */
  catalog: () => ipcRenderer.invoke('justsign:catalog'),
  catalogImport: (keys) => ipcRenderer.invoke('justsign:catalog-import', { keys }),
  statusAll: () => ipcRenderer.invoke('justsign:status-all'),
  checkinAll: () => ipcRenderer.invoke('justsign:checkin-all'),
  affTransfer: (accountKey) => ipcRenderer.invoke('justsign:aff-transfer', { accountKey }),
  tokenList: (accountKey) => ipcRenderer.invoke('justsign:token-list', { accountKey }),
  tokenCreate: (accountKey, name) => ipcRenderer.invoke('justsign:token-create', { accountKey, name }),
  tokenDelete: (accountKey, id) => ipcRenderer.invoke('justsign:token-delete', { accountKey, id }),
  settingsGet: () => ipcRenderer.invoke('justsign:settings-get'),
  settingsSave: (b) => ipcRenderer.invoke('justsign:settings-save', b),
});