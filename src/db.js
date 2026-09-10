import { readFileSync, writeFileSync, existsSync, renameSync, unlinkSync, mkdirSync, rmSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dir = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dir, '..');
const D = process.env.JUSTSIGN_DATA || join(ROOT, 'data');
mkdirSync(D, { recursive: true });

function p(name) { return join(D, name); }

export function load(name) {
  try {
    if (!existsSync(p(name))) return null;
    return JSON.parse(readFileSync(p(name), 'utf8'));
  } catch (e) {
    return null;
  }
}

export function save(name, data) {
  const fp = p(name);
  const tmp = fp + '.' + process.pid + '.tmp';
  writeFileSync(tmp, JSON.stringify(data, null, 2));
  try {
    renameSync(tmp, fp);
  } catch (e) {
    try { unlinkSync(tmp); } catch (_) {}
    throw e;
  }
}

export function appendLog(entry) {
  const logs = load('logs.json') || [];
  logs.push({ time: new Date().toISOString(), ...entry });
  if (logs.length > 10000) logs.splice(0, logs.length - 10000);
  save('logs.json', logs);
}

export function clearData() { rmSync(D, { recursive: true, force: true }); }
export { D };
