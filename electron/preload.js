import { contextBridge, ipcRenderer } from 'electron';

// 暴露给 UI（index.html）的最小桥：UI 检测到 window.electronAPI 即显示"一键授权"
contextBridge.exposeInMainWorld('electronAPI', {
  authStart: (siteKey, accountKey, alias) => ipcRenderer.invoke('justsign:auth', { siteKey, accountKey, alias }),
  accounts: () => ipcRenderer.invoke('justsign:accounts'),
  version: () => ipcRenderer.invoke('justsign:version')
});
