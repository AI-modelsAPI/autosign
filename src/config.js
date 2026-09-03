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
  sites: [
    { key: 'justworker', name: 'justworker (小学生公益站)', baseUrl: 'https://api.justwoker.icu', checkinType: 'login' }
  ],
  accounts: []
};

export function loadConfig() {
  try {
    if (existsSync(CFG)) {
      const raw = readFileSync(CFG, 'utf8');
      return deepMerge(DEFAULT, JSON.parse(raw));
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
