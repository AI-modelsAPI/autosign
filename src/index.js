/**
 * index.js — justsign CLI（v0.1.2：站点→账号两级模型）
 *
 *   node src/index.js help
 *   node src/index.js setup                       初始化
 *   node src/index.js sites                       列出站点
 *   node src/index.js sites add <名称> <API地址> [manual|login]
 *   node src/index.js sites del <siteKey>
 *   node src/index.js accounts                    列出账号（跨站点）
 *   node src/index.js accounts add <siteKey> [别名]
 *   node src/index.js status  <accountKey>
 *   node src/index.js checkin <accountKey>
 *   node src/index.js logs    <accountKey> [类别]
 *
 * 授权: node src/auth.js <siteKey> <accountKey>  （或桌面端授权窗口 / 安卓 WebView）
 */
import { loadConfig, saveConfig } from './config.js';
import { appendLog } from './db.js';
import { SiteClient } from './client.js';

const cfg = loadConfig();
const argv = process.argv.slice(2);

function siteKeyOf(baseUrl) {
  const k = String(baseUrl || 'site').toLowerCase()
    .replace(/^https?:\/\//, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return k || 'site';
}

function findAccount(key) {
  for (const s of (cfg.sites || [])) {
    for (const a of (s.accounts || [])) {
      if (a.key === key) return { a, site: s };
    }
  }
  return null;
}

function findAccountStrict(key) {
  const x = findAccount(key);
  if (!x) { console.error('账号不存在:', key); process.exit(1); }
  return x;
}

function ensureAccounts(site) {
  if (!Array.isArray(site.accounts)) site.accounts = [];
  return site.accounts;
}

async function cmdSetup() {
  if (!(cfg.sites || []).length) {
    saveConfig(cfg);
    console.log('✅ 已初始化 config.json');
    console.log('   代理:', cfg.proxy?.enabled ? `${cfg.proxy.type}://${cfg.proxy.host}:${cfg.proxy.port}` : '关闭');
    console.log('   站点列表为空 —— 先手动添加站点:');
    console.log('     node src/index.js sites add 小学生公益站 https://api.justwoker.icu login');
    console.log('   再添加账号并授权:');
    console.log('     node src/index.js accounts add api-justwoker-icu 主号');
    console.log('     node src/auth.js api-justwoker-icu <accountKey>');
  } else {
    console.log('已初始化，站点数:', cfg.sites.length,
      '账号数:', cfg.sites.reduce((n, s) => n + (s.accounts || []).length, 0));
  }
}

async function cmdSites() {
  const sub = argv[1];
  if (sub === 'add') {
    const [name, baseUrl, type] = argv.slice(2);
    if (!name || !baseUrl) { console.error('缺参数: sites add <名称> <API地址> [manual|login]'); process.exit(1); }
    let url = baseUrl.replace(/\/+$/, '');
    if (!/^https?:\/\//i.test(url)) url = 'https://' + url;
    const key = siteKeyOf(url);
    if ((cfg.sites || []).some(s => s.key === key)) { console.error('站点已存在:', key); process.exit(1); }
    cfg.sites.push({ key, name, baseUrl: url, checkinType: type === 'manual' ? 'manual' : 'login', accounts: [] });
    saveConfig(cfg);
    console.log('✅ 已添加站点:', key, '→', url, '(' + (type === 'manual' ? '手动签到' : '登录即签到') + ')');
    return;
  }
  if (sub === 'del') {
    const key = argv[2];
    if (!key) { console.error('缺参数: sites del <siteKey>'); process.exit(1); }
    const before = (cfg.sites || []).length;
    cfg.sites = (cfg.sites || []).filter(s => s.key !== key);
    if (cfg.sites.length === before) { console.error('站点不存在:', key); process.exit(1); }
    saveConfig(cfg);
    console.log('✅ 已删除站点:', key);
    return;
  }
  // 列表
  if (!(cfg.sites || []).length) { console.log('（暂无站点，先 sites add）'); return; }
  for (const s of cfg.sites) {
    console.log(`• ${s.key}  ${s.name}  ${s.baseUrl}  [${s.checkinType === 'manual' ? '手动签到' : '登录即签到'}]  账号:${(s.accounts || []).length}`);
    for (const a of ensureAccounts(s)) {
      console.log(`    - ${a.key}  ${a.alias || ''}  ${a.githubAccount ? '@' + a.githubAccount : ''}  ${a.token ? 'token✓' : '待授权'}`);
    }
  }
}

async function cmdAccounts() {
  const sub = argv[1];
  if (sub === 'add') {
    const [siteKey, alias] = argv.slice(2);
    if (!siteKey) { console.error('缺参数: accounts add <siteKey> [别名]'); process.exit(1); }
    const site = (cfg.sites || []).find(s => s.key === siteKey);
    if (!site) { console.error('站点不存在:', siteKey, '（先 sites add）'); process.exit(1); }
    const accs = ensureAccounts(site);
    const key = 'acc_' + Date.now();
    accs.push({ key, alias: alias || key, githubAccount: null, token: null, cookie: null });
    saveConfig(cfg);
    console.log('✅ 已添加账号:', key, '@', siteKey, '→ 下一步: node src/auth.js', siteKey, key);
    return;
  }
  // 列表
  const all = [];
  for (const s of (cfg.sites || [])) for (const a of ensureAccounts(s)) all.push({ ...a, siteKey: s.key, siteName: s.name });
  console.log(JSON.stringify(all.map(a => ({ ...a, token: a.token ? a.token.slice(0, 8) + '…' : null })), null, 2));
}

async function cmdStatus() {
  const { a, site } = findAccountStrict(argv[1]);
  const cl = new SiteClient(site, a, cfg);
  const [st, sf, us] = await Promise.all([cl.status(), cl.self(), cl.usageLog({ category: '系统', limit: 5 })]);
  appendLog({ site: site.key, account: a.key, event: 'status', detail: {
    statusHttp: st.status, selfHttp: sf.status, selfCode: (typeof sf.data === 'object' && sf.data.code),
    usHttp: us.status
  }});
  console.log(JSON.stringify({
    site: site.name,
    station: st,
    user: sf,
    systemLogs: us
  }, null, 2));
}

async function cmdCheckin() {
  const { a, site } = findAccountStrict(argv[1]);
  if (site.checkinType !== 'manual') {
    console.log('ℹ️ 该站点登录即签到，无需单独操作。');
    return;
  }
  const cl = new SiteClient(site, a, cfg);
  const r = await cl.checkin();
  appendLog({ site: site.key, account: a.key, event: 'checkin', detail: { status: r.status, data: r.data } });
  console.log(JSON.stringify(r, null, 2));
}

async function cmdLogs() {
  const { a, site } = findAccountStrict(argv[1]);
  const cl = new SiteClient(site, a, cfg);
  const r = await cl.usageLog({ category: argv[2] || '系统', limit: 20, page: 1 });
  console.log(JSON.stringify(r, null, 2));
}

const cmds = {
  help: () => {
    console.log('justsign — 公益AI中转站自动签到引擎（v0.1.2 站点→账号两级）');
    console.log('用法: node src/index.js <命令> [参数]');
    console.log('  sites                                       列出站点及账号');
    console.log('  sites add <名称> <API地址> [manual|login]    添加站点');
    console.log('  sites del <siteKey>                         删除站点');
    console.log('  accounts                                    列出账号');
    console.log('  accounts add <siteKey> [别名]                站点下添加账号');
    console.log('  status <accountKey>                         站点状态 + 额度 + 用户 + 系统日志');
    console.log('  checkin <accountKey>                        签到（登录即签到站点会跳过）');
    console.log('  logs <accountKey> [cat]                     使用日志');
    console.log('  setup                                       初始化配置');
    console.log('授权: node src/auth.js <siteKey> <accountKey>');
  },
  setup: cmdSetup,
  sites: cmdSites,
  accounts: cmdAccounts,
  status: cmdStatus,
  checkin: cmdCheckin,
  logs: cmdLogs
};

const cmd = argv[0] || 'help';
if (!cmds[cmd]) { console.error('未知命令:', cmd); process.exit(1); }
Promise.resolve(cmds[cmd]()).catch(e => { console.error(e); process.exit(1); });