// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 长曝光核心算法（C++/NDK）——PATTERN_SPEC §10：
//   1. 只下沉两段最热的主循环：`暗帧减法 + MAX 增量累加`（逐帧 O(N)）与
//      `月亮局部色调映射`（肩部曲线 + 可分离盒模糊 + 逐像素比例缩放）；
//   2. 工作分辨率 / 批大小 / 内存预算全部由 Kotlin 侧（LongExposureJobRunner、
//      guardMemory）决定，本层**不做任何内存分配决策**，只处理被明确交给它的缓冲；
//   3. 与 Kotlin 实现（DarkFrameSubtractor / MaxAccumulator / SuperMoonToneMapper）
//      **逐位一致**：全程 float 运算、round-half-up、无 -ffast-math（见 CMakeLists 补丁）。
//
// 对应的 Kotlin 声明：com.hinnka.mycamera.longexposure.LongExposureNative

#ifndef PHOTON_CAMERA_LONGEXPOSURE_LIGHT_PAINTING_CORE_H_
#define PHOTON_CAMERA_LONGEXPOSURE_LIGHT_PAINTING_CORE_H_

#include <cstdint>

namespace photon {
namespace longexposure {

// 与 LinearRgbPlane.SAMPLE_MAX 对齐（12bit 线性域上限）。
constexpr int32_t kSampleMax = 4095;

// 暗帧扣除增益范围，与 DarkFrameConfig.GAIN_MIN/GAIN_MAX 对齐。
constexpr float kGainMin = 0.0f;
constexpr float kGainMax = 2.0f;

// 月亮局部色调映射单像素最大放大比例，与 SuperMoonToneMapper.MAX_RATIO 对齐。
constexpr float kToneMapMaxRatio = 8.0f;

// 全局传递曲线 LUT 级数，与 SuperMoonToneMapper.GLOBAL_LUT_SIZE 对齐。
constexpr int32_t kToneMapLutSize = 1024;

// 返回值：>= 0 表示成功（值为被判定的坏点样本数）；-1 表示入参校验失败（调用方回退 Kotlin）。
constexpr int32_t kInvalidInput = -1;

/**
 * 暗帧减法 + MAX 增量累加（融合为一次遍历）。
 *
 * 语义（与 Kotlin 侧逐位一致）：
 *   value = clamp(frame[i] - round(dark[i] * gain), 0, kSampleMax)
 *   若 mask_hot_pixels && hot_threshold > 0 && dark[i] >= hot_threshold  -> value = 0（计为坏点）
 *   !seeded ? accum[i] = value : accum[i] = max(accum[i], value)
 *
 * @param frame            长度 sample_count，只读。
 * @param dark             长度 sample_count 或 nullptr（= 无暗帧）。
 * @param accum            长度 sample_count，读写（就地累加）。
 * @param sample_count     width * height * 3。
 * @param gain             暗电流扣除增益（内部 clamp 到 [kGainMin, kGainMax]）。
 * @param hot_threshold    坏点阈值（<= 0 关闭判定）。
 * @param mask_hot_pixels  是否启用坏点置零掩膜。
 * @param seeded           累加器是否已有基线（首帧 false）。
 * @return 坏点样本数；入参非法返回 kInvalidInput。
 */
int32_t SubtractMaxAccumulate(const int16_t* frame,
                              const int16_t* dark,
                              int16_t* accum,
                              int32_t sample_count,
                              float gain,
                              int32_t hot_threshold,
                              bool mask_hot_pixels,
                              bool seeded);

/**
 * 月亮局部色调映射（**就地**改写 samples）。
 *
 * 传递函数与 Kotlin SuperMoonToneMapper 完全一致：
 *   1) 高光肩部（严格单调、上界 < 1，不截断）：
 *        l > threshold ? threshold + (1 - threshold) * x / (1 + strength * x),  x = (l - threshold) / (1 - threshold)
 *   2) 暗部提升 l^(1/(1+shadow_lift))（0 -> 0）
 *   3) gamma：l^gamma
 *   4) 局部对比：global + local_amount * (global - blur(global)) * (1 - global)
 *   5) 逐通道按同一亮度比例缩放（保色相）：ratio = clamp(mapped / luma, 0, kToneMapMaxRatio)
 *
 * @param samples           长度 width*height*3，读写（就地）。
 * @param width/height      平面尺寸（像素）。
 * @return true = 成功；false = 入参非法/内存不足（调用方回退 Kotlin 实现）。
 */
bool ToneMap(int16_t* samples,
             int32_t width,
             int32_t height,
             float highlight_threshold,
             float highlight_strength,
             float shadow_lift,
             float gamma,
             int32_t local_radius,
             float local_amount);

}  // namespace longexposure
}  // namespace photon

#endif  // PHOTON_CAMERA_LONGEXPOSURE_LIGHT_PAINTING_CORE_H_
