/**
 * electron/main.js — justsign 桌面端（macOS Intel DMG / Windows / Linux）
 *
 * v0.1.2：站点→账号两级模型 + 同款授权修复。
 *
 * 授权根因与修复（与安卓 AuthActivity 同款）：
 *   新版 new-api 前端 localStorage['new-api:auth-session'] 只是跨页签广播事件（写后即删），
 *   轮询它永远拿不到 token。真正流程：GitHub 回调到站点域 /oauth/github?code&state 后，
 *   前端 GET /api/oauth/github?code&state 直接返回 {success:true,data:{access_token,...}}。
 *   修复：onPageRendered 检测站点域 /oauth/ 路径 → 在页面上下文执行同样 fetch → 拿 bundle 落盘。
 *
 * 职责：
 *   1. 启动内置引擎（src/server.js → http://127.0.0.1:7300）
 *   2. 主窗口加载原生桌面风格 UI（electron/desktop.html，本地文件直载）
 *   3. IPC 授权桥（回调页交换）+ 站点管理 IPC
 */
import { app, BrowserWindow, ipcMain, Menu, net, session } from 'electron';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dir = dirname(fileURLToPath(import.meta.url));
if (!app.requestSingleInstanceLock()) app.quit();

let mainWin = null;
let quitting = false;

/* ---------- 引擎（数据目录指到 userData，避免写入 asar 只读包） ---------- */
let dbM = null, cfgM = null;
async function ensureMods() { // 开发树: <root>/src/；打包后: app.asar/src/ —— 双路径探测
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

/* ---------- SOCKS5 代理（state 请求走站点 API 时使用） ---------- */
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

/* ---------- 配置与账号落盘 ---------- */
async function loadCfg() { await ensureMods(); return cfgM.loadConfig(); }
function saveCfg(cfg) { cfgM.saveConfig(cfg); }

function findAccountIn(cfg, key) {
  for (const s of (cfg.sites || [])) {
    for (const a of (s.accounts || [])) {
      if (a.key === key) return { site: s, acc: a };
    }
  }
  return null;
}

function siteKeyOf(baseUrl) {
  const k = String(baseUrl || 'site').toLowerCase()
    .replace(/^https?:\/\//, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return k || 'site';
}

/* ---------- 授权桥（v0.1.2：回调页直接交换） ----------
 *  1. POST {base}/api/oauth/state {provider:'github',intent:'login'} → flow_token
 *  2. GET {base}/api/status → data.github_client_id（动态，不写死）
 *  3. 打开 github.com/login/oauth/authorize?client_id&state=flow_token&scope=user:email
 *  4. GitHub 回调站点域 /oauth/github?code&state → 注入 fetch('/api/oauth/github?code&state')
 *     → {success,data:{access_token,user}} → 写入 site.accounts[] → 通知 UI → 关窗
 */
async function runAuth(siteKey, accountKey, alias) {
  const cfg = await loadCfg();
  const site = (cfg.sites || []).find(s => s.key === siteKey);
  if (!site) return { ok: false, error: '站点不存在: ' + siteKey };
  const base = site.baseUrl.replace(/\/+$/, '');

  /* 第 0 步：账号记录先占位（别名），授权失败也不丢 */
  if (!Array.isArray(site.accounts)) site.accounts = [];
  let acc = site.accounts.find(a => a.key === accountKey);
  if (!acc) {
    acc = { key: accountKey, alias: alias || accountKey, githubAccount: null, token: null, cookie: null };
    site.accounts.push(acc);
  } else if (alias && !acc.alias) {
    acc.alias = alias;
  }
  saveCfg(cfg);

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

  /* 第 2 步：clientId 动态获取 */
  let clientId = cfg.github_client_id || 'Ov23liBGecTYSePKpXQC';
  try {
    const sess = await getAuthSession(cfg);
    const r = await net.fetch(base + '/api/status', { session: sess });
    const j = await r.json().catch(() => ({}));
    if (j?.data?.github_client_id) clientId = j.data.github_client_id;
  } catch (_) {}

  /* 第 3 步：开真实浏览器窗口走 GitHub 官方授权 */
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
    let exchangeTried = false; // 同一页签只自动交换一次，避免重复 fetch 同一 code
    const finish = (r) => {
      if (done) return;
      done = true;
      try { if (!auth.isDestroyed()) auth.close(); } catch (_) {}
      resolve(r);
    };

    /* 回调页直接交换：站点域 /oauth/ 路径时执行 */
    wc.on('did-navigate', async (_e, url) => {
      await tryExchange(url);
    });
    wc.on('did-navigate-in-page', async (_e, url) => {
      await tryExchange(url);
    });
    async function tryExchange(url) {
      if (done) return;
      let host = '', path = '';
      try { const u = new URL(url); host = u.host; path = u.pathname; } catch (_) { return; }
      if (!host || host !== new URL(base).host || !path.startsWith('/oauth/')) return;
      if (exchangeTried) return;
      exchangeTried = true;
      try {
        const js = `
          (async () => {
            const q = new URLSearchParams(location.search);
            const provider = location.pathname.split('/').pop();
            try {
              const r = await fetch('/api/oauth/' + provider + '?' + q.toString(), { credentials: 'include' });
              return JSON.stringify(await r.json());
            } catch (e) { return JSON.stringify({ success: false, message: String(e && e.message || e) }); }
          })()`;
        const raw = await wc.executeJavaScript(js, true);
        const bundle = JSON.parse(raw);
        if (!bundle || !bundle.success || !bundle.data?.access_token) {
          finish({ ok: false, error: '交换失败: ' + (bundle?.message || ('http 层未成功')) });
          return;
        }
        const login = bundle.data.user?.username || bundle.data.user?.login || null;
        const token = bundle.data.access_token;
        let cookie = '';
        try { cookie = await wc.executeJavaScript('document.cookie', true); } catch (_) {}
        const cfg2 = await loadCfg();
        const found = findAccountIn(cfg2, accountKey) || { site, acc };
        const target = found.acc || acc;
        target.githubAccount = login || target.githubAccount || null;
        target.token = token;
        if (cookie) target.cookie = cookie;
        target.updatedAt = new Date().toISOString();
        saveCfg(cfg2);
        finish({ ok: true, account: accountKey, user: login });
      } catch (e) {
        finish({ ok: false, error: '交换异常: ' + (e && e.message || e) });
      }
    }
    auth.on('closed', () => finish({ ok: false, error: '授权窗口已关闭' }));
    setTimeout(() => finish({ ok: false, error: '授权超时（5 分钟）' }), 5 * 60 * 1000);
  });
}

/* ---------- IPC ---------- */
ipcMain.handle('justsign:auth', (_e, p) => runAuth(p?.siteKey, p?.accountKey, p?.alias));

ipcMain.handle('justsign:sites', async () => {
  const cfg = await loadCfg();
  return {
    ok: true,
    sites: (cfg.sites || []).map(s => ({
      ...s,
      accounts: (s.accounts || []).map(a => ({ ...a, token: a.token ? String(a.token).slice(0, 8) + '…' : null }))
    }))
  };
});

ipcMain.handle('justsign:site-save', async (_e, b) => {
  const cfg = await loadCfg();
  if (!b || !b.baseUrl) return { ok: false, error: '需要 baseUrl' };
  let baseUrl = String(b.baseUrl).trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(baseUrl)) baseUrl = 'https://' + baseUrl;
  const checkinType = b.checkinType === 'manual' ? 'manual' : 'login';
  if (!Array.isArray(cfg.sites)) cfg.sites = [];
  if (b.key) {
    const s = cfg.sites.find(x => x.key === b.key);
    if (!s) return { ok: false, error: '站点不存在' };
    if (b.name) s.name = String(b.name).trim() || s.name;
    s.baseUrl = baseUrl;
    s.checkinType = checkinType;
  } else {
    const key = siteKeyOf(baseUrl);
    if (cfg.sites.some(x => x.key === key)) return { ok: false, error: '该站点已存在（按地址识别）' };
    cfg.sites.push({ key, name: String(b.name || '').trim() || key, baseUrl, checkinType, accounts: [] });
  }
  saveCfg(cfg);
  return { ok: true };
});

ipcMain.handle('justsign:site-delete', async (_e, siteKey) => {
  const cfg = await loadCfg();
  const before = (cfg.sites || []).length;
  cfg.sites = (cfg.sites || []).filter(s => s.key !== siteKey);
  if (cfg.sites.length === before) return { ok: false, error: '站点不存在' };
  saveCfg(cfg);
  return { ok: true };
});

ipcMain.handle('justsign:accounts', async () => {
  const cfg = await loadCfg();
  const out = [];
  for (const s of (cfg.sites || [])) {
    for (const a of (s.accounts || [])) out.push({ ...a, token: a.token ? String(a.token).slice(0, 8) + '…' : null, siteKey: s.key, siteName: s.name });
  }
  return out;
});

ipcMain.handle('justsign:version', () => ({ app: app.getVersion(), electron: process.versions.electron }));

app.on('second-instance', () => { mainWin?.show(); });
app.on('activate', () => { mainWin?.show(); });
app.whenReady().then(async () => {
  try { await startEngine(); } catch (e) { console.error('engine start fail:', e); }
  createMain();
});
app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', () => { /* 常驻后台，不退出 */ });