/* options.js — 管理页：站点→账号两级管理 + 日志 + 设置（复刻桌面端三视图交互） */
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
const usd2 = v => v == null ? '—' : (Math.abs(v) >= 1000 ? v.toFixed(0) : Math.abs(v) >= 100 ? v.toFixed(1) : v.toFixed(2));
const usdC = (v, cls) => v == null ? '<span class="v-dim">—</span>' : '<span class="v ' + cls + '">$' + usd2(v) + '</span>';

function toast(msg, ok = true) {
  const t = document.createElement('div');
  t.className = 'toast ' + (ok ? 'ok' : 'err');
  t.textContent = msg;
  $('toast-root').appendChild(t);
  setTimeout(() => { t.style.transition = 'opacity .3s'; t.style.opacity = '0'; setTimeout(() => t.remove(), 320); }, 3200);
}

function modal(title, bodyHtml, buttons) {
  const root = $('modal-root');
  root.innerHTML = '';
  const ov = document.createElement('div'); ov.className = 'overlay';
  const buttonsHtml = (buttons || []).map((b, i) =>
    '<button class="btn ' + (b.style || 'btn-ghost') + '" data-i="' + i + '">' + esc(b.label) + '</button>').join('');
  ov.innerHTML = '<div class="modal"><div class="modal-h">' + esc(title) + '</div><div class="modal-b">' + bodyHtml +
    '</div><div class="modal-f">' + buttonsHtml + '</div></div>';
  ov.addEventListener('click', e => { if (e.target === ov) close(); });
  ov.querySelectorAll('[data-i]').forEach(btn => btn.addEventListener('click', () => {
    const b = buttons[Number(btn.dataset.i)];
    if (b.onClick && b.onClick() === false) return;
    close();
  }));
  root.appendChild(ov);
  const escFn = e => { if (e.key === 'Escape') { close(); document.removeEventListener('keydown', escFn); } };
  document.addEventListener('keydown', escFn);
  function close() { ov.remove(); document.removeEventListener('keydown', escFn); }
  return { close };
}

function send(msg) {
  return new Promise(res => chrome.runtime.sendMessage(msg, r => res(r || { ok: false, error: 'SW 无响应' })));
}

const state = { sites: [], status: {}, cfg: null, view: 'dash' };

async function loadAll() {
  const r = await send({ type: 'getConfig' });
  if (!r.ok) { toast('加载失败: ' + r.error, false); return; }
  state.sites = r.config.sites || [];
  state.cfg = r.config;
  await refreshStatuses(false);
}

async function refreshStatuses(manual) {
  if (manual) $('btn-refresh').textContent = '⟳ 同步中…';
  const accs = [];
  for (const s of state.sites) for (const a of (s.accounts || [])) if (a.token) accs.push(a);
  await Promise.all(accs.map(async a => {
    state.status[a.key] = await send({ type: 'status', key: a.key });
  }));
  $('btn-refresh').textContent = '⟳ 刷新';
  renderDash();
  if (manual) toast('已同步最新额度');
}

function renderDash() {
  const box = $('site-list');
  const totalAccs = state.sites.reduce((n, s) => n + (s.accounts || []).length, 0);
  $('site-empty').style.display = state.sites.length ? 'none' : 'block';
  let avail = 0, used = 0, today = 0, okCount = 0;

  box.innerHTML = state.sites.map(site => {
    const accs = site.accounts || [];
    const typeTag = site.checkinType === 'manual' ? '手动签到' : '登录即签到';
    const rows = accs.map(a => {
      const st = state.status[a.key];
      const hasTok = !!a.token;
      const expired = st && st.authorized === false;
      if (st && st.ok && st.authorized) {
        okCount++;
        avail += st.availableUSD || 0; used += st.usedUSD || 0;
        if (st.todayUsed != null) today += st.todayUsed;
      }
      const pill = !hasTok ? '<span class="pill wait">待授权</span>'
        : expired ? '<span class="pill err">异常</span>' : '<span class="pill ok">正常</span>';
      const gh = a.githubAccount ? '<span class="gh">@' + esc(a.githubAccount) + '</span>'
        : (a.siteUserId ? '<span class="gh">UID ' + esc(a.siteUserId) + '</span>' : '');
      return '<div class="acc-row">' +
        '<div class="acc-top">' +
          '<span class="acc-name">' + esc(a.alias || a.key) + '</span>' + pill + gh +
          '<span class="spacer"></span>' +
          '<button class="btn btn-danger-t btn-mini" data-sact="del-acc" data-acc="' + esc(a.key) + '">删除</button>' +
        '</div>' +
        '<div class="money-row">' +
          '<div><span class="k">可用</span>' + (hasTok ? usdC(st?.availableUSD, 'v-green') : '<span class="v-dim">—</span>') + '</div>' +
          '<div><span class="k">已用</span>' + (hasTok ? usdC(st?.usedUSD, 'v-orange') : '<span class="v-dim">—</span>') + '</div>' +
          '<div><span class="k">今日</span>' + (hasTok ? usdC(st?.todayUsed, 'v-purple') : '<span class="v-dim">—</span>') + '</div>' +
        '</div>' +
        '<div class="acc-ops">' +
          (hasTok
            ? '<button class="btn btn-ghost btn-mini" data-sact="checkin" data-acc="' + esc(a.key) + '">立即签到</button>'
            : '<button class="btn btn-primary btn-mini" data-sact="auth" data-site="' + esc(site.key) + '" data-acc="' + esc(a.key) + '">GitHub 授权</button>') +
          (hasTok ? '<button class="btn btn-outline btn-mini" data-sact="reauth" data-site="' + esc(site.key) + '" data-acc="' + esc(a.key) + '">重新授权</button>' : '') +
        '</div></div>';
    }).join('');

    return '<div class="site-card">' +
      '<div class="site-head">' +
        '<span class="s-name">' + esc(site.name || site.key) + '</span>' +
        '<span class="s-type">' + typeTag + '</span>' +
        '<span class="s-url">' + esc(site.baseUrl || '') + '</span>' +
        '<span class="spacer"></span>' +
        '<button class="btn btn-ghost btn-mini" data-sact="add-acc" data-site="' + esc(site.key) + '">＋ 账号</button>' +
        '<button class="btn btn-outline btn-mini" data-sact="edit-site" data-site="' + esc(site.key) + '">编辑</button>' +
        '<button class="btn btn-danger-t btn-mini" data-sact="del-site" data-site="' + esc(site.key) + '">删除站点</button>' +
      '</div>' +
      (rows || '<div class="acc-row"><span class="gh">该站点下还没有账号，点「＋ 账号」添加。</span></div>') +
    '</div>';
  }).join('');

  $('s-avail').textContent = '$' + usd2(avail);
  $('s-used').textContent = '$' + usd2(used);
  $('s-today').textContent = '$' + usd2(today);
  $('s-count').textContent = totalAccs;
  $('s-okcount').textContent = okCount + ' 个在线';

  box.querySelectorAll('[data-sact]').forEach(b => b.addEventListener('click', () => {
    const act = b.dataset.sact, siteKey = b.dataset.site, accKey = b.dataset.acc;
    const site = state.sites.find(x => x.key === siteKey);
    if (act === 'add-acc') addAccount(siteKey);
    else if (act === 'edit-site') editSite(site);
    else if (act === 'del-site') delSite(site);
    else if (act === 'del-acc') delAccount(accKey);
    else if (act === 'auth' || act === 'reauth') startAuth(siteKey, accKey);
    else if (act === 'checkin') doCheckin(accKey, b);
  }));
}

/* ================= 站点管理 ================= */
function siteModal(existing) {
  const isEdit = !!existing;
  modal(isEdit ? '编辑站点' : '添加站点',
    '<p>站点名称：</p><p style="margin-top:6px"><input type="text" id="m-site-name" placeholder="如：小学生公益站" value="' + esc(existing?.name || '') + '"></p>' +
    '<p style="margin-top:12px">API 地址：</p><p style="margin-top:6px"><input type="text" id="m-site-url" class="mono" placeholder="https://api.example.com" value="' + esc(existing?.baseUrl || '') + '"></p>' +
    '<p style="margin-top:12px">签到方式：</p><p style="margin-top:6px">' +
      '<label><input type="radio" name="m-ct" value="login" ' + (!existing || existing.checkinType !== 'manual' ? 'checked' : '') + '> 登录即签到（new-api 站点）</label>　' +
      '<label><input type="radio" name="m-ct" value="manual" ' + (existing?.checkinType === 'manual' ? 'checked' : '') + '> 手动签到（/api/user/checkin）</label></p>' +
    '<p style="margin-top:10px;font-size:12px;color:var(--faint)">站点 key 由 API 地址自动生成；每个站点下可添加多个 GitHub 账号。</p>',
    [
      { label: '取消' },
      { label: isEdit ? '保存' : '添加站点', style: 'btn-primary', onClick: async () => {
        const name = $('m-site-name').value.trim();
        const baseUrl = $('m-site-url').value.trim();
        const ct = document.querySelector('input[name="m-ct"]:checked')?.value || 'login';
        if (!baseUrl) { toast('请填写 API 地址', false); return false; }
        const r = await send({ type: 'siteSave', site: isEdit ? { key: existing.key, name, baseUrl, checkinType: ct } : { name, baseUrl, checkinType: ct } });
        if (r.ok) { toast(isEdit ? '站点已保存' : '站点已添加'); await loadAll(); }
        else toast(r.error || '保存失败', false);
      } }
    ]);
}

async function editSite(site) { if (site) siteModal(site); }

function delSite(site) {
  if (!site) return;
  const accs = (site.accounts || []).length;
  modal('删除站点', '确定删除「<b>' + esc(site.name || site.key) + '</b>」吗？<br>其下 <b>' + accs + '</b> 个账号及授权记录将一并删除（不影响站点账号本身）。', [
    { label: '取消' },
    { label: '删除', style: 'btn-danger-t', onClick: async () => {
      const r = await send({ type: 'siteDelete', key: site.key });
      if (r.ok) { toast('站点已删除'); await loadAll(); } else toast(r.error, false);
    } }
  ]);
}

function addAccount(siteKey) {
  modal('添加账号 — ' + siteKey,
    '<p>账号别名（用于区分，如：主号 / 小号）：</p><p style="margin-top:10px"><input type="text" id="m-alias" placeholder="账号别名"></p>' +
    '<p style="margin-top:8px">添加后点「GitHub 授权」，在打开的标签页登录后自动保存凭据，无需复制粘贴。</p>',
    [
      { label: '取消' },
      { label: '添加', style: 'btn-primary', onClick: async () => {
        const alias = $('m-alias').value.trim() || ('账号' + Date.now() % 10000);
        const r = await send({ type: 'accountSave', siteKey, alias });
        if (r.ok) { toast('账号已添加'); await loadAll(); } else toast(r.error, false);
      } }
    ]);
}

function delAccount(accKey) {
  const acc = state.sites.flatMap(s => s.accounts || []).find(a => a.key === accKey);
  modal('删除账号', '确定删除「<b>' + esc(acc?.alias || accKey) + '</b>」吗？<br>仅删除本机记录，不影响站点账号。', [
    { label: '取消' },
    { label: '删除', style: 'btn-danger-t', onClick: async () => {
      const r = await send({ type: 'accountDelete', key: accKey });
      if (r.ok) { toast('已删除'); await loadAll(); } else toast(r.error, false);
    } }
  ]);
}

/* ================= 授权 / 签到 ================= */
async function startAuth(siteKey, accKey) {
  toast('正在打开授权标签页…');
  const r = await send({ type: 'auth', siteKey, key: accKey });
  if (r.ok) toast('请在打开的页面登录 GitHub，完成后自动保存');
  else toast(r.error || '授权启动失败', false);
}

async function doCheckin(accKey, btn) {
  if (btn) { btn.disabled = true; btn.textContent = '签到中…'; }
  const r = await send({ type: 'checkin', key: accKey });
  if (btn) { btn.disabled = false; btn.textContent = '立即签到'; }
  if (r.ok) {
    toast(r.skipped ? (r.message || '该站点登录即签到，已刷新保活') : '签到完成（http ' + r.http + '）');
    await refreshStatuses(false);
  } else toast('签到失败: ' + (r.error || 'http ' + r.http), false);
}

/* ================= 日志 / 设置 ================= */
async function renderLogs() {
  const box = $('log-lines');
  box.innerHTML = '<div class="empty">加载中…</div>';
  const r = await send({ type: 'logs' });
  const items = (r.data || []);
  box.innerHTML = items.length ? items.map(e =>
    '<div class="logline"><span class="t">' + esc(new Date(e.time).toLocaleString('zh-CN', { hour12: false })) +
    '</span><span class="ev">' + esc(e.event || '') +
    '</span><span class="dt">' + esc(typeof e.detail === 'string' ? e.detail : JSON.stringify(e.detail)) + '</span></div>').join('')
    : '<div class="empty">暂无日志</div>';
}

function renderSettings() {
  const sch = state.cfg?.schedule || {};
  $('sw-sched').classList.toggle('on', !!sch.enabled);
  $('in-hour').value = sch.hour ?? 3;
  $('in-minute').value = sch.minute ?? 5;
  $('sw-notify').classList.toggle('on', !!state.cfg?.notify);
}

$('sw-sched').addEventListener('click', () => $('sw-sched').classList.toggle('on'));
$('sw-notify').addEventListener('click', () => $('sw-notify').classList.toggle('on'));
$('btn-save-set').addEventListener('click', async () => {
  const hour = Math.min(23, Math.max(0, Number($('in-hour').value) || 3));
  const minute = Math.min(59, Math.max(0, Number($('in-minute').value) || 5));
  const r = await send({ type: 'settingsSave', settings: {
    schedule: { enabled: $('sw-sched').classList.contains('on'), hour, minute },
    notify: $('sw-notify').classList.contains('on')
  }});
  if (r.ok) { toast('设置已保存'); await loadAll(); } else toast('保存失败: ' + r.error, false);
});

/* ================= 顶栏 / 视图 ================= */
$('btn-add').addEventListener('click', () => siteModal(null));
$('btn-refresh').addEventListener('click', () => refreshStatuses(true));

document.querySelectorAll('.nav-item').forEach(n => n.addEventListener('click', () => {
  document.querySelectorAll('.nav-item').forEach(x => x.classList.remove('active'));
  n.classList.add('active');
  state.view = n.dataset.view;
  $('view-dash').style.display = state.view === 'dash' ? '' : 'none';
  $('view-logs').style.display = state.view === 'logs' ? '' : 'none';
  $('view-settings').style.display = state.view === 'settings' ? '' : 'none';
  $('page-title').textContent = { dash: '仪表盘', logs: '运行日志', settings: '设置' }[state.view];
  if (state.view === 'logs') renderLogs();
  if (state.view === 'settings') renderSettings();
}));

loadAll();
