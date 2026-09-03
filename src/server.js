/**
 * server.js — justsign 引擎 HTTP 服务：静态 UI + REST API + cron 调度
 * 用法: node src/server.js   （默认 127.0.0.1:7300）
 */
import { createServer } from 'http';
import { readFileSync, statSync } from 'fs';
import { join, dirname, extname } from 'path';
import { fileURLToPath } from 'url';
import { loadConfig, saveConfig } from './config.js';
import { load, save, appendLog } from './db.js';
import { SiteClient } from './client.js';
import cron from 'node-cron';

const __dir = dirname(fileURLToPath(import.meta.url));
const STATIC = __dir;
const PORT = parseInt(process.env.JUSTSIGN_PORT || '7300', 10);
const HOST = process.env.JUSTSIGN_HOST || '127.0.0.1';
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

const cfg = loadConfig();
let tokens = load('tokens.json') || [];

function findAccount(key) {
  const tk = tokens.find(t => t.key === key);
  if (!tk) return null;
  const site = cfg.sites.find(s => s.key === tk.siteKey);
  if (!site) return null;
  return { tk, site };
}

function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
}

function serveStatic(req, res, pathname) {
  let rel = pathname === '/' ? '/index.html' : pathname;
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
  const seg = url.pathname.split('/').filter(Boolean); // ['api', 'status', 'gh1']
  const cmd = seg[1];

  if (cmd === 'health') return json(res, 200, { ok: true, time: new Date().toISOString() });

  if (cmd === 'config') {
    if (req.method === 'POST') {
      Object.assign(cfg, body);
      saveConfig(cfg);
      return json(res, 200, { ok: true, cfg });
    }
    return json(res, 200, { ok: true, cfg });
  }

  if (cmd === 'accounts' && req.method === 'GET') {
    return json(res, 200, { ok: true, tokens: tokens.map(t => ({ ...t, token: t.token ? t.token.slice(0, 8) + '…' : null })) });
  }

  if (cmd === 'accounts' && seg[2] === 'save' && req.method === 'POST') {
    const t = body || {};
    if (!t.key || !t.siteKey) return json(res, 400, { ok: false, error: '需要 key 和 siteKey' });
    const i = tokens.findIndex(x => x.key === t.key);
    if (i >= 0) tokens[i] = { ...tokens[i], ...t }; else tokens.push(t);
    save('tokens.json', tokens);
    appendLog({ site: t.siteKey, account: t.key, event: 'account-save', detail: { hasToken: !!t.token } });
    return json(res, 200, { ok: true });
  }

  if (cmd === 'accounts' && seg[2] && req.method === 'DELETE') {
    tokens = tokens.filter(x => x.key !== seg[2]);
    save('tokens.json', tokens);
    return json(res, 200, { ok: true });
  }

  if (cmd === 'status' && seg[2]) {
    const x = findAccount(seg[2]);
    if (!x) return json(res, 404, { ok: false, error: '账号不存在' });
    const cl = new SiteClient(x.site, x.tk, cfg);
    const [self, st] = await Promise.all([cl.self(), cl.status()]);
    const d = self?.data?.data || {};
    const unit = st?.data?.data?.quota_per_unit || 500000;
    const price = st?.data?.data?.price || 7.3;
    const quota = Number(d.quota || 0), used = Number(d.used_quota || 0);
    const result = {
      ok: true,
      account: x.tk.key, site: x.site.name,
      http: self.status,
      authorized: self.status === 200,
      availableUSD: +(quota / unit).toFixed(2),
      usedUSD: +(used / unit).toFixed(2),
      availableRaw: quota, usedRaw: used,
      user: d.display_name || d.username || d.github_id || null,
      todayUsed: d.today_used_quota != null ? +(d.today_used_quota / unit).toFixed(4) : null
    };
    appendLog({ site: x.site.key, account: x.tk.key, event: 'status', detail: { http: self.status, availableUSD: result.availableUSD } });
    return json(res, 200, result);
  }

  if (cmd === 'checkin' && seg[2]) {
    const x = findAccount(seg[2]);
    if (!x) return json(res, 404, { ok: false, error: '账号不存在' });
    if (x.site.checkinType !== 'manual') {
      return json(res, 200, { ok: true, skipped: true, message: '该站点登录即签到，无需单独签到' });
    }
    const cl = new SiteClient(x.site, x.tk, cfg);
    const r = await cl.checkin();
    appendLog({ site: x.site.key, account: x.tk.key, event: 'checkin', detail: { http: r.status, code: r.data?.code } });
    return json(res, 200, { ok: true, http: r.status, data: r.data });
  }

  if (cmd === 'logs' && seg[2]) {
    const x = findAccount(seg[2]);
    if (!x) return json(res, 404, { ok: false, error: '账号不存在' });
    const cl = new SiteClient(x.site, x.tk, cfg);
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
    const cl = new SiteClient({ key: 't', name: 't', baseUrl: 'https://api.justwoker.icu' }, {}, cfg);
    const t0 = Date.now();
    const r = await cl.status();
    return json(res, 200, { ok: r.ok && r.status === 200, http: r.status, ms: Date.now() - t0 });
  }

  if (cmd === 'refresh' && seg[2]) {
    return json(res, 200, { ok: false, error: '授权需在 App 内嵌 WebView 完成（桌面端:electron main.js auth；安卓端:auth-bridge）' });
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
    for (const t of tokens) {
      const site = cfg.sites.find(s => s.key === t.siteKey);
      if (!site) continue;
      try {
        if (site.checkinType === 'manual') {
          const cl = new SiteClient(site, t, cfg);
          const r = await cl.checkin();
          appendLog({ site: site.key, account: t.key, event: 'cron-checkin', detail: { http: r.status } });
        } else {
          const cl = new SiteClient(site, t, cfg);
          const r = await cl.self();
          appendLog({ site: site.key, account: t.key, event: 'cron-login-refresh', detail: { http: r.status } });
        }
      } catch (e) {
        appendLog({ site: site.key, account: t.key, event: 'cron-error', detail: { error: e.message } });
      }
    }
  }, { timezone: 'Asia/Shanghai' });
}

srv.listen(PORT, HOST, () => {
  console.log(`justsign server → http://${HOST}:${PORT}`);
  console.log(`  sites: ${cfg.sites.map(s => s.key).join(', ')} | accounts: ${tokens.length} | schedule: ${cfg.schedule?.enabled ? cfg.schedule.cron : 'off'}`);
});