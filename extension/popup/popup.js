/* popup.js — 状态速览：额度合计 + 账号列表 + 快捷签到 */
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;' }[c]));
const usd = v => v == null ? '—' : (Math.abs(v) >= 1000 ? v.toFixed(0) : Math.abs(v) >= 100 ? v.toFixed(1) : v.toFixed(2));

function toast(msg, ok = true) {
  const t = $('toast');
  t.textContent = msg;
  t.style.borderLeft = '4px solid ' + (ok ? '#22c55e' : '#ef4444');
  t.classList.add('show');
  clearTimeout(t._h);
  t._h = setTimeout(() => t.classList.remove('show'), 2600);
}

function send(msg) {
  return new Promise(res => chrome.runtime.sendMessage(msg, r => res(r || { ok: false, error: 'no response' })));
}

let config = null;
const statuses = {};

async function loadAll() {
  const r = await send({ type: 'getConfig' });
  if (!r.ok) { $('acc-list').innerHTML = '<div class="empty">加载失败: ' + esc(r.error) + '</div>'; return; }
  config = r.config;
  const accs = [];
  for (const s of (config.sites || [])) for (const a of (s.accounts || [])) accs.push({ a, site: s });
  await Promise.all(accs.filter(x => x.a.token).map(async x => {
    statuses[x.a.key] = await send({ type: 'status', key: x.a.key });
  }));
  render(accs);
}

function render(accs) {
  let avail = 0, used = 0, okCount = 0;
  const html = accs.map(({ a, site }) => {
    const st = statuses[a.key];
    const hasTok = !!a.token;
    if (st?.ok && st.authorized) { okCount++; avail += st.availableUSD || 0; used += st.usedUSD || 0; }
    const pill = !hasTok ? '<span class="pill wait">待授权</span>'
      : st && st.authorized === false ? '<span class="pill err">异常</span>' : '<span class="pill ok">正常</span>';
    return '<div class="acc" data-acc="' + esc(a.key) + '">' +
      '<div class="acc-top"><b>' + esc(a.alias || a.key) + '</b>' +
      '<span class="site">' + esc(site.name || site.key) + '</span>' + pill + '<span class="spacer"></span></div>' +
      '<div class="money">' +
        '<div><span class="k">可用</span>' + (hasTok ? '<span class="v v-green">$' + usd(st?.availableUSD) + '</span>' : '<span class="v-dim">—</span>') + '</div>' +
        '<div><span class="k">已用</span>' + (hasTok ? '<span class="v v-orange">$' + usd(st?.usedUSD) + '</span>' : '<span class="v-dim">—</span>') + '</div>' +
      '</div>' +
      '<div class="ops">' +
        (hasTok
          ? '<button class="btn btn-ghost" data-act="checkin">立即签到</button>' +
            '<button class="btn btn-outline" data-act="refresh">刷新</button>'
          : '<button class="btn btn-primary" data-act="auth">GitHub 授权</button>') +
      '</div></div>';
  }).join('');
  $('acc-list').innerHTML = html || '<div class="empty">还没有站点和账号<br>点右上「管理页」添加</div>';
  $('s-avail').textContent = '$' + usd(avail);
  $('s-used').textContent = '$' + usd(used);
  $('s-count').textContent = accs.length + (okCount ? ` · ${okCount} 在线` : '');

  $('acc-list').querySelectorAll('[data-act]').forEach(b => b.addEventListener('click', async () => {
    const accKey = b.closest('.acc').dataset.acc;
    const act = b.dataset.act;
    if (act === 'checkin') {
      b.disabled = true; b.textContent = '签到中…';
      const r = await send({ type: 'checkin', key: accKey });
      b.disabled = false; b.textContent = '立即签到';
      toast(r.ok ? (r.skipped ? r.message : '签到完成（http ' + r.http + '）') : ('失败: ' + r.error), r.ok);
      loadAll();
    } else if (act === 'refresh') {
      loadAll();
    } else if (act === 'auth') {
      const site = accs.find(x => x.a.key === accKey)?.site;
      if (site) {
        const r = await send({ type: 'auth', siteKey: site.key, key: accKey });
        toast(r.ok ? '已打开授权页，登录后自动保存' : ('失败: ' + r.error), r.ok);
        if (r.ok) window.close();
      }
    }
  }));
}

$('open-mgr').addEventListener('click', () => chrome.runtime.openOptionsPage());
loadAll();
