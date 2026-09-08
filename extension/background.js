/**
 * background.js — autosign 浏览器扩展引擎（MV3 Service Worker）
 *
 * 与桌面端（electron/main.js + src/server.js）同源的数据模型与接口语义：
 *   config = { sites: [{ key, name, baseUrl, checkinType: 'manual'|'login', accounts: [{ key, alias, githubAccount, token, siteUserId }] }] }
 *   siteKey = baseUrl 规整化（与 Store.siteKeyOf / server.js 同规则）
 *
 * MV3 生命周期适配（kuozan 踩坑经验）：
 *   - SW 30s 空闲即被杀 → 一切状态落 chrome.storage.local，任何定时用 chrome.alarms，禁 setInterval
 *   - 每日签到 alarm（默认 03:05 Asia/Shanghai 语义，由时区偏移换算成 after 精确时刻）
 *   - 状态快照 persist，popup 打开时若无快照才现拉
 *
 * 授权（沿用 Electron 已验证的 new-api 路线）：
 *   startAuth(siteKey, accKey)：开站点域标签页 → 截 /api/user/oauth 回调（access_token）
 *   → 读页面 localStorage.user 拿站点数字 ID（New-Api-User 头需要）→ 落库 → 关标签页。
 */

const DEFAULT_CFG = {
  schedule: { enabled: false, hour: 3, minute: 5 },
  notify: true,
  sites: [] // 用户手动添加，不写死
};

const ALARM_DAILY = 'autosign-daily';
const ALARM_KEEPALIVE = 'autosign-keepalive';

/* ================= 存储 ================= */
async function loadCfg() {
  const { config } = await chrome.storage.local.get('config');
  if (!config) return structuredClone(DEFAULT_CFG);
  return { ...structuredClone(DEFAULT_CFG), ...config };
}
async function saveCfg(cfg) { await chrome.storage.local.set({ config: cfg }); }

async function appendLog(event, detail) {
  const { logs } = await chrome.storage.local.get('logs');
  const arr = logs || [];
  arr.push({ time: Date.now(), event, detail: detail == null ? '' : (typeof detail === 'string' ? detail : JSON.stringify(detail)) });
  await chrome.storage.local.set({ logs: arr.slice(-300) });
}

/* ================= 站点/账号工具（同 server.js 规则） ================= */
function siteKeyOf(baseUrl) {
  const k = String(baseUrl || 'site').toLowerCase()
    .replace(/^https?:\/\//, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return k || 'site';
}
function findAccount(cfg, key) {
  for (const s of (cfg.sites || []))
    for (const a of (s.accounts || []))
      if (a.key === key) return { site: s, acc: a };
  return null;
}
function maskCfg(cfg) {
  return {
    ...cfg,
    sites: (cfg.sites || []).map(s => ({
      ...s,
      accounts: (s.accounts || []).map(a => ({ ...a, token: a.token ? String(a.token).slice(0, 8) + '…' : null }))
    }))
  };
}

/* ================= 站点请求（fetch 版 SiteClient） ================= */
async function siteCall(site, acc, method, path, body) {
  const headers = { 'Accept': 'application/json' };
  if (acc?.token) headers['Authorization'] = 'Bearer ' + acc.token;
  if (acc?.siteUserId) headers['New-Api-User'] = String(acc.siteUserId);
  const opt = { method, headers };
  if (body !== undefined) { headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  try {
    const r = await fetch((site.baseUrl || '').replace(/\/+$/, '') + path, opt);
    let data = null;
    try { data = await r.json(); } catch (_) { /* 非 JSON 响应保留 null */ }
    return { ok: r.status >= 200 && r.status < 300, status: r.status, data };
  } catch (e) {
    return { ok: false, status: 0, error: e.message };
  }
}

/* ================= 业务：状态 / 签到 ================= */
async function accountStatus(site, acc) {
  const [self, st] = await Promise.all([
    siteCall(site, acc, 'GET', '/api/user/self'),
    siteCall(site, acc, 'GET', '/api/status')
  ]);
  const d = self?.data?.data || {};
  const unit = st?.data?.data?.quota_per_unit || 500000;
  return {
    ok: true, account: acc.key, site: site.name, siteKey: site.key,
    http: self.status, authorized: self.status === 200,
    availableUSD: +(Number(d.quota || 0) / unit).toFixed(2),
    usedUSD: +(Number(d.used_quota || 0) / unit).toFixed(2),
    todayUsed: d.today_used_quota != null ? +(d.today_used_quota / unit).toFixed(4) : null,
    user: d.display_name || d.username || d.github_id || null
  };
}

async function doCheckin(cfg, accKey) {
  const x = findAccount(cfg, accKey);
  if (!x) return { ok: false, error: '账号不存在' };
  const { site, acc } = x;
  if (site.checkinType !== 'manual') {
    const r = await siteCall(site, acc, 'GET', '/api/user/self');
    await appendLog('login-refresh', `${site.key}/${acc.key} http=${r.status}`);
    return { ok: true, skipped: true, message: '该站点登录即签到，已刷新保活', http: r.status };
  }
  const r = await siteCall(site, acc, 'POST', '/api/user/checkin');
  await appendLog('checkin', `${site.key}/${acc.key} http=${r.status} code=${r.data?.code}`);
  return { ok: true, http: r.status, data: r.data };
}

async function runAllCheckins(reason) {
  const cfg = await loadCfg();
  for (const site of (cfg.sites || [])) {
    for (const acc of (site.accounts || [])) {
      if (!acc.token) continue;
      try { await doCheckin(cfg, acc.key); } catch (e) { await appendLog('checkin-error', `${acc.key}: ${e.message}`); }
    }
  }
  await appendLog('batch-done', `reason=${reason}`);
}

/* ================= 每日调度（alarms，SW 被杀也能醒） ================= */
function nextDailyAlarmTime(hour, minute) {
  const now = new Date();
  // 用户本地时区即预期时区（扩展跟随浏览器），按本地 hour:minute 触发
  const t = new Date(now.getFullYear(), now.getMonth(), now.getDate(), hour, minute, 0, 0);
  if (t <= now) t.setDate(t.getDate() + 1);
  return t;
}
async function syncDailyAlarm(cfg) {
  await chrome.alarms.clear(ALARM_DAILY);
  if (!cfg.schedule?.enabled) return;
  const t = nextDailyAlarmTime(cfg.schedule.hour ?? 3, cfg.schedule.minute ?? 5);
  chrome.alarms.create(ALARM_DAILY, { when: t.getTime(), periodInMinutes: 24 * 60 });
  await appendLog('alarm-set', t.toLocaleString('zh-CN', { hour12: false }));
}

chrome.alarms.onAlarm.addListener(al => {
  if (al.name === ALARM_DAILY) runAllCheckins('daily');
  if (al.name === ALARM_KEEPALIVE) appendLog('keepalive', 'sw-awake'); // 轻：仅写一条日志证明存活
});

chrome.runtime.onInstalled.addListener(async () => {
  const cfg = await loadCfg();
  await syncDailyAlarm(cfg);
  // 30 分钟保活探测：验证 SW 唤醒路径（正式版可关）
  chrome.alarms.create(ALARM_KEEPALIVE, { periodInMinutes: 30 });
});

/* ================= 授权：标签页截回调 ================= */
const AUTH_TABS = {}; // tagKey -> tabId
async function startAuth(siteKey, accKey) {
  const cfg = await loadCfg();
  const site = (cfg.sites || []).find(s => s.key === siteKey);
  if (!site) return { ok: false, error: '站点不存在' };
  const tab = await chrome.tabs.create({ url: site.baseUrl, active: true });
  AUTH_TABS[siteKey + '|' + accKey] = tab.id;
  await appendLog('auth-start', `${siteKey}/${accKey} tab=${tab.id}`);
  return { ok: true, tabId: tab.id };
}

chrome.tabs.onUpdated.addListener(async (tabId, info, tab) => {
  if (info.status !== 'complete' || !tab.url) return;
  const { pendingAuth } = await chrome.storage.session.get('pendingAuth');
  if (!pendingAuth) return;
  const u = new URL(tab.url);
  // new-api 前端回调页：站点域任意页加载完成后尝试从 localStorage 取会话
  if (!u.origin) return;
  try {
    const [{ result }] = await chrome.scripting.executeScript({
      target: { tabId },
      func: () => {
        try {
          const sess = localStorage.getItem('new-api:auth-session');
          const userRaw = localStorage.getItem('user');
          return { sess, userRaw, href: location.href };
        } catch (e) { return { error: String(e) }; }
      }
    });
    let token = null, userId = null;
    if (result?.sess) { try { token = JSON.parse(result.sess)?.access_token || null; } catch (_) {} }
    if (result?.userRaw) { try { userId = JSON.parse(result.userRaw)?.id ?? null; } catch (_) {} }
    if (token) {
      const cfg = await loadCfg();
      const x = findAccount(cfg, pendingAuth.accKey);
      if (x) {
        x.acc.token = token;
        if (userId != null) x.acc.siteUserId = String(userId);
        x.acc.updatedAt = new Date().toISOString();
        await saveCfg(cfg);
        await appendLog('auth-ok', `${pendingAuth.siteKey}/${pendingAuth.accKey} user=${userId}`);
        await chrome.storage.session.remove('pendingAuth');
        chrome.tabs.remove(tabId).catch(() => {});
      }
    }
  } catch (_) { /* 页面不可注入（如 chrome:// 域），忽略 */ }
});

async function beginAuth(siteKey, accKey) {
  await chrome.storage.session.set({ pendingAuth: { siteKey, accKey, at: Date.now() } });
  return startAuth(siteKey, accKey);
}

/* ================= 消息路由（popup / options → SW） ================= */
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  (async () => {
    try {
      switch (msg?.type) {
        case 'getConfig': {
          const cfg = await loadCfg();
          return sendResponse({ ok: true, config: maskCfg(cfg) });
        }
        case 'siteSave': {
          const cfg = await loadCfg();
          const b = msg.site || {};
          let baseUrl = String(b.baseUrl || '').trim().replace(/\/+$/, '');
          if (!baseUrl) return sendResponse({ ok: false, error: '需要 baseUrl' });
          if (!/^https?:\/\//i.test(baseUrl)) baseUrl = 'https://' + baseUrl;
          if (!Array.isArray(cfg.sites)) cfg.sites = [];
          if (b.key) {
            const s = cfg.sites.find(x => x.key === b.key);
            if (!s) return sendResponse({ ok: false, error: '站点不存在' });
            s.name = b.name || s.name; s.baseUrl = baseUrl;
            s.checkinType = b.checkinType === 'manual' ? 'manual' : 'login';
          } else {
            const key = siteKeyOf(baseUrl);
            if (cfg.sites.some(x => x.key === key)) return sendResponse({ ok: false, error: '该站点已存在（按地址识别）' });
            cfg.sites.push({ key, name: b.name || key, baseUrl, checkinType: b.checkinType === 'manual' ? 'manual' : 'login', accounts: [] });
          }
          await saveCfg(cfg);
          await appendLog('site-save', b.name || baseUrl);
          return sendResponse({ ok: true });
        }
        case 'siteDelete': {
          const cfg = await loadCfg();
          cfg.sites = (cfg.sites || []).filter(s => s.key !== msg.key);
          await saveCfg(cfg);
          await appendLog('site-delete', msg.key);
          return sendResponse({ ok: true });
        }
        case 'accountSave': {
          const cfg = await loadCfg();
          const site = (cfg.sites || []).find(s => s.key === msg.siteKey);
          if (!site) return sendResponse({ ok: false, error: 'siteKey 无效' });
          if (!Array.isArray(site.accounts)) site.accounts = [];
          const key = msg.key || 'acc_' + Date.now();
          let a = site.accounts.find(x => x.key === key);
          if (!a) { a = { key, alias: msg.alias || key, githubAccount: null, token: null, siteUserId: null }; site.accounts.push(a); }
          if (msg.alias != null && String(msg.alias).trim()) a.alias = String(msg.alias).trim();
          a.updatedAt = new Date().toISOString();
          await saveCfg(cfg);
          return sendResponse({ ok: true, key });
        }
        case 'accountDelete': {
          const cfg = await loadCfg();
          for (const s of (cfg.sites || [])) s.accounts = (s.accounts || []).filter(a => a.key !== msg.key);
          await saveCfg(cfg);
          await appendLog('account-delete', msg.key);
          return sendResponse({ ok: true });
        }
        case 'status': {
          const cfg = await loadCfg();
          const x = findAccount(cfg, msg.key);
          if (!x) return sendResponse({ ok: false, error: '账号不存在' });
          const r = await accountStatus(x.site, x.acc);
          return sendResponse(r);
        }
        case 'checkin': {
          const cfg = await loadCfg();
          return sendResponse(await doCheckin(cfg, msg.key));
        }
        case 'auth': {
          return sendResponse(await beginAuth(msg.siteKey, msg.key));
        }
        case 'logs': {
          const { logs } = await chrome.storage.local.get('logs');
          return sendResponse({ ok: true, data: (logs || []).slice().reverse() });
        }
        case 'settingsSave': {
          const cfg = await loadCfg();
          if (msg.settings?.schedule) cfg.schedule = msg.settings.schedule;
          if (msg.settings?.notify != null) cfg.notify = !!msg.settings.notify;
          await saveCfg(cfg);
          await syncDailyAlarm(cfg);
          return sendResponse({ ok: true });
        }
        default:
          return sendResponse({ ok: false, error: '未知消息: ' + msg?.type });
      }
    } catch (e) {
      sendResponse({ ok: false, error: e.message });
    }
  })();
  return true; // 异步 sendResponse
});
