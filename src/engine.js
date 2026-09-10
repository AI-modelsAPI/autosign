/**
 * engine.js — 桌面端引擎（v0.2.0）：与安卓 v0.6.x 对齐的核心调度
 *   callWithAuth   带 token/cookie/New-Api-User 的统一请求（对齐安卓 Engine.callWithAuth）
 *   statusOf       账号状态：余额/累计已用/今日消耗/已签（GET /api/user/self + /api/data/self）
 *   checkinOne     手动签到（POST /api/user/checkin）
 *   affTransfer    邀请额度划转（POST /api/user/aff_transfer，最小 $1）
 *   tokenList/Create/Delete  API Key 管理（/api/token/ 三件套）
 * 安全红线：日志只记布尔/数值，绝不记 token/cookie/key 明文。
 */
import { SiteClient } from './client.js';

export const QUOTA_PER_UNIT_DEFAULT = 500000;

function jOf(x) { return (x && typeof x === 'object' && x.data && typeof x.data === 'object') ? x.data : {}; }

/** 统一带凭据请求。acc 需含 token / cookie / siteUserId（兼容安卓字段 siteCookie）。 */
export async function callWithAuth(cfg, site, acc, method, path, body) {
  const a = {
    ...acc,
    cookie: acc.cookie || acc.siteCookie || '',
  };
  const cl = new SiteClient(site, a, cfg);
  // New-Api-User 头（新版 New API UserAuth 要求，缺则 401）
  const uid = String(acc.siteUserId || acc.userId || '').trim();
  if (uid) cl.axios.defaults.headers.common['New-Api-User'] = uid;
  cl.axios.defaults.headers.common['Referer'] = site.baseUrl + '/';
  return cl.call(method, path, body);
}

function userOf(selfRes) {
  const j = jOf(selfRes);
  return (j.user && typeof j.user === 'object') ? j.user : j;
}

/** 账号状态聚合（对齐安卓 status()）。返回 {ok,http,quota,used,todayUsed,checkedIn,affQuota,affHistory,message} */
export async function statusOf(cfg, site, acc) {
  const unit = site.quotaPerUnit || QUOTA_PER_UNIT_DEFAULT;
  const self = await callWithAuth(cfg, site, acc, 'get', '/api/user/self');
  const http = self.status || 0;
  if (http !== 200) {
    const msg = jOf(self).message || (self.data && self.data.message) || ('HTTP ' + http);
    return { ok: false, http, message: msg };
  }
  const u = userOf(self);
  const quota = Number(u.quota || 0) / unit;
  const used = Number(u.used_quota || 0) / unit;
  const affQuota = Number(u.aff_quota || 0) / unit;
  const affHistory = Number(u.aff_history_quota || 0) / unit;
  // 今日消耗：/api/data/self 按小时聚合，取今天 0 点起
  let todayUsed = null;
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime() / 1000;
  const end = (now.getTime() / 1000) + 300;
  try {
    const d = await callWithAuth(cfg, site, acc, 'get',
      `/api/data/self?start_timestamp=${Math.floor(start)}&end_timestamp=${Math.floor(end)}&default_time=hour`);
    if (d.status === 200) {
      const arr = jOf(d);
      const items = Array.isArray(arr) ? arr : (arr.data || []);
      let sum = 0;
      for (const it of items) sum += Number(it.quota || 0) + Number(it.token_used || 0) * 0; // quota 为主
      todayUsed = sum / unit;
    }
  } catch (_) { /* 今日消耗获取失败不阻塞 */ }
  return {
    ok: true, http, quota, used, todayUsed, affQuota, affHistory,
    checkedIn: !!u.checked_in,
    siteUserId: String(u.id || acc.siteUserId || ''),
    username: u.username || u.display_name || '',
    affCode: String(u.aff_code || ''),
  };
}

/** 手动签到。返回 {ok, already, reward, message} */
export async function checkinOne(cfg, site, acc) {
  const r = await callWithAuth(cfg, site, acc, 'post', '/api/user/checkin');
  const j = jOf(r);
  const ok = r.status === 200 && j.success !== false;
  const msg = j.message || (ok ? '签到成功' : ('HTTP ' + r.status));
  return { ok, already: /已经|already|已签/i.test(msg), reward: null, message: msg, http: r.status };
}

/** 邀请额度划转：读取 aff_quota，≥$1 全额划转。开关关闭时不执行。 */
export async function affTransfer(cfg, site, acc, enabled) {
  if (!enabled) return { ok: false, skipped: true, message: '划转开关未开启' };
  const self = await callWithAuth(cfg, site, acc, 'get', '/api/user/self');
  if (self.status !== 200) return { ok: false, message: 'HTTP ' + self.status };
  const u = userOf(self);
  const unit = site.quotaPerUnit || QUOTA_PER_UNIT_DEFAULT;
  const aff = Number(u.aff_quota || 0);
  if (aff < unit) return { ok: false, message: '邀请额度不足 $1，暂不划转' };
  const r = await callWithAuth(cfg, site, acc, 'post', '/api/user/aff_transfer', { quota: aff });
  const j = jOf(r);
  return { ok: r.status === 200 && j.success !== false, message: j.message || (r.status === 200 ? '划转成功' : ('HTTP ' + r.status)), amount: aff / unit };
}

/* ---------------- API Key 管理（New API /api/token/ 三件套） ---------------- */
export async function tokenList(cfg, site, acc) {
  const r = await callWithAuth(cfg, site, acc, 'get', '/api/token/?p=1&size=100');
  const j = jOf(r);
  if (r.status !== 200 || j.success === false) {
    return { ok: false, message: j.message || ('HTTP ' + r.status) };
  }
  // 四形态兼容（对齐安卓 v0.6.3 修复）
  let items = [];
  const inner = j.data;
  if (Array.isArray(inner)) items = inner;
  else if (inner && typeof inner === 'object') {
    items = inner.items || inner.records || (Array.isArray(j.items) ? j.items : []);
  } else if (Array.isArray(j.items)) items = j.items;
  return { ok: true, items };
}

export async function tokenCreate(cfg, site, acc, name) {
  const r = await callWithAuth(cfg, site, acc, 'post', '/api/token/', { name: String(name || 'key') });
  const j = jOf(r);
  const ok = r.status === 200 && j.success !== false;
  return { ok, message: j.message || (ok ? '新建成功' : ('HTTP ' + r.status)) };
}

export async function tokenDelete(cfg, site, acc, id) {
  const r = await callWithAuth(cfg, site, acc, 'delete', '/api/token/' + id);
  const j = jOf(r);
  const ok = r.status === 200 && j.success !== false;
  return { ok, message: j.message || (ok ? '删除成功' : ('HTTP ' + r.status)) };
}
