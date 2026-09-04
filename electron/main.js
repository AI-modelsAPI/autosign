/**
 * electron/main.js — justsign 桌面端（macOS Intel DMG / Windows / Linux）
 *
 * 职责：
 *   1. 启动内置引擎（src/server.js → http://127.0.0.1:7300）
 *   2. 主窗口加载原生桌面风格 UI（electron/desktop.html，本地文件直载）
 *   3. IPC 授权桥：两步 OAuth（POST /api/oauth/state 拿 flow_token →
 *      GitHub authorize?state=flow_token）→ 轮询 localStorage['new-api:auth-session']
 *      → 落盘 tokens.json
 *
 * 关键点：授权窗口是 Electron 自带完整 Chromium，真实浏览器指纹，
 *         可通过 Cloudflare Turnstile 人机验证（headless 过不了的它过得去）。
 *         授权完成后窗口即关闭，之后走纯 HTTP 接口，永不再弹窗。
 */
import { app, BrowserWindow, ipcMain, Menu, net, session } from 'electron';
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

/* ---------- 主窗口（本地桌面风格 UI） ---------- */
function createMain() {
  mainWin = new BrowserWindow({
    width: 1180, height: 780, minWidth: 940, minHeight: 600,
    title: '公益AI中转站 · 自动签到台',
    backgroundColor: '#f8fafc',
    webPreferences: { preload: join(__dir, 'preload.js'), contextIsolation: true, nodeIntegration: false }
  });
  mainWin.loadFile(join(__dir, 'desktop.html'));
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

/* ---------- SOCKS5 代理（授权 state 请求直连站点时用，与引擎 client.js 同源配置） ----------
 * 用 Electron net.fetch + 专用 session（Chromium 原生 SOCKS5 支持），
 * 不用 socks-proxy-agent：Node fetch(undici) 的 dispatcher 不接受 http.Agent。
 */
let authSession = null;
async function getAuthSession(cfg) {
  if (authSession) return authSession;
  authSession = session.fromPartition('justsign-auth-state');
  const p = cfg.proxy;
  if (p && p.enabled) {
    try { await authSession.setProxy({ mode: 'fixed_servers', protocol: 'socks5', host: p.host || '127.0.0.1', port: p.port || 10808 }); }
    catch (_) {}
  }
  return authSession;
}

/* ---------- 授权桥：两步 OAuth（新版 new-api） ----------
 *  1. POST {baseUrl}/api/oauth/state {provider:'github',intent:'login'} → data = flow_token
 *  2. 打开 https://github.com/login/oauth/authorize?client_id=..&state=flow_token&scope=user:email
 *  3. GitHub 回调站点 /oauth/github?code=..&state=..，站点前端自动交换并写 localStorage
 *  4. 轮询读 session → 落盘 tokens.json → 关窗
 */
async function runAuth(siteKey, accountKey, alias) {
  await ensureMods();
  const cfg = cfgM.loadConfig();
  const { load, save, appendLog } = dbM;
  const site = (cfg.sites || []).find(s => s.key === siteKey);
  if (!site) return { ok: false, error: '站点不存在: ' + siteKey };
  const base = site.baseUrl.replace(/\/+$/, '');

  /* 第 0 步：账号记录先占位（别名），授权失败也不丢 */
  const tokens0 = load('tokens.json') || [];
  let rec0 = tokens0.find(t => t.key === accountKey);
  if (!rec0) {
    rec0 = { key: accountKey, siteKey, alias: alias || accountKey };
    tokens0.push(rec0);
    save('tokens.json', tokens0);
  } else if (alias) {
    rec0.alias = alias;
    save('tokens.json', tokens0);
  }

  /* 第 1 步：拿 flow_token（服务端 state） */
  let flowToken = null;
  try {
    const sess = await getAuthSession(cfg);
    const resp = await net.fetch(base + '/api/oauth/state', {
      method: 'POST',
      session: sess,
      headers: {
        'User-Agent': cfg.UA || 'Mozilla/5.0',
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      },
      body: JSON.stringify({ provider: 'github', intent: 'login' })
    });
    const j = await resp.json().catch(() => ({}));
    if (resp.status === 200 && j.success) {
      flowToken = typeof j.data === 'string' ? j.data : (j.data && j.data.flow_token);
    }
    if (!flowToken) {
      return { ok: false, error: 'state 获取失败: ' + (j.message || ('http ' + resp.status)) };
    }
  } catch (e) {
    return { ok: false, error: 'state 请求异常: ' + e.message };
  }

  /* 第 2 步：开真实浏览器窗口走 GitHub 官方授权 */
  const clientId = cfg.github_client_id || 'Ov23liBGecTYSePKpXQC';
  const authUrl = 'https://github.com/login/oauth/authorize?client_id=' + encodeURIComponent(clientId)
    + '&state=' + encodeURIComponent(flowToken) + '&scope=user:email';

  const auth = new BrowserWindow({
    width: 520, height: 820, parent: mainWin, modal: true,
    title: 'GitHub 授权 — ' + (site.name || siteKey),
    webPreferences: {
      contextIsolation: true,
      // 每个账号独立持久会话：同机多 GitHub 账号互不串扰
      partition: 'persist:auth-' + siteKey + '-' + accountKey
    }
  });
  const wc = auth.webContents;
  try { wc.setUserAgent(cfg.UA); } catch (_) {}
  await auth.loadURL(authUrl);

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
        const login = s.user?.username || s.user?.login || null; // 新版 username / 老版 login
        let cookie = '';
        try { cookie = await wc.executeJavaScript('document.cookie', true); } catch (_) {}
        const tokens = load('tokens.json') || [];
        const i = tokens.findIndex(t => t.key === accountKey);
        const rec = i >= 0 ? tokens[i] : { key: accountKey, siteKey };
        Object.assign(rec, {
          siteKey,
          alias: rec.alias || alias || accountKey,
          githubAccount: login || rec.githubAccount || null,
          token, cookie,
          updatedAt: new Date().toISOString()
        });
        if (i < 0) tokens.push(rec);
        save('tokens.json', tokens);
        appendLog({ site: siteKey, account: accountKey, event: 'auth', detail: { via: 'electron', user: login } });
        finish({ ok: true, account: accountKey, user: login });
      } catch (_) { /* 页面跳转瞬间读取失败，下一轮再试 */ }
    }, AUTH_POLL_MS);
    auth.on('closed', () => finish({ ok: false, error: '授权窗口已关闭' }));
    setTimeout(() => finish({ ok: false, error: '授权超时（5 分钟）' }), 5 * 60 * 1000);
  });
}

/* ---------- IPC ---------- */
ipcMain.handle('justsign:auth', (_e, p) => runAuth(p?.siteKey, p?.accountKey, p?.alias));
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