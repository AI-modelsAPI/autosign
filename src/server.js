/**
 * server.js — justsign 引擎 HTTP 服务：静态 UI + REST API + cron 调度
 * v0.1.2：站点→账号两级模型（config.sites[].accounts[]），站点可增删改；
 *         旧版扁平 tokens.json 自动迁移；设置走 /api/settings（避免整包 config 覆盖 token）。
 * 用法: node src/server.js   （默认 127.0.0.1:7300）
 */
import { createServer } from 'http';
import { readFileSync, statSync, renameSync } from 'fs';
import { join, dirname, extname } from 'path';
import { fileURLToPath } from 'url';
import { loadConfig, saveConfig } from './config.js';
import { load, save, appendLog, D as DATA_DIR } from './db.js';
import { SiteClient } from './client.js';
import cron from 'node-cron';

const __dir = dirname(fileURLToPath(import.meta.url));
const STATIC = __dir;
const PORT = parseInt(process.env.JUSTSIGN_PORT || '7300', 10);
const HOST = process.env.JUSTSIGN_HOST || '127.0.0.1';
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

const cfg = loadConfig();

/* ---------- 站点/账号工具（与安卓 Store.siteKeyOf 同规则） ---------- */
function siteKeyOf(baseUrl) {
  const k = String(baseUrl || 'site').toLowerCase()
    .replace(/^https?:\/\//, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return k || 'site';
}

function findAccount(key) {
  for (const s of (cfg.sites || [])) {
    for (const a of (s.accounts || [])) {
      if (a.key === key) return { site: s, acc: a };
    }
  }
  return null;
}

function maskToken(t) { return t ? String(t).slice(0, 8) + '…' : null; }

function maskSite(s) {
  return { ...s, accounts: (s.accounts || []).map(a => ({ ...a, token: maskToken(a.token) })) };
}

function allAccountsFlat() {
  const out = [];
  for (const s of (cfg.sites || [])) {
    for (const a of (s.accounts || [])) out.push({ ...a, token: maskToken(a.token), siteKey: s.key, siteName: s.name });
  }
  return out;
}

/* ---------- 旧版扁平 tokens.json 一次性迁移 ---------- */
function migrateLegacy() {
  try {
    const legacy = load('tokens.json');
    if (!Array.isArray(legacy) || !legacy.length) return;
    let changed = false;
    if (!Array.isArray(cfg.sites)) cfg.sites = [];
    for (const t of legacy) {
      if (!t || !t.key) continue;
      const sk = t.siteKey || 'justworker';
      const site = cfg.sites.find(s => s.key === sk);
      if (!site) continue; // 站点已不存在则跳过该记录
      if (!Array.isArray(site.accounts)) { site.accounts = []; changed = true; }
      if (!site.accounts.find(a => a.key === t.key)) {
        site.accounts.push({
          key: t.key, alias: t.alias || t.key,
          githubAccount: t.githubAccount || null,
          token: t.token || t.accessToken || null,
          cookie: t.cookie || null,
          updatedAt: t.updatedAt || null
        });
        changed = true;
      }
    }
    if (changed) {
      saveConfig(cfg);
      try { renameSync(join(DATA_DIR, 'tokens.json'), join(DATA_DIR, 'tokens.json.migrated')); } catch (_) {}
      console.log('✅ 已迁移旧版 tokens.json → config.sites[].accounts[]（原文件改名 tokens.json.migrated）');
    }
  } catch (e) {
    console.error('迁移旧数据失败（忽略）:', e.message);
  }
}
migrateLegacy();

function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
}

function serveStatic(req, res, pathname) {
  const rel = pathname === '/' ? '/index.html' : pathname;
  const file = join(STATIC, rel);
  if (!file.startsWith(STATIC)) return false;
  try { statSync(file); } catch { return false; }
  try {
    const buf = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(buf);
    return true;
  } catch { return false; }
}

async function handleApi(req, res, url, body) {
  const seg = url.pathname.split('/').filter(Boolean); // ['api', <cmd>, ...]
  const cmd = seg[1];

  if (cmd === 'health') return json(res, 200, { ok: true, time: new Date().toISOString() });

  /* ====== 站点管理（v0.1.2） ====== */
  if (cmd === 'sites' && req.method === 'GET') {
    return json(res, 200, { ok: true, sites: (cfg.sites || []).map(maskSite) });
  }

  if (cmd === 'sites' && seg[2] === 'save' && req.method === 'POST') {
    const b = body || {};
    let baseUrl = String(b.baseUrl || '').trim().replace(/\/+$/, '');
    if (!baseUrl) return json(res, 400, { ok: false, error: '需要 baseUrl' });
    if (!/^https?:\/\//i.test(baseUrl)) baseUrl = 'https://' + baseUrl;
    const name = String(b.name || '').trim();
    const checkinType = b.checkinType === 'manual' ? 'manual' : 'login';
    if (!Array.isArray(cfg.sites)) cfg.sites = [];
    if (b.key) {
      const s = cfg.sites.find(x => x.key === b.key);
      if (!s) return json(res, 404, { ok: false, error: '站点不存在' });
      s.name = name || s.name;
      s.baseUrl = baseUrl;
      s.checkinType = checkinType;
    } else {
      const key = siteKeyOf(baseUrl);
      if (cfg.sites.some(x => x.key === key)) return json(res, 409, { ok: false, error: '该站点已存在（按地址识别）' });
      cfg.sites.push({ key, name: name || key, baseUrl, checkinType, accounts: [] });
    }
    saveConfig(cfg);
    appendLog({ site: b.key || siteKeyOf(baseUrl), account: '*', event: 'site-save', detail: { name, baseUrl, checkinType } });
    return json(res, 200, { ok: true, sites: cfg.sites.map(maskSite) });
  }

  if (cmd === 'sites' && seg[2] && seg[2] !== 'save' && req.method === 'DELETE') {
    const before = (cfg.sites || []).length;
    cfg.sites = (cfg.sites || []).filter(s => s.key !== seg[2]);
    if (cfg.sites.length === before) return json(res, 404, { ok: false, error: '站点不存在' });
    saveConfig(cfg);
    appendLog({ site: seg[2], account: '*', event: 'site-delete', detail: null });
    return json(res, 200, { ok: true });
  }

  /* ====== 账号管理（挂在站点下） ====== */
  if (cmd === 'accounts' && seg[2] === 'save' && req.method === 'POST') {
    const b = body || {};
    const site = (cfg.sites || []).find(s => s.key === b.siteKey);
    if (!site) return json(res, 400, { ok: false, error: 'siteKey 无效' });
    if (!Array.isArray(site.accounts)) site.accounts = [];
    const key = b.key || 'acc_' + Date.now();
    let a = site.accounts.find(x => x.key === key);
    if (!a) { a = { key, alias: b.alias || key }; site.accounts.push(a); }
    if (b.alias != null && String(b.alias).trim()) a.alias = String(b.alias).trim();
    if (b.githubAccount != null) a.githubAccount = b.githubAccount;
    if (b.token != null) a.token = b.token;
    if (b.cookie != null) a.cookie = b.cookie;
    a.updatedAt = new Date().toISOString();
    saveConfig(cfg);
    appendLog({ site: site.key, account: key, event: 'account-save', detail: { hasToken: !!a.token } });
    return json(res, 200, { ok: true, key });
  }

  if (cmd === 'accounts' && seg[2] && seg[2] !== 'save' && req.method === 'DELETE') {
    let removed = false;
    for (const s of (cfg.sites || [])) {
      const n = (s.accounts || []).length;
      s.accounts = (s.accounts || []).filter(a => a.key !== seg[2]);
      if (s.accounts.length < n) removed = true;
    }
    if (!removed) return json(res, 404, { ok: false, error: '账号不存在' });
    saveConfig(cfg);
    appendLog({ site: '*', account: seg[2], event: 'account-delete', detail: null });
    return json(res, 200, { ok: true });
  }

  /* 兼容旧 UI：扁平账号列表 */
  if (cmd === 'accounts' && req.method === 'GET') {
    return json(res, 200, { ok: true, tokens: allAccountsFlat() });
  }

  /* ====== 设置（只动 proxy/schedule，绝不覆盖站点/账号） ====== */
  if (cmd === 'settings' && seg[2] === 'save' && req.method === 'POST') {
    const b = body || {};
    if (b.proxy && typeof b.proxy === 'object') {
      cfg.proxy = {
        enabled: !!b.proxy.enabled, type: 'socks5',
        host: String(b.proxy.host || '127.0.0.1'),
        port: Number(b.proxy.port) || 10808
      };
    }
    if (b.schedule && typeof b.schedule === 'object') {
      cfg.schedule = { enabled: !!b.schedule.enabled, cron: String(b.schedule.cron || '0 3 * * *') };
    }
    saveConfig(cfg);
    return json(res, 200, { ok: true });
  }

  if (cmd === 'config') {
    if (req.method === 'POST') { // 兼容：仅合并 proxy/schedule，不覆盖站点
      if (body?.proxy) cfg.proxy = body.proxy;
      if (body?.schedule) cfg.schedule = body.schedule;
      saveConfig(cfg);
      return json(res, 200, { ok: true, cfg: { ...cfg, sites: (cfg.sites || []).map(maskSite) } });
    }
    return json(res, 200, { ok: true, cfg: { ...cfg, sites: (cfg.sites || []).map(maskSite) } });
  }

  /* ====== 单账号：状态 / 签到 / 日志 ====== */
  if (cmd === 'status' && seg[2]) {
    const x = findAccount(seg[2]);
    if (!x) return json(res, 404, { ok: false, error: '账号不存在' });
    const cl = new SiteClient(x.site, x.acc, cfg);
    const [self, st] = await Promise.all([cl.self(), cl.status()]);
    const d = self?.data?.data || {};
    const unit = st?.data?.data?.quota_per_unit || 500000;
    const quota = Number(d.quota || 0), used = Number(d.used_quota || 0);
    const result = {
      ok: true,
      account: x.acc.key, site: x.site.name, siteKey: x.site.key,
      http: self.status,
      authorized: self.status === 200,
      availableUSD: +(quota / unit).toFixed(2),
      usedUSD: +(used / unit).toFixed(2),
      availableRaw: quota, usedRaw: used,
      user: d.display_name || d.username || d.github_id || null,
      todayUsed: d.today_used_quota != null ? +(d.today_used_quota / unit).toFixed(4) : null
    };
    appendLog({ site: x.site.key, account: x.acc.key, event: 'status', detail: { http: self.status, availableUSD: result.availableUSD } });
    return json(res, 200, result);
  }

  if (cmd === 'checkin' && seg[2]) {
    const x = findAccount(seg[2]);
    if (!x) return json(res, 404, { ok: false, error: '账号不存在' });
    if (x.site.checkinType !== 'manual') {
      return json(res, 200, { ok: true, skipped: true, message: '该站点登录即签到，无需单独签到' });
    }
    const cl = new SiteClient(x.site, x.acc, cfg);
    const r = await cl.checkin();
    appendLog({ site: x.site.key, account: x.acc.key, event: 'checkin', detail: { http: r.status, code: r.data?.code } });
    return json(res, 200, { ok: true, http: r.status, data: r.data });
  }

  if (cmd === 'logs' && seg[2]) {
    const x = findAccount(seg[2]);
    if (!x) return json(res, 404, { ok: false, error: '账号不存在' });
    const cl = new SiteClient(x.site, x.acc, cfg);
    const cat = url.searchParams.get('category') || '系统';
    const r = await cl.usageLog({ category: cat, limit: Number(url.searchParams.get('limit') || 20), page: Number(url.searchParams.get('page') || 1) });
    const list = r?.data?.data?.list || r?.data?.data?.items || r?.data?.data || [];
    const rows = (Array.isArray(list) ? list : []).map(x => ({
      time: x.created_at || x.time, category: x.category || cat,
      text: (typeof x === 'string' ? x : (x.description || x.content || x.remark || JSON.stringify(x.data || ''))),
      quota: x.quota
    }));
    const bonus = rows.find(r => String(r.text).includes('签到'));
    return json(res, 200, { ok: true, http: r.status, rows, lastBonus: bonus || null });
  }

  if (cmd === 'history') {
    return json(res, 200, { ok: true, data: (load('logs.json') || []).slice(-300).reverse() });
  }

  if (cmd === 'proxy-test') {
    const base = (cfg.sites || [])[0]?.baseUrl || 'https://api.justwoker.icu';
    const cl = new SiteClient({ key: 't', name: 't', baseUrl: base }, {}, cfg);
    const t0 = Date.now();
    const r = await cl.status();
    return json(res, 200, { ok: r.ok && r.status === 200, http: r.status, ms: Date.now() - t0 });
  }

  if (cmd === 'refresh' && seg[2]) {
    return json(res, 200, { ok: false, error: '授权需在 App 内嵌 WebView（安卓）或桌面端授权窗口（Electron）完成' });
  }

  return json(res, 404, { ok: false, error: '未知接口: ' + url.pathname });
}

const srv = createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'content-type,authorization');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS');
  if (req.method === 'OPTIONS') return res.end();

  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  let body = {};
  if (req.method === 'POST') {
    let raw = '';
    for await (const c of req) raw += c;
    try { body = JSON.parse(raw || '{}'); } catch { body = {}; }
  }

  try {
    if (url.pathname.startsWith('/api/')) return await handleApi(req, res, url, body);
    if (serveStatic(req, res, url.pathname)) return;
    return json(res, 404, { ok: false, error: 'not found' });
  } catch (e) {
    return json(res, 500, { ok: false, error: e.message });
  }
});

if (cfg.schedule?.enabled) {
  cron.schedule(cfg.schedule.cron || '0 3 * * *', async () => {
    for (const site of (cfg.sites || [])) {
      for (const a of (site.accounts || [])) {
        if (!a.token) continue;
        try {
          const cl = new SiteClient(site, a, cfg);
          if (site.checkinType === 'manual') {
            const r = await cl.checkin();
            appendLog({ site: site.key, account: a.key, event: 'cron-checkin', detail: { http: r.status } });
          } else {
            const r = await cl.self();
            appendLog({ site: site.key, account: a.key, event: 'cron-login-refresh', detail: { http: r.status } });
          }
        } catch (e) {
          appendLog({ site: site.key, account: a.key, event: 'cron-error', detail: { error: e.message } });
        }
      }
    }
  }, { timezone: 'Asia/Shanghai' });
}

srv.listen(PORT, HOST, () => {
  const siteCount = (cfg.sites || []).length;
  const accCount = (cfg.sites || []).reduce((n, s) => n + (s.accounts || []).length, 0);
  console.log(`justsign server → http://${HOST}:${PORT}`);
  console.log(`  sites: ${siteCount} | accounts: ${accCount} | schedule: ${cfg.schedule?.enabled ? cfg.schedule.cron : 'off'}`);
});