import { loadConfig, saveConfig, DEFAULT } from './config.js';
import { load, save, appendLog } from './db.js';
import { SiteClient } from './client.js';

const cfg = loadConfig();
const accts = load('accounts.json') || [];

function findAccount(key) {
  const a = accts.find(x => x.key === key);
  if (!a) { console.error('账号不存在:', key); process.exit(1); }
  const site = cfg.sites.find(s => s.key === a.siteKey);
  if (!site) { console.error('站点不存在:', a.siteKey); process.exit(1); }
  return { a, site };
}

async function cmdSetup() {
  if (!accts.length) {
    saveConfig(cfg);
    save('accounts.json', []);
    console.log('✅ 已初始化 config.json');
    console.log('   代理:', cfg.proxy.enabled ? `${cfg.proxy.type}://${cfg.proxy.host}:${cfg.proxy.port}` : '关闭');
    console.log('   已注册站点:', cfg.sites.map(s => s.key).join(', '));
    console.log('   账号列表 (data/accounts.json) 为空。');
    console.log('   提示: 运行 `node src/auth.js justworker` 完成 GitHub 授权（会弹出登录态，自动抓取）。');
  } else {
    console.log('已初始化，账号数:', accts.length);
  }
}

async function cmdStatus() {
  const { a, site } = findAccount(process.argv[3]);
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
  const { a, site } = findAccount(process.argv[3]);
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
  const { a, site } = findAccount(process.argv[3]);
  const cl = new SiteClient(site, a, cfg);
  const r = await cl.usageLog({ category: process.argv[4] || '系统', limit: 20, page: 1 });
  console.log(JSON.stringify(r, null, 2));
}

const cmds = {
  help: () => {
    console.log('justsign — 公益AI中转站自动签到引擎');
    console.log('用法: node src/index.js <命令> [参数]');
    console.log('  setup                   初始化配置');
    console.log('  status <accountKey>     站点状态 + 额度 + 用户 + 系统日志');
    console.log('  checkin <accountKey>    签到（登录即签到站点会跳过）');
    console.log('  logs <accountKey> [cat] 使用日志');
    console.log('  accounts                列出账号');
    console.log('  accounts add <key> <siteKey> <githubAccount>  新增账号');
    console.log('授权: node src/auth.js <siteKey> <accountKey>');
  },
  setup: cmdSetup,
  status: cmdStatus,
  checkin: cmdCheckin,
  logs: cmdLogs,
  accounts: () => {
    if (process.argv[3] === 'add') {
      const [, key, siteKey, gh] = process.argv.slice(4);
      if (!key || !siteKey) { console.error('缺参数: key siteKey githubAccount'); process.exit(1); }
      if (!cfg.sites.find(s => s.key === siteKey)) { console.error('站点不存在:', siteKey); process.exit(1); }
      const a = accts.find(x => x.key === key) || { key, siteKey, accounts: [] };
      if (!a.accounts.includes(gh)) a.accounts.push(gh);
      save('accounts.json', accts.includes(a) ? accts : [...accts, a]);
      console.log('✅ 已记录账号:', key, '->', gh, '@', siteKey);
    } else {
      console.log(JSON.stringify(accts, null, 2));
    }
  }
};

const cmd = process.argv[2] || 'help';
if (!cmds[cmd]) { console.error('未知命令:', cmd); process.exit(1); }
cmds[cmd]().catch(e => { console.error(e); process.exit(1); });
