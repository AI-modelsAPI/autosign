import { contextBridge, ipcRenderer } from 'electron';

// 暴露给 UI（desktop.html）的最小桥
contextBridge.exposeInMainWorld('electronAPI', {
  authStart: (siteKey, accountKey, alias) => ipcRenderer.invoke('justsign:auth', { siteKey, accountKey, alias }),
  sites: () => ipcRenderer.invoke('justsign:sites'),
  siteSave: (b) => ipcRenderer.invoke('justsign:site-save', b),
  siteDelete: (siteKey) => ipcRenderer.invoke('justsign:site-delete', siteKey),
  accounts: () => ipcRenderer.invoke('justsign:accounts'),
  version: () => ipcRenderer.invoke('justsign:version')
});