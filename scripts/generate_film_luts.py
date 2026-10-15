#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_film_luts.py — 程序化生成 RNI Films 风格的复古胶片 3D LUT（.plut v4）。

背景（PhotonCamera / com.hinnka.mycamera，Apache-2.0 二次开发）：
    工程的 .plut 是私有二进制 3D LUT 容器，lut/LutParser.kt 按 little-endian int32 头解析：
        magic 'PLUT' | version(1..4) | size(N) | dataType(0=U8,1=U16)
        [curveStorageId]   (version>=2)   —— 我们写 0 = TransferCurve.SRGB
        [colorSpaceOrdinal](version>=3)   —— 我们写 0 = ColorSpace.SRGB
        然后 N^3*3 个样本。
    采样坐标（见 lut/LutImageProcessor.kt 与 PreviewColorShaderModules.kt）：
        当 curve=SRGB(0)/gamut=SRGB(0) 时，送入 3D 纹理的坐标 ≈ display-referred sRGB [0,1]，
        因此本脚本在 sRGB 显示域直接做 sRGB -> sRGB 的胶片风格映射。
    数据排列与 Adobe .cube 一致：B 为最外层、G 中层、R 最内层（R 变化最快）。

胶片建模（全部在 sRGB 显示域 [0,1] 内）：
    1) 胶片特征曲线 toe/shoulder：黑位抬升(fade)、对比度 S 曲线、高光柔肩；
    2) 3x3 分通道色矩阵：跨通道混色，给出各胶片独有的颜色签名；
    3) 围绕 Rec.709 亮度的饱和度；
    4) 阴影/高光 split-tone（加色法），模拟胶片偏色。
    该脚本只烘焙颜色映射；颗粒/漏光/暗角/柔光等"质感层"由 GL shader 叠加
    （lut/FilmGrainShaders.kt、lut/FilmTextureLayersGl.kt 等），不在 LUT 内做软化，
    以保证"好看 + 清晰"——LUT 不引入任何模糊/低通。

用法：
    python3 scripts/generate_film_luts.py                 # 生成全部预设到 assets/luts/
    python3 scripts/generate_film_luts.py --list         # 只打印预设清单
    python3 scripts/generate_film_luts.py --size 33      # 指定 cube 边长（默认 33）
可重复运行，幂等覆盖同名产物。
"""

import json
import math
import os
import struct
import sys

# ---- 与 lut/LutParser.kt 对齐的容器常量 ----
PLUT_MAGIC = b"PLUT"
PLUT_VERSION = 4
CURVE_SRGB_STORAGE_ID = 0      # TransferCurve.SRGB.storageId
COLORSPACE_SRGB_ORDINAL = 0    # ColorSpace.SRGB.ordinal
DATA_TYPE_UINT16 = 1

# Rec.709 亮度权重（sRGB 显示域近似）
LUMA_R, LUMA_G, LUMA_B = 0.2126, 0.7152, 0.0722


def clamp01(x):
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def film_toneline(x, black_lift, contrast, shoulder):
    """
    一维胶片特征曲线（sRGB 显示域）。
    black_lift : 黑位抬升量 [0,~0.15]，模拟乳剂片基的雾感/暗部不发黑（OldRoll 胶片"奶"感来源）。
    contrast   : S 曲线强度，1.0 为线性，>1 对比更强。
    shoulder   : 高光柔肩量 [0,1]，>0 时高光向 1.0 缓慢滚降而不是硬切。
    """
    # 1) 抬升黑位：把 [0,1] 映射为 [black_lift, 1] 的仿射，纯黑被托起
    y = black_lift + (1.0 - black_lift) * x
    # 2) 以 0.5 为枢轴的 S 形对比（tanh 形，平滑无拐点）
    if contrast != 1.0:
        y = 0.5 + math.tanh((y - 0.5) * contrast * 2.0) / (2.0 * math.tanh(contrast))
    # 3) 高光柔肩：接近 1 时做柔和压缩，避免高光死白、保留胶片高光层次
    if shoulder > 0.0:
        over = y - (1.0 - shoulder)
        if over > 0.0:
            y = (1.0 - shoulder) + shoulder * math.tanh(over / max(shoulder, 1e-3))
    return clamp01(y)


def apply_saturation(c, sat):
    """围绕 Rec.709 亮度的饱和度缩放。sat=1 不变，<1 去饱和，>1 增饱和。"""
    luma = c[0] * LUMA_R + c[1] * LUMA_G + c[2] * LUMA_B
    return (
        clamp01(luma + (c[0] - luma) * sat),
        clamp01(luma + (c[1] - luma) * sat),
        clamp01(luma + (c[2] - luma) * sat),
    )


def apply_matrix(c, m):
    """3x3 行主序色矩阵：out_r = m[0][0]*r + m[0][1]*g + m[0][2]*b ..."""
    r, g, b = c
    return (
        clamp01(m[0][0] * r + m[0][1] * g + m[0][2] * b),
        clamp01(m[1][0] * r + m[1][1] * g + m[1][2] * b),
        clamp01(m[2][0] * r + m[2][1] * g + m[2][2] * b),
    )


def split_tone(c, shadow_rgb, shadow_amt, highlight_rgb, highlight_amt):
    """
    阴影/高光分色（additive split-tone）。
    shadow_rgb / highlight_rgb 为 0..1 目标偏色；shadow_amt/highlight_amt 为强度。
    权重：阴影用 (1-luma)^2，高光用 luma^2，中间调不受影响。
    """
    luma = c[0] * LUMA_R + c[1] * LUMA_G + c[2] * LUMA_B
    sw = shadow_amt * (1.0 - luma) ** 2
    hw = highlight_amt * (luma ** 2)
    return (
        clamp01(c[0] + sw * shadow_rgb[0] + hw * highlight_rgb[0]),
        clamp01(c[1] + sw * shadow_rgb[1] + hw * highlight_rgb[1]),
        clamp01(c[2] + sw * shadow_rgb[2] + hw * highlight_rgb[2]),
    )


# ==========================================================================
# 胶片预设库（参数均为经验性"好看且薄"的取值，参考 RNI Films 经典胶片性格）
# 每个预设：tone(逐通道 toe/对比/肩)、matrix(色矩阵)、sat、split(分色)
#   tone: dict(ch -> (black_lift, contrast, shoulder))，ch in r/g/b
# ==========================================================================
FILM_PRESETS = {
    "kodak_gold_200": {
        "config": {
            "id": "film_kodak_gold_200",
            "name": {"en": "Kodak Gold 200", "zh": "柯达金 200"},
            "path": "film/kodak_gold_200.plut",
            "category": "Film",
            "isVip": True,
        },
        "tone": {
            # 暖金卷：黑位略抬（琥珀暖暗部），中低对比，高光柔
            "r": (0.020, 1.05, 0.10),
            "g": (0.012, 0.98, 0.08),
            "b": (0.008, 0.92, 0.06),
        },
        # 红/黄抬、蓝压：经典琥珀暖；暗部带一点青绿补色
        "matrix": [
            [1.06, 0.03, -0.02],
            [0.015, 1.00, 0.005],
            [-0.03, 0.02, 0.92],
        ],
        "sat": 1.06,
        "split": {
            "shadow_rgb": (0.05, 0.03, -0.02),   # 阴影暖琥珀
            "shadow_amt": 0.10,
            "highlight_rgb": (0.04, 0.02, -0.03),  # 高光偏金
            "highlight_amt": 0.06,
        },
    },

    "portra_400": {
        "config": {
            "id": "film_portra_400",
            "name": {"en": "Portra 400", "zh": "波特罗 400"},
            "path": "film/portra_400.plut",
            "category": "Film",
            "isVip": True,
        },
        "tone": {
            # 人像卷：柔和低对比、黑位轻抬、高光奶油（不硬）
            "r": (0.018, 0.90, 0.16),
            "g": (0.014, 0.88, 0.16),
            "b": (0.012, 0.86, 0.14),
        },
        # 肤色友好：红/橙干净不发灰，蓝略降以避免冷脸
        "matrix": [
            [1.03, 0.02, 0.00],
            [0.01, 1.00, 0.01],
            [-0.01, 0.01, 0.96],
        ],
        "sat": 0.92,  # 柔和、略降饱和
        "split": {
            "shadow_rgb": (0.02, 0.02, 0.03),   # 阴影极轻冷
            "shadow_amt": 0.05,
            "highlight_rgb": (0.03, 0.02, 0.01),  # 高光微暖奶油
            "highlight_amt": 0.05,
        },
    },

    "fuji_pro400h": {
        "config": {
            "id": "film_fuji_pro400h",
            "name": {"en": "Fuji Pro 400H", "zh": "富士 Pro 400H"},
            "path": "film/fuji_pro400h.plut",
            "category": "Film",
            "isVip": True,
        },
        "tone": {
            # 富士 400H：偏绿、低饱和、明亮通透、对比偏柔
            "r": (0.016, 0.92, 0.12),
            "g": (0.016, 0.94, 0.12),
            "b": (0.014, 0.90, 0.10),
        },
        # 绿抬、红略压、蓝带青：标志性的"富士绿"（植被/灰绿影调）
        "matrix": [
            [0.98, 0.02, 0.00],
            [0.01, 1.04, 0.01],
            [-0.01, 0.02, 1.00],
        ],
        "sat": 0.95,
        "split": {
            "shadow_rgb": (-0.01, 0.02, 0.02),  # 阴影偏青绿
            "shadow_amt": 0.08,
            "highlight_rgb": (0.01, 0.02, 0.02),  # 高光微冷
            "highlight_amt": 0.05,
        },
    },

    "ektar_100": {
        "config": {
            "id": "film_ektar_100",
            "name": {"en": "Ektar 100", "zh": "爱克塔 100"},
            "path": "film/ektar_100.plut",
            "category": "Film",
            "isVip": True,
        },
        "tone": {
            # Ektar：浓郁饱和、对比略高、高光保留
            "r": (0.006, 1.12, 0.08),
            "g": (0.006, 1.12, 0.08),
            "b": (0.006, 1.10, 0.08),
        },
        # 高饱和不偏色，仅轻微抬蓝以显通透
        "matrix": [
            [1.02, 0.00, 0.00],
            [0.00, 1.02, 0.00],
            [0.00, 0.00, 1.04],
        ],
        "sat": 1.22,  # 浓郁
        "split": {
            "shadow_rgb": (0.0, 0.0, 0.01),
            "shadow_amt": 0.04,
            "highlight_rgb": (0.01, 0.01, 0.0),
            "highlight_amt": 0.04,
        },
    },

    "fuji_velvia": {
        "config": {
            "id": "film_fuji_velvia",
            "name": {"en": "Fuji Velvia 50", "zh": "富士 Velvia 50"},
            "path": "film/fuji_velvia.plut",
            "category": "Film",
            "isVip": True,
        },
        "tone": {
            # Velvia：极艳、高对比、黑位扎实、风光浓郁
            "r": (0.004, 1.18, 0.06),
            "g": (0.004, 1.18, 0.06),
            "b": (0.004, 1.16, 0.06),
        },
        # 绿/红略抬（Velvia 标志性的浓郁绿与红），蓝通透
        "matrix": [
            [1.04, 0.01, 0.00],
            [0.00, 1.05, 0.00],
            [0.00, 0.00, 1.04],
        ],
        "sat": 1.30,  # 极艳
        "split": {
            "shadow_rgb": (0.0, 0.01, 0.01),
            "shadow_amt": 0.04,
            "highlight_rgb": (0.01, 0.02, 0.0),
            "highlight_amt": 0.05,
        },
    },

    "cross_processed": {
        "config": {
            "id": "film_cross_processed",
            "name": {"en": "Cross Processed", "zh": "交叉冲印"},
            "path": "film/cross_processed.plut",
            "category": "Film",
            "isVip": True,
        },
        "tone": {
            # 交叉冲印：高对比、黑位压一点、高光冲
            "r": (-0.004, 1.22, 0.05),
            "g": (-0.004, 1.18, 0.05),
            "b": (-0.004, 1.20, 0.05),
        },
        # 青橙（teal-orange）骨架：阴影压向青、高光/肤色推向橙
        "matrix": [
            [1.05, 0.02, -0.03],
            [-0.02, 1.02, 0.02],
            [-0.05, 0.02, 1.05],
        ],
        "sat": 1.15,
        "split": {
            "shadow_rgb": (-0.04, 0.02, 0.06),   # 阴影 teal/青
            "shadow_amt": 0.18,
            "highlight_rgb": (0.08, 0.03, -0.05),  # 高光 orange/橙
            "highlight_amt": 0.16,
        },
    },

    "ilford_hp5": {
        "config": {
            "id": "film_ilford_hp5",
            "name": {"en": "Ilford HP5", "zh": "伊尔福 HP5 黑白"},
            "path": "film/ilford_hp5.plut",
            "category": "Film",
            "isVip": True,
        },
        "tone": {
            # 传统黑白：高反差、黑位扎实（不抬）、高光柔肩保层次
            "r": (0.0, 1.20, 0.10),
            "g": (0.0, 1.20, 0.10),
            "b": (0.0, 1.20, 0.10),
        },
        # 去色矩阵（Rec.709 加权成亮度），输出通道相同 => 纯黑白
        "matrix": [
            [LUMA_R, LUMA_G, LUMA_B],
            [LUMA_R, LUMA_G, LUMA_B],
            [LUMA_R, LUMA_G, LUMA_B],
        ],
        "sat": 1.0,  # 矩阵已完成去色
        "split": {
            "shadow_rgb": (0.0, 0.0, 0.0),
            "shadow_amt": 0.0,
            "highlight_rgb": (0.0, 0.0, 0.0),
            "highlight_amt": 0.0,
        },
    },
}


def apply_preset(r, g, b, p):
    """把单个 sRGB 样本 (r,g,b) 经过一个胶片预设，返回 (r',g',b') in [0,1]。"""
    tr = p["tone"]["r"]
    tg = p["tone"]["g"]
    tb = p["tone"]["b"]
    # 1) 逐通道胶片特征曲线
    r2 = film_toneline(r, *tr)
    g2 = film_toneline(g, *tg)
    b2 = film_toneline(b, *tb)
    # 2) 色矩阵
    r2, g2, b2 = apply_matrix((r2, g2, b2), p["matrix"])
    # 3) 饱和度
    r2, g2, b2 = apply_saturation((r2, g2, b2), p["sat"])
    # 4) split-tone
    s = p["split"]
    r2, g2, b2 = split_tone(
        (r2, g2, b2),
        s["shadow_rgb"], s["shadow_amt"],
        s["highlight_rgb"], s["highlight_amt"],
    )
    return clamp01(r2), clamp01(g2), clamp01(b2)


def build_cube(size, preset):
    """生成 size^3*3 的 RGB 列表，顺序：B 外 -> G 中 -> R 内（与 .cube/.plut 一致）。"""
    out = []
    inv = (size - 1.0) if size > 1 else 1.0
    for bi in range(size):
        b = bi / inv
        for gi in range(size):
            g = gi / inv
            for ri in range(size):
                r = ri / inv
                r2, g2, b2 = apply_preset(r, g, b, preset)
                out.append((r2, g2, b2))
    return out


def write_plut(path, size, cube):
    """按 LutParser 期望的 v4 二进制写 .plut（UINT16 样本）。"""
    with open(path, "wb") as f:
        f.write(PLUT_MAGIC)
        f.write(struct.pack("<I", PLUT_VERSION))
        f.write(struct.pack("<I", size))
        f.write(struct.pack("<I", DATA_TYPE_UINT16))
        f.write(struct.pack("<I", CURVE_SRGB_STORAGE_ID))
        f.write(struct.pack("<I", COLORSPACE_SRGB_ORDINAL))
        # cube 已是 (r,g,b) 三元组顺序；展开为 uint16 序列
        flat = []
        for (r, g, b) in cube:
            flat.append(int(r * 65535.0 + 0.5))
            flat.append(int(g * 65535.0 + 0.5))
            flat.append(int(b * 65535.0 + 0.5))
        f.write(struct.pack("<%dH" % len(flat), *flat))


def main():
    args = sys.argv[1:]
    size = 33
    if "--list" in args:
        print("预设清单（%d 套）:" % len(FILM_PRESETS))
        for key, p in FILM_PRESETS.items():
            c = p["config"]
            print("  - %-18s %s / %s  -> %s" % (key, c["name"]["en"], c["name"]["zh"], c["path"]))
        return
    if "--size" in args:
        size = int(args[args.index("--size") + 1])

    # 默认输出到工程 assets/luts/（脚本位于 scripts/ 下）
    here = os.path.dirname(os.path.abspath(__file__))
    lut_dir = os.path.join(here, "..", "app", "src", "main", "assets", "luts")
    lut_dir = os.path.normpath(lut_dir)

    written = []
    for key, preset in FILM_PRESETS.items():
        rel = preset["config"]["path"]  # e.g. "film/kodak_gold_200.plut"
        out_path = os.path.join(lut_dir, rel)
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        cube = build_cube(size, preset)
        write_plut(out_path, size, cube)
        written.append((rel, os.path.getsize(out_path)))
        print("生成 %-32s  (%d^3, %d bytes)" % (rel, size, os.path.getsize(out_path)))

    # 同时打印 config.json 应追加的条目片段，方便核对（人工/脚本可据此合并）
    print("\n# ---- config.json 追加条目（已由 register_film_luts.py 自动合并，此处仅打印核对）----")
    entries = [p["config"] for p in FILM_PRESETS.values()]
    print(json.dumps(entries, ensure_ascii=False, indent=2))
    print("\n完成：%d 个 .plut 已写入 %s" % (len(written), lut_dir))


if __name__ == "__main__":
    main()
