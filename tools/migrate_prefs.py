#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
justsign 配置搬迁：换签名后必须卸载重装，这个脚本把配置带过去。

为什么需要：v0.2.1 起 debug 构建改用仓库内固定签名（justsign-debug.jks），
与之前每机随机的默认 debug 签名不同，Android 拒绝覆盖安装，只能卸载重装，
而卸载会清掉 shared_prefs。

做的事：
  1) 读备份的 shared_prefs/justsign.xml（HTML 实体编码的 JSON）
  2) 把 credentials[].password / .twofa 的密文清空
     —— 卸载时 Android Keystore 里的 AES 密钥已销毁，那两串 v1: 密文再也解不开；
        留着只会让 UI 显示「已存密码」而实际填充为空。清掉，提示用户重录。
  3) 输出干净的 XML 到 /data/local/tmp/js_prefs_clean.xml

用法（在 Operit 的 Ubuntu 里跑，然后用 shell 写回）：
  python3 tools/migrate_prefs.py <备份.xml> [输出.xml]
  # 写回：run-as icu.justwoker.justsign sh -c 'cat /data/local/tmp/js_prefs_clean.xml > \
  #        /data/data/icu.justwoker.justsign/shared_prefs/justsign.xml'
"""
import sys, re, json, html

DEF_SRC = '/data/local/tmp/js_prefs_backup.xml'
DEF_DST = '/data/local/tmp/js_prefs_clean.xml'


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else DEF_SRC
    dst = sys.argv[2] if len(sys.argv) > 2 else DEF_DST

    raw = open(src, encoding='utf-8').read()
    m = re.search(r'(<string name="config">)(.*?)(</string>)', raw, re.S)
    if not m:
        print('未找到 config 段，文件可能不是 justsign.xml')
        return 1

    cfg = json.loads(html.unescape(m.group(2)))

    cleared = 0
    for c in cfg.get('credentials', []) or []:
        for f in ('password', 'twofa'):
            if c.get(f, ''):
                c[f] = ''
                cleared += 1

    sites = cfg.get('sites', []) or []
    accs = sum(len(s.get('accounts') or []) for s in sites)
    tokens = sum(1 for s in sites for a in (s.get('accounts') or []) if a.get('token'))

    newjson = html.escape(json.dumps(cfg, ensure_ascii=False), quote=True)
    out = raw[:m.start(2)] + newjson + raw[m.end(2):]
    open(dst, 'w', encoding='utf-8').write(out)

    print(f'站点 {len(sites)} 个 · 账号 {accs} 个（其中 {tokens} 个已授权 token 保留）')
    print(f'已清空 {cleared} 个失效密文字段（密码/2FA 需重新录入）')
    print(f'输出: {dst}')
    return 0


if __name__ == '__main__':
    sys.exit(main())