#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
把 Java 里拼接的 JS 模板抽出成 .js，供 `node --check` 做语法校验。

背景：CheckinJs / AuthFillJs 的脚本是几百行 Java 字符串 `"..." + "..."` 拼出来的，
一个漏掉的引号或括号在编译期完全看不出来，只会在运行时静默失败（表现为「签到没反应」）。
这里按 Java 字符串字面量的转义规则解析（\" \\ \n \uXXXX），拼回真实 JS。

用法：
  python3 tools/extract_js.py <Java文件> <输出.js> [TEMPLATE字段名]
  node --check <输出.js>
"""
import sys, re


def parse_java_literals(src: str):
    r"""按 Java 转义规则逐字符扫描，返回所有字符串字面量的内容（已解转义）。
    行注释 // 与块注释 /* */ 内的引号会被跳过。"""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        # 跳过注释
        if c == '/' and i + 1 < n:
            if src[i + 1] == '/':
                j = src.find('\n', i)
                i = n if j < 0 else j + 1
                continue
            if src[i + 1] == '*':
                j = src.find('*/', i + 2)
                i = n if j < 0 else j + 2
                continue
        if c == '"':
            buf, i = [], i + 1
            while i < n:
                ch = src[i]
                if ch == '\\':
                    nx = src[i + 1]
                    simple = {'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', 'f': '\f',
                              '"': '"', "'": "'", '\\': '\\'}
                    if nx in simple:
                        buf.append(simple[nx]); i += 2
                    elif nx == 'u':
                        buf.append(chr(int(src[i + 2:i + 6], 16))); i += 6
                    else:
                        buf.append(nx); i += 2
                elif ch == '"':
                    i += 1
                    break
                else:
                    buf.append(ch); i += 1
            out.append(''.join(buf))
            continue
        i += 1
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    src_path, dst_path = sys.argv[1], sys.argv[2]
    field = sys.argv[3] if len(sys.argv) > 3 else 'TEMPLATE'

    src = open(src_path, encoding='utf-8').read()
    key = f'private static final String {field} ='
    if key not in src:
        print(f'未找到字段 {field}')
        return 1
    body = src[src.index(key) + len(key):]
    # 模板以 `;` 结尾（最后一行是 "...\")();";）
    end = body.find('\n}')
    if end > 0:
        body = body[:end]

    js = ''.join(parse_java_literals(body))
    # 常量引用（如 + SIGNAL_NEED_UI +）在拼接时是 Java 标识符，这里补成字面量
    consts = dict(re.findall(r'String\s+([A-Z_]+)\s*=\s*"([^"]*)"', src))
    for name, val in consts.items():
        js = js.replace(f"'{name}'", f"'{val}'")

    open(dst_path, 'w', encoding='utf-8').write(js)
    print(f'{src_path} -> {dst_path}  ({len(js)} chars)')
    return 0


if __name__ == '__main__':
    sys.exit(main())