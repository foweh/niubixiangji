// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 微距焦点堆叠的 C++ 计算核（PATTERN_SPEC §10）。
//
// 算法思路来源：PetteriAimonen/focus-stack（MIT，Star≈670，C++）的
// 「细节能量度量 → 逐像素加权融合」；本核按其思路实现为
// **拉普拉斯方差 / 梯度能量 + 软掩模**，与纯 Kotlin 参考实现 `FocusClarityMap` 数值对齐。
//
// 本层不做内存分配决策：工作分辨率、帧数、分片全部由 Kotlin 侧（FocusStackProcessor +
// BgJobRunner）决定并传入。

#ifndef PHOTON_FOCUSSTACK_CORE_H_
#define PHOTON_FOCUSSTACK_CORE_H_

namespace focusstack {

/** 清晰度度量，序号必须与 Kotlin `ClarityMetric.ordinal` 一致。 */
enum class Metric {
  kLaplacianVariance = 0,
  kGradientEnergy = 1,
};

/** 权重分母下限（与 Kotlin `FocusClarityMap.MASK_EPS` 一致）。 */
constexpr float kMaskEps = 1e-8f;

/** 一致性滤波阈值（与 Kotlin `CONSISTENCY_RATIO_THRESHOLD` 一致）。 */
constexpr float kConsistencyRatioThreshold = 1.5f;

constexpr int kClarityRadiusMin = 1;
constexpr int kClarityRadiusMax = 32;
constexpr int kMaskSmoothRadiusMin = 0;
constexpr int kMaskSmoothRadiusMax = 64;
constexpr int kConsistencyMin = 0;
constexpr int kConsistencyMax = 2;
constexpr int kHistBins = 256;

/**
 * 逐帧清晰度图。
 *
 * @param luma     长度 >= width*height 的亮度（0..1）。
 * @param out      长度 >= width*height 的输出清晰度（未归一化）。
 * @param radius   统计邻域半径（内部 clamp 到 [1, 32]）。
 * @return false 表示参数非法（调用方回退纯 Kotlin）。
 */
bool ComputeClarity(const float* luma,
                    float* out,
                    int width,
                    int height,
                    int metric,
                    int radius);

/**
 * 软掩模：逐帧归一化 → 权重指数 → 跨帧归一化 → 高斯平滑 → 邻域一致性滤波。
 *
 * @param clarity_flat 长度 frame_count*width*height 的清晰度（frame-major 扁平布局）。
 * @param mask_out     同形状输出。
 * @return false 表示参数非法（调用方回退纯 Kotlin）。
 */
bool SoftMask(const float* clarity_flat,
              float* mask_out,
              int frame_count,
              int width,
              int height,
              float exponent,
              int smooth_radius,
              int consistency);

}  // namespace focusstack

#endif  // PHOTON_FOCUSSTACK_CORE_H_
