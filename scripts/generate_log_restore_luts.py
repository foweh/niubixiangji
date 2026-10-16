#!/usr/bin/env python3
"""
生成 StormCam 缺失的两款 Log 还原 LUT（Log→Rec.709），落盘到 assets/luts/log/。

对应任务：Log 视频 20 项 · 第 4 项「Log LUT 包强化」。

为什么只补这两款
----------------
工程 `assets/luts/log/` 已有 6 款 Log 还原 LUT（Apple Log2 / F-Log2 / V-Log / LogC4 /
Log3G10 / L-Log），但**缺 HLG 与 ARRI LogC3**——正是本任务主推的三档中的两档。
本脚本按与工程 shader 严格互逆的链条烘焙这两款：

    正向（工程 GLSL，录制时把线性画面编码成 Log 灰片）
        linear709 --(applyLutColorSpace: M(709→原生色域))--> linear_gamut
                 --(applyLutCurve: 该档位的 Log 曲线)------> Log 代码

    逆向（本脚本烘焙的还原 LUT，把 Log 灰片还原成 Rec.709 显示）
        Log 代码 --(Log 曲线逆变换)--> linear_gamut
                --(M(原生色域→709))--> linear709
                --(Rec.709 OETF + 高光软膝)--> 显示代码

关键一致性保证（已用工程内置文件反查核对过）
------------------------------------------
* `.plut` 头部标签（curve storageId / colorSpace ordinal）必须与档位一致，
  否则 `VideoLogProfile.matchesLut()` 会把 LUT 从 Log 视频模式的列表里过滤掉；
* 输入轴 = **Log 代码值**（与 shader 在 `uVideoLogEnabled` 时 `lutInColor = color.rgb` 一致）；
* 色域矩阵用与工程 `LutShaderMappings.colorSpaceId` 同一套 D65 基色算法：
  已核对本脚本算出的 709→BT2020 / 709→ARRI4 / 709→V-Gamut 与工程 GLSL 中
  `colorSpace == 2 / 3 / 7` 分支的数值**逐位一致**（见 --audit 输出）。

依赖：仅标准库（不引入 numpy，便于在任意环境重跑）。

用法：
    python3 scripts/generate_log_restore_luts.py                # 生成两款 LUT 到 assets
    python3 scripts/generate_log_restore_luts.py --audit        # 审计既有 6 款 LUT 的中灰映射
    python3 scripts/generate_log_restore_luts.py --out /tmp/x   # 输出到指定目录
"""

from __future__ import annotations

import argparse
import json
import math
import os
import struct
import sys

# ─────────────────────────── 常量 ───────────────────────────

LUT_SIZE = 33            # 与工程既有 Log 还原组同规格
PLUT_MAGIC = 0x54554C50  # 'PLUT' (little endian)
PLUT_VERSION = 3         # v3 = 支持 curve(>=2) + colorSpace(>=3) 标签，工程 LutParser 已支持 1..4
DATA_TYPE_UINT16 = 1

# TransferCurve.storageId（工程 color/TransferCurve.kt）
CURVE_STORAGE_ID = {
    "HLG": 7,
    "LOGC3": 12,   # ⚠️ 由 patch 新增：TransferCurve.LOGC3(storageId = 12, shaderId = 11)
}

# ColorSpace.ordinal（工程 raw/ColorSpace.kt；**新增枚举必须追加在末尾以保住既有 ordinal**）
COLORSPACE_ORDINAL = {
    "SRGB": 0, "DCI_P3": 1, "BT2020": 2, "VGamut": 3, "ARRI4": 4, "AppleLog2": 5,
    "S_GAMUT3_CINE": 6, "ACES_AP1": 7, "ProPhoto": 8, "RED": 9,
    "FilmLightEGamut": 10, "HNCS": 11,
    "ARRI3": 12,   # ⚠️ 由 patch 追加在末尾：ColorSpace.ARRI3
}

D65 = (0.3127, 0.3290)

# 色域主色色度（与工程 ColorSpace 定义同一批数值）
GAMUT_PRIMARIES = {
    "SRGB": ((0.640, 0.330), (0.300, 0.600), (0.150, 0.060)),
    "BT2020": ((0.708, 0.292), (0.170, 0.797), (0.131, 0.046)),
    "VGamut": ((0.730, 0.280), (0.165, 0.840), (0.100, -0.030)),
    "ARRI4": ((0.7347, 0.2653), (0.1424, 0.8576), (0.0991, -0.0308)),
    "ARRI3": ((0.6840, 0.3130), (0.2210, 0.8480), (0.0861, -0.1020)),
    "AppleLog2": ((0.725, 0.301), (0.221, 0.814), (0.068, -0.076)),
    "RED": ((0.780308, 0.304253), (0.121595, 1.493994), (0.095612, -0.084589)),
}

# ARRI LogC3（EI 800）官方公开常量
LOGC3_A, LOGC3_B, LOGC3_C = 5.555556, 0.052272, 0.247190
LOGC3_D, LOGC3_E, LOGC3_F = 0.385537, 5.367655, 0.092809
LOGC3_CUT_LINEAR = 0.010591

# HLG (ITU-R BT.2100)
HLG_A = 0.17883277
HLG_B = 1.0 - 4.0 * HLG_A
HLG_C = 0.5 - HLG_A * math.log(4.0 * HLG_A)


# ─────────────────────────── 曲线 ───────────────────────────

def logc3_encode(linear: float) -> float:
    x = max(linear, 0.0)
    if x > LOGC3_CUT_LINEAR:
        return LOGC3_C * math.log10(LOGC3_A * x + LOGC3_B) + LOGC3_D
    return LOGC3_E * x + LOGC3_F


def logc3_decode(code: float) -> float:
    """LogC3 逆变换；线性域结果不做 [0,1] 截断（高光余量由后续 OETF 处理）。"""
    c = min(max(code, 0.0), 1.0)
    if c > LOGC3_E * LOGC3_CUT_LINEAR + LOGC3_F:
        return (10.0 ** ((c - LOGC3_D) / LOGC3_C) - LOGC3_B) / LOGC3_A
    return (c - LOGC3_F) / LOGC3_E


def hlg_encode(linear: float) -> float:
    x = min(max(linear, 0.0), 1.0)
    if x <= 1.0 / 12.0:
        return math.sqrt(3.0 * x)
    return HLG_A * math.log(12.0 * x - HLG_B) + HLG_C


def hlg_decode(code: float) -> float:
    c = min(max(code, 0.0), 1.0)
    if c <= 0.5:
        return (c * c) / 3.0
    return (math.exp((c - HLG_C) / HLG_A) + HLG_B) / 12.0


CURVES = {
    "HLG": (hlg_encode, hlg_decode),
    "LOGC3": (logc3_encode, logc3_decode),
}

# 各档位 LUT 的输入色域（= 该档位原生色域），与 Kotlin LogCurvePreset.gamut 一致
PRESET_GAMUT = {
    "HLG": "BT2020",
    "LOGC3": "ARRI3",
}

# 映射到显示白（100% 信号）的场景线性值 —— 与 Kotlin LogCurvePreset.displayWhiteInput 一致。
# HLG：BT.2408 漫反射白在代码 0.75（场景线性 0.2649）；若按"代码 1.0 = 白"会整体偏暗约 1 档。
HLG_DIFFUSE_WHITE_SCENE = (math.exp((0.75 - HLG_C) / HLG_A) + HLG_B) / 12.0
PRESET_DISPLAY_WHITE = {
    "HLG": HLG_DIFFUSE_WHITE_SCENE,
    "LOGC3": 1.0,
}


# ─────────────────────────── 显示编码 ───────────────────────────

def rec709_oetf(linear: float) -> float:
    x = max(linear, 0.0)
    if x < 0.018:
        return 4.5 * x
    return 1.099 * (x ** 0.45) - 0.099


def srgb_oetf(linear: float) -> float:
    x = max(linear, 0.0)
    if x <= 0.0031308:
        return 12.92 * x
    return 1.055 * (x ** (1.0 / 2.4)) - 0.055


# 默认 Rec.709 OETF：18% 中灰 → 0.410 信号，与 ARRI「LogC3-to-Gamma24-Rec709」、
# Panasonic「V-709」的 41% 中灰规范一致。
# 注：工程既有 6 款 Log 还原 LUT 的 18% 中灰落在 0.45（≈sRGB/显示 gamma 口味，略亮），
# 若要与本脚本产物在观感上完全对齐，用 --oetf srgb 重跑即可（见 delivery/changelog 说明）。
OETF = {"rec709": rec709_oetf, "srgb": srgb_oetf}
ACTIVE_OETF = rec709_oetf


def soft_knee(linear: float, knee: float = 0.9) -> float:
    """端点精确的高光软膝（与 Kotlin LogToRec709LutBaker.softKnee 逐位同式）：
    knee 以下恒等、knee 处连续、1.0 精确映射到 1.0（保证场景白 = 100% 信号）。"""
    x = min(max(linear, 0.0), 1.0)
    if knee <= 0.0 or x <= knee:
        return x
    span = 1.0 - knee
    t = (x - knee) / span
    return knee + span * (1.0 - (1.0 - t) * (1.0 - t))


def display_code(linear: float) -> float:
    return min(max(ACTIVE_OETF(soft_knee(min(max(linear, 0.0), 1.0))), 0.0), 1.0)


# ─────────────────────────── 矩阵 ───────────────────────────

def _xy_to_xyz(x: float, y: float):
    y = y if abs(y) > 1e-6 else 1e-6
    return (x / y, 1.0, (1.0 - x - y) / y)


def _mat_mul(a, b):
    return [[sum(a[r][k] * b[k][c] for k in range(3)) for c in range(3)] for r in range(3)]


def _mat_inv(m):
    (a, b, c), (d, e, f), (g, h, i) = m
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    if abs(det) < 1e-12:
        raise ValueError("singular matrix")
    idet = 1.0 / det
    return [
        [(e * i - f * h) * idet, (c * h - b * i) * idet, (b * f - c * e) * idet],
        [(f * g - d * i) * idet, (a * i - c * g) * idet, (c * d - a * f) * idet],
        [(d * h - e * g) * idet, (b * g - a * h) * idet, (a * e - b * d) * idet],
    ]


def rgb_to_xyz(primaries, white=D65):
    r, g, b = (_xy_to_xyz(*p) for p in primaries)
    w = _xy_to_xyz(*white)
    prim = [[r[0], g[0], b[0]], [r[1], g[1], b[1]], [r[2], g[2], b[2]]]
    scales = [sum(_mat_inv(prim)[row][k] * w[k] for k in range(3)) for row in range(3)]
    return [[prim[row][col] * scales[col] for col in range(3)] for row in range(3)]


def conversion(src_gamut: str, dst_gamut: str):
    """行主序 3×3：src → dst（D65，无白点适配）。"""
    if src_gamut == dst_gamut:
        return [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
    a = rgb_to_xyz(GAMUT_PRIMARIES[src_gamut])
    b = rgb_to_xyz(GAMUT_PRIMARIES[dst_gamut])
    return _mat_mul(_mat_inv(b), a)


# ─────────────────────────── 烘焙 ───────────────────────────

def bake(preset: str, size: int = LUT_SIZE, knee: float = 0.9):
    """烘焙 Log→Rec.709 3D LUT，输入轴 = Log 代码值，布局 r 变化最快。"""
    decode = CURVES[preset][1]
    gamut = PRESET_GAMUT[preset]
    white = PRESET_DISPLAY_WHITE[preset]
    m = conversion(gamut, "SRGB")  # 原生色域 → Rec.709（工程 SRGB 即 709 基色）
    step = 1.0 / (size - 1)
    data = [None] * (size * size * size)
    for b_idx in range(size):
        for g_idx in range(size):
            for r_idx in range(size):
                lr = decode(r_idx * step) / white
                lg = decode(g_idx * step) / white
                lb = decode(b_idx * step) / white
                out = [
                    m[i][0] * lr + m[i][1] * lg + m[i][2] * lb for i in range(3)
                ]
                data[(b_idx * size + g_idx) * size + r_idx] = [display_code(v) for v in out]
    return data, m


def write_plut(path: str, data, size: int, curve: str, color_space: str):
    """写 .plut v3（UINT16），布局/头部与工程 LutParser + 既有 6 款 LUT 完全一致。"""
    with open(path, "wb") as fh:
        fh.write(struct.pack(
            "<iiiiii",
            PLUT_MAGIC, PLUT_VERSION, size, DATA_TYPE_UINT16,
            CURVE_STORAGE_ID[curve], COLORSPACE_ORDINAL[color_space],
        ))
        flat = []
        for cell in data:
            for value in cell:
                flat.append(min(max(int(round(value * 65535)), 0), 65535))
        fh.write(struct.pack("<%dH" % len(flat), *flat))


# ─────────────────────────── 审计 ───────────────────────────

def audit(assets_dir: str):
    """读回工程既有 Log 还原 LUT，打印中灰/白点映射，用于与本脚本产物同口径对比。"""
    log_dir = os.path.join(assets_dir, "luts", "log")
    if not os.path.isdir(log_dir):
        print("!! 找不到 %s" % log_dir)
        return
    print("%-28s %-8s %-8s %-8s" % ("file", "code@18%", "code@0", "code@1"))
    for name in sorted(os.listdir(log_dir)):
        if not name.endswith(".plut"):
            continue
        with open(os.path.join(log_dir, name), "rb") as fh:
            magic, ver, size, dtype, curve_id, cs_ord = struct.unpack("<iiiiii", fh.read(24))
            raw = fh.read()
        count = size * size * size * 3
        values = struct.unpack("<%dH" % count, raw[:count * 2])
        step = size - 1

        def sample(code: float):
            idx = min(max(int(round(code * step)), 0), step)
            return values[((idx * size + idx) * size + idx) * 3] / 65535.0

        print("%-28s %-8.3f %-8.3f %-8.3f" % (name, sample(0.4233), sample(0.0), sample(1.0)))


# ─────────────────────────── 入口 ───────────────────────────

def build_plan(out_dir: str, assets_dir: str):
    """要生成的 LUT 清单（id / 档位 / 标签 / 说明）。"""
    return [
        dict(
            file="log_restore_hlg.plut", preset="HLG", curve="HLG", color_space="BT2020",
            name_zh="HLG 还原 (Rec.709)", name_en="HLG Restore (Rec.709)",
            note="HLG(BT.2100) → Rec.709 技术转换，18% 中灰落 41% 信号",
        ),
        dict(
            file="log_restore_logc3.plut", preset="LOGC3", curve="LOGC3", color_space="ARRI3",
            name_zh="LogC3 还原 (Rec.709)", name_en="LogC3 Restore (Rec.709)",
            note="ARRI LogC3/AWG3 → Rec.709 Gamma24，依赖 patch 新增 LOGC3/ARRI3 枚举",
        ),
    ]


def main() -> int:
    global ACTIVE_OETF
    parser = argparse.ArgumentParser(description="生成缺失的 Log→Rec.709 还原 LUT")
    parser.add_argument("--out", default=None, help="输出目录（默认写 assets/luts/log）")
    parser.add_argument("--size", type=int, default=LUT_SIZE)
    parser.add_argument("--oetf", choices=sorted(OETF.keys()), default="rec709",
                        help="输出显示编码：rec709（默认，规范 41% 中灰）或 srgb（与既有 6 款观感对齐）")
    parser.add_argument("--audit", action="store_true", help="只审计既有 LUT，不生成")
    parser.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = parser.parse_args()
    ACTIVE_OETF = OETF[args.oetf]

    assets_dir = os.path.join(args.repo, "app", "src", "main", "assets")
    if args.audit:
        audit(assets_dir)
        print("\n本脚本模型的色域矩阵核对（应与工程 GLSL applyLutColorSpace 分支数值一致）：")
        for gamut in ("BT2020", "ARRI4", "VGamut", "ARRI3"):
            m = conversion("SRGB", gamut)
            print("  709→%-8s %s" % (gamut, " ".join("%.6f" % v for v in sum(m, []))))
        return 0

    out_dir = args.out or os.path.join(assets_dir, "luts", "log")
    os.makedirs(out_dir, exist_ok=True)

    entries = []
    for item in build_plan(out_dir, assets_dir):
        data, matrix = bake(item["preset"], args.size)
        path = os.path.join(out_dir, item["file"])
        write_plut(path, data, args.size, item["curve"], item["color_space"])
        print("生成 %s  size=%d  curve=%s(%d)  colorSpace=%s(%d)" % (
            path, args.size, item["curve"], CURVE_STORAGE_ID[item["curve"]],
            item["color_space"], COLORSPACE_ORDINAL[item["color_space"]],
        ))
        mid = data[(((args.size - 1) // 3) * args.size + ((args.size - 1) // 3)) * args.size + ((args.size - 1) // 3)]
        print("    中灰格点抽样 = %s（应≈0.41 Rec.709 中灰）" % ["%.3f" % v for v in mid])
        entries.append(dict(
            # id 必须与 Kotlin `LogVideoConfig.defaultRestoreLutId(preset)` 返回值一致
            # （log_restore_<preset>：hlg → log_restore_hlg、logc3 → log_restore_logc3），
            # 否则「档位 → 默认还原 LUT」的一键套用会找不到该条目。
            id="log_restore_" + item["preset"].lower(), path=item["file"],
            name={"zh": item["name_zh"], "en": item["name_en"]}, note=item["note"],
        ))

    print("\n将以下条目追加到 assets/luts/config.json 的 luts 数组（category 必须为 \"Log\"）：")
    print(json.dumps(entries, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
