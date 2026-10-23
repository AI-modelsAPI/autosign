import assert from 'node:assert/strict';
import fs from 'node:fs';

const root = 'android/app/src/main/java/icu/justwoker/justsign';
const site = fs.readFileSync(`${root}/SiteProtocol.java`, 'utf8');
const engine = fs.readFileSync(`${root}/Engine.java`, 'utf8');

function methodBody(source, marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, `missing ${marker}`);
  const open = source.indexOf('{', start);
  assert(open >= 0, `missing body for ${marker}`);
  let depth = 0;
  let quote = '';
  let escaped = false;
  for (let i = open; i < source.length; i++) {
    const c = source[i];
    if (quote) {
      if (escaped) escaped = false;
      else if (c === '\\') escaped = true;
      else if (c === quote) quote = '';
      continue;
    }
    if (c === '"' || c === "'") { quote = c; continue; }
    if (c === '{') depth++;
    if (c === '}' && --depth === 0) return source.slice(open, i + 1);
  }
  throw new Error(`unclosed body for ${marker}`);
}

const receipt = methodBody(site, 'public static boolean hasSignInReceipt(JSONObject account, long now)');
assert(!receipt.includes('lastLogin'), 'lastLogin must not be a receipt shortcut');
assert(site.includes('"relogin".equals(source)'));
assert(site.includes('"api_sign_in".equals(source)'));

const dead = methodBody(engine, 'private boolean sessionLikelyDead(');
assert.equal(dead.includes('503'), false, 'WAF/503 must not trigger silent reauth');
assert.match(dead, /return http == 401 \|\| http == 403;/);

const refresh = methodBody(engine, 'public String refreshAccessToken(');
const rotatedPatch = refresh.indexOf('put("siteCookie", rotated)');
const failureReturn = refresh.indexOf('if (http != 200 || !json.optBoolean("success", false) || newToken.isEmpty()) return "";');
assert(rotatedPatch >= 0 && rotatedPatch < failureReturn,
  'rotated refresh cookie must be persisted before a failed refresh returns');

for (const marker of [
  'private JSONObject reloginCheckin(',
  'private JSONObject anyRouterReloginLocked(',
]) {
  const body = methodBody(engine, marker);
  const logout = body.indexOf('logoutSession(');
  const gate = body.indexOf('ReauthManager.mayProceed(lo)');
  const auth = Math.min(
    ...['SilentAuth.run(', 'OffscreenReauth.attempt(']
      .map(x => body.indexOf(x))
      .filter(x => x >= 0),
  );
  assert(logout >= 0 && gate > logout && auth > gate, `${marker} must gate reauth after logout`);
}

const scheduled = methodBody(engine, 'private void runScheduled(boolean anyOnly)');
assert(scheduled.includes('String provider = SiteProtocol.provider(site, tk);'));
assert(scheduled.includes('OffscreenReauth.attempt(ctx, store, site, key, 30)'));
assert(scheduled.includes('SilentAuth.run(ctx, sKey, key'));
assert(scheduled.indexOf('ReauthManager.mayProceed(lo)') < scheduled.indexOf('String provider = SiteProtocol.provider(site, tk);'));

console.log('reauth regressions: PASS');
