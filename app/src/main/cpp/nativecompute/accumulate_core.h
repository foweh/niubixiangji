// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 流式叠加计算层核心 —— C++/NDK 实现（MAX / MEAN + 暗帧扣除）。
//
// 与 `com.hinnka.mycamera.longexposure` 的纯 Kotlin 实现**镜像**：
//   * MAX 累加 + 暗帧扣除  ↔ DarkFrameSubtractor.subtractInto + MaxAccumulator.addFrame
//   * MEAN 累加（求和）    ↔ MeanAccumulator.addFrame
//   * MEAN + 暗帧扣除      ↔ DarkFrameSubtractor.subtractInto + MeanAccumulator.addFrame
//
// 说明（诚实标注，避免功能重复）：
//   长曝光模块的 **MAX** 路径此前已由 `LongExposureNative`（cpp/longexposure/）下沉到 C++，
//   因此本文件的主接入点是**尚未 native 化的 MEAN 路径**；MAX 入口一并提供，便于：
//     ① 需要"单一计算层入口"时替换既有 LongExposureNative；
//     ② 其它模块（如烟火/堆栈）复用同一份语义实现。
//   两条 MAX 实现的算式与舍入完全一致（同为 floor(x + 0.5) 的 round-half-up）。
//
// 内存特性（PATTERN_SPEC §4）：全部**就地**累加，O(1) 常驻（1 个累加器 + 1 帧），
// 帧数增长只拉长耗时、不放大峰值内存；帧级内存/落盘/节流决策仍由 Kotlin 侧负责。

#ifndef PHOTON_CAMERA_NATIVECOMPUTE_ACCUMULATE_CORE_H_
#define PHOTON_CAMERA_NATIVECOMPUTE_ACCUMULATE_CORE_H_

#include <cstdint>

namespace photon {
namespace nativecompute {
namespace accumulate {

/** 入参非法时的哨兵返回值（Kotlin 侧据此回退纯 Kotlin 实现）。 */
constexpr int32_t kInvalidInput = -1;

/** 线性域样本上限（与 LinearRgbPlane.SAMPLE_MAX 对齐，12bit）。 */
constexpr int32_t kSampleMax = 4095;

/** 暗帧扣除增益允许范围（与 DarkFrameConfig.GAIN_MIN/GAIN_MAX 对齐）。 */
constexpr float kGainMin = 0.0f;
constexpr float kGainMax = 2.0f;

/**
 * 暗帧减法 + MAX 累加（融合为一次遍历）。
 *
 * 语义（与 Kotlin 逐位一致）：
 *   value = clamp(frame[i] - round(dark[i] * gain), 0, kSampleMax)
 *   若 mask_hot_pixels && hot_threshold > 0 && dark[i] >= hot_threshold -> value = 0（计为坏点）
 *   !seeded ? accum[i] = value : accum[i] = max(accum[i], value)
 *
 * @param dark 可为 nullptr（= 无暗帧）
 * @param seeded 累加器是否已有基线（首帧传 false）
 * @return 坏点样本数；入参非法返回 kInvalidInput
 */
int32_t AccumulateMax(const int16_t* frame,
                      const int16_t* dark,
                      int16_t* accum,
                      int32_t sample_count,
                      float gain,
                      int32_t hot_threshold,
                      bool mask_hot_pixels,
                      bool seeded);

/**
 * MEAN 累加（求和）：`sum[i] += frame[i]`。
 *
 * 与 Kotlin `MeanAccumulator.addFrame` 一致：累加用 int32（12bit × ≤4096 帧 ≈ 1.7e7，
 * 远低于 int32 上限），**不做饱和**，以保持与 Kotlin 完全同值。
 *
 * @return true = 成功；false = 入参非法
 */
bool AccumulateMean(int32_t* sum, const int16_t* frame, int32_t sample_count);

/**
 * MEAN 累加 + 暗帧扣除（融合为一次遍历）。
 *
 * 语义：`value = clamp(frame[i] - round(dark[i] * gain), 0, kSampleMax)`，
 * 坏点掩膜命中时 value = 0 并计数；随后 `sum[i] += value`
 * （等价 Kotlin 的 `subtractInto(..., scratch)` 后 `mean.addFrame(scratch)`）。
 *
 * @return 坏点样本数；入参非法返回 kInvalidInput
 */
int32_t SubtractMeanAccumulate(const int16_t* frame,
                               const int16_t* dark,
                               int32_t* sum,
                               int32_t sample_count,
                               float gain,
                               int32_t hot_threshold,
                               bool mask_hot_pixels);

}  // namespace accumulate
}  // namespace nativecompute
}  // namespace photon

#endif  // PHOTON_CAMERA_NATIVECOMPUTE_ACCUMULATE_CORE_H_
