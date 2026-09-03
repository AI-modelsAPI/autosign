/**
 * electron/main.js — justsign 桌面端（macOS Intel DMG / Windows / Linux）
 *
 * 职责：
 *   1. 启动内置引擎（src/server.js → http://127.0.0.1:7300）
 *   2. 主窗口加载引擎 UI（点关闭=隐藏到后台，调度不停）
 *   3. IPC 授权桥：弹出真实（非无头）授权窗口 → GitHub OAuth →
 *      轮询读 localStorage['new-api:auth-session'] → 落盘 tokens.json
 *
 * 关键点：授权窗口是 Electron 自带完整 Chromium，真实浏览器指纹，
 *         可通过 Cloudflare Turnstile 人机验证（headless 过不了的它过得去）。
 *         授权完成后浏览器即关闭，之后走纯 HTTP 接口，永不再弹窗。
 */
import { app, BrowserWindow, ipcMain, Menu } from 'electron';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dir = dirname(fileURLToPath(import.meta.url));
if (!app.requestSingleInstanceLock()) app.quit();

let mainWin = null;
let quitting = false;
const AUTH_POLL_MS = 1200;

/* ---------- 引擎（数据目录指到 userData，避免写入 asar 只读包） ---------- */
let dbM = null, cfgM = null;
async function ensureMods() { // 开发树: <root>/src/db.js；打包后: app.asar/src/db.js —— 双路径探测
  if (dbM && cfgM) return;
  for (const b of ['../src/', './src/']) {
    try {
      dbM = await import(b + 'db.js');
      cfgM = await import(b + 'config.js');
      return;
    } catch (_) {}
  }
  throw new Error('引擎模块加载失败（src/db.js 未找到）');
}
async function startEngine() {
  const userData = app.getPath('userData');
  process.env.JUSTSIGN_DATA = join(userData, 'data');
  process.env.JUSTSIGN_CONFIG = join(userData, 'config.json');
  for (const b of ['../src/', './src/']) {
    try { await import(b + 'server.js'); return; } catch (_) {}
  }
  throw new Error('引擎 server.js 加载失败');
}

/* ---------- 主窗口 ---------- */
function createMain() {
  mainWin = new BrowserWindow({
    width: 1180, height: 780, minWidth: 860, minHeight: 560,
    title: '公益AI中转站 · 自动签到台',
    webPreferences: { preload: join(__dir, 'preload.js'), contextIsolation: true, nodeIntegration: false }
  });
  mainWin.loadURL('http://127.0.0.1:7300');
  // 点关闭 = 隐藏到后台，引擎继续跑（全后台原则）
  mainWin.on('close', (e) => {
    if (!quitting) { e.preventDefault(); mainWin.hide(); }
  });
  const menu = Menu.buildFromTemplate([{
    label: 'justsign',
    submenu: [
      { label: '显示主窗口', click: () => mainWin?.show() },
      { type: 'separator' },
      { label: '真正退出（停止后台签到）', click: () => { quitting = true; app.quit(); } }
    ]
  }]);
  Menu.setApplicationMenu(menu);
}

/* ---------- 授权桥（真实浏览器窗口，非无头，过 Turnstile） ---------- */
async function runAuth(siteKey, accountKey) {
  await ensureMods();
  const cfg = cfgM.loadConfig();
  const { load, save, appendLog } = dbM;
  const site = (cfg.sites || []).find(s => s.key === siteKey);
  if (!site) return { ok: false, error: '站点不存在: ' + siteKey };

  const auth = new BrowserWindow({
    width: 500, height: 780, parent: mainWin, modal: true,
    title: 'GitHub 授权 — ' + site.name,
    webPreferences: {
      contextIsolation: true,
      // 每个账号独立持久会话：同机多 GitHub 账号互不串扰
      partition: 'persist:auth-' + siteKey + '-' + accountKey
    }
  });
  const wc = auth.webContents;
  try { wc.setUserAgent(cfg.UA); } catch (_) {}
  await auth.loadURL(site.baseUrl.replace(/\/+$/, '') + '/api/oauth/github');

  return await new Promise((resolve) => {
    let done = false;
    const finish = (r) => {
      if (done) return;
      done = true; clearInterval(timer);
      try { if (!auth.isDestroyed()) auth.close(); } catch (_) {}
      resolve(r);
    };
    const timer = setInterval(async () => {
      if (done || auth.isDestroyed()) return;
      try {
        const raw = await wc.executeJavaScript("localStorage.getItem('new-api:auth-session')", true);
        if (!raw) return; // 授权还没完成，继续轮询
        const s = JSON.parse(raw);
        const token = s.access_token || s.accessToken;
        if (!token) return finish({ ok: false, error: 'session 中无 access_token' });
        let cookie = '';
        try { cookie = await wc.executeJavaScript('document.cookie', true); } catch (_) {}
        const tokens = load('tokens.json') || [];
        const i = tokens.findIndex(t => t.key === accountKey);
        const rec = i >= 0 ? tokens[i] : { key: accountKey, siteKey };
        Object.assign(rec, {
          siteKey,
          githubAccount: s.user?.login || rec.githubAccount || null,
          token, cookie,
          updatedAt: new Date().toISOString()
        });
        if (i < 0) tokens.push(rec);
        save('tokens.json', tokens);
        appendLog({ site: siteKey, account: accountKey, event: 'auth', detail: { via: 'electron', user: s.user?.login || null } });
        finish({ ok: true, account: accountKey, user: s.user?.login || null });
      } catch (_) { /* 页面跳转瞬间读取失败，下一轮再试 */ }
    }, AUTH_POLL_MS);
    auth.on('closed', () => finish({ ok: false, error: '授权窗口已关闭' }));
    setTimeout(() => finish({ ok: false, error: '授权超时（5 分钟）' }), 5 * 60 * 1000);
  });
}

ipcMain.handle('justsign:auth', (_e, p) => runAuth(p?.siteKey, p?.accountKey));
ipcMain.handle('justsign:accounts', async () => { await ensureMods(); return dbM.load('tokens.json') || []; });
ipcMain.handle('justsign:version', () => ({ app: app.getVersion(), electron: process.versions.electron }));

app.on('second-instance', () => { mainWin?.show(); });
app.on('activate', () => { mainWin?.show(); });
app.whenReady().then(async () => {
  try { await startEngine(); } catch (e) { console.error('engine start fail:', e); }
  createMain();
});
app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', () => { /* 常驻后台，不退出 */ });
