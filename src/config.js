/**
 * config.js — 配置加载/保存（v0.1.2：站点→账号两级模型）
 *   config.sites[] = 站点（用户手动添加，不写死）；site.accounts[] = 该站点下的账号（含 token）。
 *   旧版扁平 data/tokens.json 由 server.js migrateLegacy() 自动迁移。
 */
import { readFileSync, writeFileSync, existsSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dir = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dir, '..');
const CFG = process.env.JUSTSIGN_CONFIG || join(ROOT, 'config.json');

export const DEFAULT = {
  proxy: { enabled: true, type: 'socks5', host: '127.0.0.1', port: 10808 },
  UA: 'Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
  chromiumPath: null,
  chromiumArgs: ['--no-sandbox', '--disable-dev-shm-usage', '--single-process'],
  schedule: { enabled: false, cron: '0 3 * * *' },
  sites: [] // 站点不写死，由用户手动添加（名称 + API 地址 + 签到方式）
};

export function loadConfig() {
  try {
    if (existsSync(CFG)) {
      const raw = readFileSync(CFG, 'utf8');
      const merged = deepMerge(DEFAULT, JSON.parse(raw));
      if (!Array.isArray(merged.sites)) merged.sites = [];
      // 兼容旧结构：如果配置里仍有顶层 accounts 数组，丢弃（真正数据在 tokens.json 迁移）
      return merged;
    }
  } catch (_) {}
  return structuredClone(DEFAULT);
}

export function saveConfig(cfg) {
  writeFileSync(CFG, JSON.stringify(cfg, null, 2));
}

function deepMerge(a, b) {
  const out = { ...a };
  for (const k of Object.keys(b)) {
    const bv = b[k], av = out[k];
    if (bv && av && typeof bv === 'object' && typeof av === 'object' && !Array.isArray(bv)) {
      out[k] = deepMerge(av, bv);
    } else {
      out[k] = bv;
    }
  }
  return out;
}

export { CFG };