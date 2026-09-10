#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 justsign 主程序图标（扁平设计，无渐变无阴影）。

设计：品牌蓝 #2563EB 圆角方底 + 白色对勾 + 右上角一枚小圆点（"每日"暗示）。
输出：
  mipmap-*/ic_launcher.png            传统图标（含圆角，带 8% 内边距）
  mipmap-*/ic_launcher_fg.png         自适应图标前景（透明底，勾居中，安全区 66%）
  mipmap-*/ic_launcher_bg.png         自适应图标背景（纯品牌蓝满幅）
不依赖 PIL：自己做超采样光栅化 + zlib/CRC 写 PNG。
"""
import zlib, struct, os, math

BLUE = (0x25, 0x63, 0xEB)
WHITE = (0xFF, 0xFF, 0xFF)

SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
SS = 4  # 超采样倍数（抗锯齿）


def write_png(path, w, h, rgba_rows):
    raw = b''.join(b'\x00' + bytes(r) for r in rgba_rows)
    comp = zlib.compress(raw, 9)

    def chunk(typ, data):
        c = struct.pack('>I', len(data)) + typ + data
        return c + struct.pack('>I', zlib.crc32(typ + data) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', comp)
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


def rounded_rect_cover(x, y, l, t, r, b, rad):
    """点 (x,y) 是否在圆角矩形内"""
    if x < l or x > r or y < t or y > b:
        return False
    # 四角
    for cx, cy in ((l + rad, t + rad), (r - rad, t + rad), (l + rad, b - rad), (r - rad, b - rad)):
        inx = (x < l + rad) if cx == l + rad else (x > r - rad)
        iny = (y < t + rad) if cy == t + rad else (y > b - rad)
        if inx and iny:
            return (x - cx) ** 2 + (y - cy) ** 2 <= rad * rad
    return True


def seg_dist(px, py, ax, ay, bx, by):
    """点到线段距离"""
    dx, dy = bx - ax, by - ay
    ll = dx * dx + dy * dy
    if ll == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / ll))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def render(size, mode):
    """mode: 'launcher' | 'fg' | 'bg'  → 返回 rgba 行列表"""
    S = size * SS
    # 24 网格 → 像素比例
    u = S / 24.0

    if mode == 'bg':
        rows = []
        for _ in range(size):
            rows.append(bytes(BLUE + (255,)) * size)
        return rows

    # 底：launcher 有圆角蓝底；fg 透明
    if mode == 'launcher':
        pad = 1.2 * u          # 外边距
        rad = 5.4 * u          # 圆角（约 22%，与系统图标观感一致）
        l, t, r, b = pad, pad, S - pad, S - pad
    else:
        l = t = r = b = 0

    # 勾：三点折线。launcher 铺满蓝底；fg 用于自适应图标，需落在中央 66% 安全区内
    if mode == 'launcher':
        p = [(6.2 * u, 12.6 * u), (10.6 * u, 17.0 * u), (18.0 * u, 7.4 * u)]
        stroke = 2.7 * u
    else:
        p = [(6.6 * u, 12.6 * u), (10.9 * u, 16.9 * u), (17.6 * u, 7.4 * u)]
        stroke = 2.4 * u
    dot = None

    half = stroke / 2.0
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            ar = ag = ab = aa = 0
            for sy in range(SS):
                for sx in range(SS):
                    fx = px * SS + sx + 0.5
                    fy = py * SS + sy + 0.5
                    cr = cg = cb = 0
                    ca = 0
                    if mode == 'launcher':
                        if rounded_rect_cover(fx, fy, l, t, r, b, rad):
                            cr, cg, cb, ca = BLUE[0], BLUE[1], BLUE[2], 255
                    # 勾
                    d = min(seg_dist(fx, fy, p[0][0], p[0][1], p[1][0], p[1][1]),
                            seg_dist(fx, fy, p[1][0], p[1][1], p[2][0], p[2][1]))
                    inmark = d <= half
                    if not inmark and dot:
                        inmark = (fx - dot[0]) ** 2 + (fy - dot[1]) ** 2 <= dot[2] ** 2
                    if inmark:
                        if mode == 'launcher':
                            cr, cg, cb, ca = WHITE[0], WHITE[1], WHITE[2], 255
                        else:
                            cr, cg, cb, ca = WHITE[0], WHITE[1], WHITE[2], 255
                    ar += cr * ca // 255
                    ag += cg * ca // 255
                    ab += cb * ca // 255
                    aa += ca
            n = SS * SS
            a = aa // n
            if a == 0:
                row += bytes((0, 0, 0, 0))
            else:
                # 由预乘还原
                row += bytes((min(255, ar * 255 // aa), min(255, ag * 255 // aa),
                              min(255, ab * 255 // aa), a))
        rows.append(bytes(row))
    return rows


def main():
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.abspath(os.path.join(base, '..', 'android', 'app', 'src', 'main', 'res'))
    if not os.path.isdir(res):
        res = '/sdcard/OperitStorage/projects/justsign/android/app/src/main/res'
    for dpi, sz in SIZES.items():
        d = os.path.join(res, 'mipmap-' + dpi)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, 'ic_launcher.png'), sz, sz, render(sz, 'launcher'))
        # 自适应图标层用 108dp 网格（前景 = 1.5 倍常规尺寸）
        fg = int(sz * 108 / 48)
        write_png(os.path.join(d, 'ic_launcher_fg.png'), fg, fg, render(fg, 'fg'))
        write_png(os.path.join(d, 'ic_launcher_bg.png'), fg, fg, render(fg, 'bg'))
        print(dpi, sz, 'fg', fg, 'ok')


if __name__ == '__main__':
    main()