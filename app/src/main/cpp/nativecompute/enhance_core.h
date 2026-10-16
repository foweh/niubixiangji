// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 经典后处理计算层核心 —— C++/NDK 实现（CLAHE + 快速双边 + 反锐化掩模）。
//
// 与 `com.hinnka.mycamera.enhance` 的纯 Kotlin 实现**镜像**：
//   * CLAHE（逐 tile 直方图 → 裁剪重分配 → CDF LUT → tile 间双线性插值）↔ ClaheProcessor
//   * 亮度均衡后的逐通道增益缩放（保色）                                ↔ ClaheProcessor.equalizeLumaInPlace
//   * 快速双边（空间核可分离 + 亮度差值域核，行带分片）                   ↔ BilateralFilterProcessor.filterRows
//   * 反锐化掩模（三次盒式模糊近似高斯 + 阈值门控）                       ↔ UnsharpMaskProcessor + BoxBlur
//
// 复用说明（PATTERN_SPEC §9 与 §10.2）：
//   算法思路来自 opencv/opencv（Apache-2.0，Star≈91191）的 `clahe.cpp` / `bilateral_filter.dispatch.cpp`；
//   但 OpenCV 的 arm64 动态库体积（单个算法需整库 ≈ 20MB+）超出本工程体积预算，
//   故**只参考思路**、代码为自写（与 Kotlin 侧同一份算法，保持两条路径同值）。
//
// 退化保护（比 OpenCV 更稳，与 Kotlin 一致）：常量 tile（occupiedBins ≤ 1）与
// `area - cdfMin <= 0` 一律退化为**恒等映射**，绝不把纯色块压黑或除零。
//
// 分片：所有入口都是"调用方给多少处理多少"，行带由 Kotlin 侧（BilateralRows 的
// row_start/row_end）传入，Kotlin 在每个分片末尾继续调 `job.checkYield()`（§10.6）。

#ifndef PHOTON_CAMERA_NATIVECOMPUTE_ENHANCE_CORE_H_
#define PHOTON_CAMERA_NATIVECOMPUTE_ENHANCE_CORE_H_

#include <cstdint>

namespace photon {
namespace nativecompute {
namespace enhance {

/** CLAHE 直方图 bin 数（8bit 亮度，与 ClaheProcessor.LUMA_BINS 对齐）。 */
constexpr int32_t kLumaBins = 256;

/** tile 网格数的允许范围（与 ClaheConfig.TILES_MIN/TILES_MAX 对齐）。 */
constexpr int32_t kTilesMin = 1;
constexpr int32_t kTilesMax = 32;

/** 值域核 LUT 档数（与 MfsrRefiner 的 512 档保持同一实现习惯）。 */
constexpr int32_t kRangeLutSize = 512;

/**
 * CLAHE 均衡（0..1 亮度域），等价 `ClaheProcessor.equalize`。
 *
 * @param luma        输入亮度（长度须 >= width*height）
 * @param out         输出亮度（长度须 >= width*height，可与 luma 同缓冲）
 * @param tiles_x/y   tile 网格，内部 clamp 到 [1, 32] 且不超过图像尺寸
 * @param clip_limit  对比度限制倍数（非有限值按 0 处理，等价 Kotlin safeClipLimit）
 * @return true = 成功；false = 入参非法（调用方回退 Kotlin）
 */
bool ClaheEqualize(const float* luma,
                   int32_t width,
                   int32_t height,
                   int32_t tiles_x,
                   int32_t tiles_y,
                   float clip_limit,
                   float* out);

/**
 * 亮度均衡后的 RGB 增益缩放（保色），等价 `ClaheProcessor.equalizeLumaInPlace`。
 *
 * 语义：`target = mix(l, equalized[i], mix)`；`target == l` 跳过；`l > 1e-3` 按
 * 比例缩放三通道（clamp 0..1），否则做加性补偿（避免放大黑位彩噪）。三通道**就地**改写。
 *
 * @param count 处理样本数（调用方传 min(image.count, width*height)）
 * @return true = 成功；false = 入参非法（调用方回退 Kotlin）
 */
bool ApplyLumaGain(float* r,
                   float* g,
                   float* b,
                   const float* luma,
                   const float* equalized,
                   int32_t count,
                   float mix);

/**
 * 快速双边滤波（平面 RGB，指定行带），等价 `BilateralFilterProcessor.filterRows`。
 *
 * 只写 [row_start, row_end) 行，其余行不动；输出为**未混合**的滤波结果
 * （与原图的 strength 混合仍由 Kotlin 侧的 `process` 完成，保持与 Kotlin 同一分工）。
 *
 * @return true = 成功；false = 入参非法（调用方回退 Kotlin）
 */
bool BilateralRows(const float* src_r,
                   const float* src_g,
                   const float* src_b,
                   float* dst_r,
                   float* dst_g,
                   float* dst_b,
                   int32_t width,
                   int32_t height,
                   int32_t row_start,
                   int32_t row_end,
                   int32_t radius,
                   float sigma_space,
                   float sigma_color);

/**
 * 单通道反锐化掩模（含三次盒式模糊近似高斯），等价
 * `UnsharpMaskProcessor.process` 的单通道分支（[BoxBlur.gaussianApprox] + 阈值门控）。
 *
 * @param channel 就地改写，长度须 >= width*height
 * @param radius  盒式半径（整数；<= 0 视为退化，调用方自行处理）
 * @param threshold 弱边抑制阈值（0..255 单位，<= 0 关闭门控）
 * @return true = 成功；false = 入参非法（调用方回退 Kotlin）
 */
bool UnsharpChannel(float* channel,
                    int32_t width,
                    int32_t height,
                    int32_t radius,
                    float amount,
                    int32_t threshold);

}  // namespace enhance
}  // namespace nativecompute
}  // namespace photon

#endif  // PHOTON_CAMERA_NATIVECOMPUTE_ENHANCE_CORE_H_
