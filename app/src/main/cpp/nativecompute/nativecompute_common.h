// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// PhotonCamera 计算层（nativecompute）公共头。
//
// 背景（PATTERN_SPEC §10）：把原本纯 Kotlin 的重计算（多帧超分 MFSR、长曝光叠加、
// 经典后处理）下沉到 C++/NDK，Kotlin 侧通过 `com.hinnka.mycamera.nativecompute.NativeComputeBridge`
// 调用；native 不可用时由 Kotlin 侧自动回退，两条路径的**算式与累加顺序一致**。
//
// 本头文件只提供三件事：
//   1. 数值约定（clamp / round-half-up / Kotlin 数学函数的等价写法）；
//   2. 统一日志（tag = PLog_NativeCompute）；
//   3. JNI ABI 版本（Kotlin 侧探针用，防止"库能加载但符号是旧版"的静默错配）。

#ifndef PHOTON_CAMERA_NATIVECOMPUTE_COMMON_H_
#define PHOTON_CAMERA_NATIVECOMPUTE_COMMON_H_

#include <android/log.h>

#include <cmath>
#include <cstdint>

namespace photon {
namespace nativecompute {

/**
 * JNI ABI 版本。
 *
 * Kotlin 侧 [NativeComputeBridge.available] 会调用 `nativeAbiVersion()` 探针：
 * 只有返回该值才认为 native 可用。这样"能加载 .so 但缺少本次新增符号"的场景
 * 会被判为不可用并回退纯 Kotlin，而不是在运行中抛 UnsatisfiedLinkError。
 *
 * 任何 JNI 签名/语义变更都必须递增此值。
 */
constexpr int32_t kAbiVersion = 1;

/** 统一日志 tag（与既有 native 模块的 PLog_* 命名一致）。 */
constexpr char kLogTag[] = "PLog_NativeCompute";

inline void LogI(const char* message) {
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message);
}

inline void LogW(const char* message) {
  __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message);
}

/** 有限性判定（NaN / ±Inf 都为 false，与 Kotlin `Float.isFinite()` 同义）。 */
inline bool IsFinite(float v) { return std::isfinite(v) != 0; }

inline bool IsFinite(double v) { return std::isfinite(v) != 0; }

inline int32_t ClampInt(int32_t value, int32_t low, int32_t high) {
  if (value < low) return low;
  if (value > high) return high;
  return value;
}

/** NaN 归 low（与 Kotlin `coerceIn` 前先判 NaN 的写法等价）。 */
inline float ClampFloat(float value, float low, float high) {
  if (!(value == value)) return low;
  if (value < low) return low;
  if (value > high) return high;
  return value;
}

/**
 * Kotlin `Float.coerceIn(min, max)` 的精确语义：**NaN 原样透传**（两个比较都为 false）。
 *
 * 与 [ClampFloat]（NaN → low）刻意区分：Kotlin 侧凡"先判 isFinite 再 coerceIn"的写法用
 * [ClampFloat]，凡直接 coerceIn 的写法（如 `(a / b).coerceIn(0f, 1f)`）必须用本函数，
 * 保证两条路径在异常输入下的结果也一致。
 */
inline float CoerceInFloat(float value, float low, float high) {
  if (value < low) return low;
  if (value > high) return high;
  return value;
}

/** Kotlin `Float.coerceAtLeast(min)`：NaN 原样透传。 */
inline float CoerceAtLeastFloat(float value, float min_value) {
  return value < min_value ? min_value : value;
}

/**
 * 与 Kotlin `kotlin.math.roundToInt()`（= `java.lang.Math.round`，即 floor(x + 0.5)）一致。
 * 刻意不用 std::nearbyint / std::rint（其舍入模式受 FP 环境影响）。
 */
inline int32_t RoundHalfUp(float value) {
  return static_cast<int32_t>(std::floor(static_cast<double>(value) + 0.5));
}

// ---------------------------------------------------------------------------
// Kotlin 数学函数等价实现。
//
// kotlin.math 的 Float 重载实现是 `Math.xxx(v.toDouble()).toFloat()`（先升 double 再回落
// float，只四舍五入一次），因此这里同样用 double 版 libm 再 static_cast<float>，
// 保证与 Kotlin 同值，而不是直接用 float 版 libm（那是另一次舍入）。
// ---------------------------------------------------------------------------

inline float CosF(float v) { return static_cast<float>(std::cos(static_cast<double>(v))); }

inline float SinF(float v) { return static_cast<float>(std::sin(static_cast<double>(v))); }

inline float ExpF(float v) { return static_cast<float>(std::exp(static_cast<double>(v))); }

inline float SqrtF(float v) { return static_cast<float>(std::sqrt(static_cast<double>(v))); }

inline float CeilF(float v) { return static_cast<float>(std::ceil(static_cast<double>(v))); }

/** Kotlin `(x).toInt()` 的截断语义（向零取整），与 C++ static_cast 一致。 */
inline int32_t TruncToInt(float v) {
  if (!IsFinite(v)) return 0;
  return static_cast<int32_t>(v);
}

/**
 * 0..1 浮点 → 0..255 字节通道，等价 `PlanarRgb.toByteChannel`：
 * NaN → 0，<= 0 → 0，>= 1 → 255，其余 `(v * 255 + 0.5)` 截断。
 */
inline int32_t ToByteChannel(float v) {
  if (!(v == v)) return 0;
  if (v <= 0.0f) return 0;
  if (v >= 1.0f) return 255;
  return ClampInt(TruncToInt(v * 255.0f + 0.5f), 0, 255);
}

/** 线性混合 `a·(1-k) + b·k`，k 先 clamp 到 [0,1]（NaN → 0），等价 `PlanarRgb.mix`。 */
inline float MixFloat(float a, float b, float t) {
  const float k = ClampFloat(t, 0.0f, 1.0f);
  return a * (1.0f - k) + b * k;
}

}  // namespace nativecompute
}  // namespace photon

#endif  // PHOTON_CAMERA_NATIVECOMPUTE_COMMON_H_
