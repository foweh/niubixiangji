// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 流式叠加计算层：C++ 核心 + JNI 桥。
//
// JNI 符号与 `com.hinnka.mycamera.nativecompute.NativeComputeBridge` 的 external 声明
// 逐参对齐（对齐表见 `delivery/selfchecks/nativecompute.md`）。
//
// 舍入约定：暗电流增益乘法一律用 round-half-up（= Kotlin roundToInt = floor(x + 0.5)），
// 与 DarkFrameSubtractor / LongExposureNative 完全一致；不使用 std::nearbyint。

#include "accumulate_core.h"

#include <jni.h>

#include <cmath>
#include <cstdint>

#include "nativecompute_common.h"

namespace photon {
namespace nativecompute {
namespace accumulate {
namespace {

/** 与 Kotlin `kotlin.math.roundToInt()`（floor(x + 0.5)）一致。 */
inline int32_t RoundHalfUp(float value) {
  return static_cast<int32_t>(std::floor(static_cast<double>(value) + 0.5));
}

/** 单样本的暗帧扣除结果（供 MAX / MEAN 两条路径共用，避免语义漂移）。 */
inline int32_t SubtractSample(int32_t sample,
                              int32_t dark_sample,
                              int32_t hot_threshold,
                              bool mask_hot_pixels,
                              bool has_dark,
                              float gain,
                              int32_t* hot_pixels) {
  if (mask_hot_pixels && hot_threshold > 0 && has_dark && dark_sample >= hot_threshold) {
    ++(*hot_pixels);
    return 0;
  }
  const int32_t value = sample - RoundHalfUp(static_cast<float>(dark_sample) * gain);
  return ClampInt(value, 0, kSampleMax);
}

}  // namespace

int32_t AccumulateMax(const int16_t* frame,
                      const int16_t* dark,
                      int16_t* accum,
                      int32_t sample_count,
                      float gain,
                      int32_t hot_threshold,
                      bool mask_hot_pixels,
                      bool seeded) {
  if (frame == nullptr || accum == nullptr || sample_count <= 0) {
    LogW("accumulate max: null buffer or non-positive sample count");
    return kInvalidInput;
  }
  const float g = IsFinite(gain) ? ClampFloat(gain, kGainMin, kGainMax) : 1.0f;
  const bool has_dark = dark != nullptr;
  const bool mask = mask_hot_pixels && hot_threshold > 0 && has_dark;
  int32_t hot_pixels = 0;
  for (int32_t i = 0; i < sample_count; ++i) {
    const int32_t dark_sample = has_dark ? static_cast<int32_t>(dark[i]) : 0;
    const int32_t value =
        SubtractSample(frame[i], dark_sample, hot_threshold, mask, has_dark, g, &hot_pixels);
    if (!seeded) {
      accum[i] = static_cast<int16_t>(value);
    } else if (value > static_cast<int32_t>(accum[i])) {
      accum[i] = static_cast<int16_t>(value);
    }
  }
  return hot_pixels;
}

bool AccumulateMean(int32_t* sum, const int16_t* frame, int32_t sample_count) {
  if (sum == nullptr || frame == nullptr || sample_count <= 0) {
    LogW("accumulate mean: null buffer or non-positive sample count");
    return false;
  }
  for (int32_t i = 0; i < sample_count; ++i) {
    sum[i] += static_cast<int32_t>(frame[i]);
  }
  return true;
}

int32_t SubtractMeanAccumulate(const int16_t* frame,
                               const int16_t* dark,
                               int32_t* sum,
                               int32_t sample_count,
                               float gain,
                               int32_t hot_threshold,
                               bool mask_hot_pixels) {
  if (frame == nullptr || sum == nullptr || sample_count <= 0) {
    LogW("subtract+mean: null buffer or non-positive sample count");
    return kInvalidInput;
  }
  const float g = IsFinite(gain) ? ClampFloat(gain, kGainMin, kGainMax) : 1.0f;
  const bool has_dark = dark != nullptr;
  const bool mask = mask_hot_pixels && hot_threshold > 0 && has_dark;
  int32_t hot_pixels = 0;
  for (int32_t i = 0; i < sample_count; ++i) {
    const int32_t dark_sample = has_dark ? static_cast<int32_t>(dark[i]) : 0;
    const int32_t value =
        SubtractSample(frame[i], dark_sample, hot_threshold, mask, has_dark, g, &hot_pixels);
    sum[i] += value;
  }
  return hot_pixels;
}

}  // namespace accumulate

// ---------------------------------------------------------------------------
// JNI 桥
// ---------------------------------------------------------------------------

namespace {

/** ShortArray 的 RAII 句柄。 */
class ShortArrayAccess {
 public:
  ShortArrayAccess(JNIEnv* env, jshortArray array, bool write_back)
      : env_(env), array_(array), write_back_(write_back) {
    if (env_ != nullptr && array_ != nullptr) {
      data_ = env_->GetShortArrayElements(array_, nullptr);
    }
  }

  ~ShortArrayAccess() {
    if (data_ != nullptr) {
      env_->ReleaseShortArrayElements(array_, data_, write_back_ ? 0 : JNI_ABORT);
    }
  }

  ShortArrayAccess(const ShortArrayAccess&) = delete;
  ShortArrayAccess& operator=(const ShortArrayAccess&) = delete;

  bool ok() const { return data_ != nullptr; }
  int16_t* data() const { return reinterpret_cast<int16_t*>(data_); }
  jsize length() const {
    return (env_ != nullptr && array_ != nullptr) ? env_->GetArrayLength(array_) : 0;
  }

 private:
  JNIEnv* env_;
  jshortArray array_;
  jshort* data_ = nullptr;
  bool write_back_;
};

/** IntArray 的 RAII 句柄。 */
class IntArrayAccess {
 public:
  IntArrayAccess(JNIEnv* env, jintArray array, bool write_back)
      : env_(env), array_(array), write_back_(write_back) {
    if (env_ != nullptr && array_ != nullptr) {
      data_ = env_->GetIntArrayElements(array_, nullptr);
    }
  }

  ~IntArrayAccess() {
    if (data_ != nullptr) {
      env_->ReleaseIntArrayElements(array_, data_, write_back_ ? 0 : JNI_ABORT);
    }
  }

  IntArrayAccess(const IntArrayAccess&) = delete;
  IntArrayAccess& operator=(const IntArrayAccess&) = delete;

  bool ok() const { return data_ != nullptr; }
  int32_t* data() const { return reinterpret_cast<int32_t*>(data_); }
  jsize length() const {
    return (env_ != nullptr && array_ != nullptr) ? env_->GetArrayLength(array_) : 0;
  }

 private:
  JNIEnv* env_;
  jintArray array_;
  jint* data_ = nullptr;
  bool write_back_;
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeAccumulateMax(
    JNIEnv* env,
    jobject /* thiz */,
    jshortArray frame,
    jshortArray dark,
    jshortArray accum,
    jint sample_count,
    jfloat gain,
    jint hot_threshold,
    jboolean mask_hot_pixels,
    jboolean seeded) {
  if (env == nullptr || frame == nullptr || accum == nullptr || sample_count <= 0) {
    LogW("jni accumulate max: invalid arguments");
    return static_cast<jint>(photon::nativecompute::accumulate::kInvalidInput);
  }
  if (env->GetArrayLength(frame) < sample_count || env->GetArrayLength(accum) < sample_count) {
    LogW("jni accumulate max: array length mismatch");
    return static_cast<jint>(photon::nativecompute::accumulate::kInvalidInput);
  }
  // dark 长度不符时按"无暗帧"处理（与 Kotlin takeIf { size == expected } 一致）。
  const bool has_dark = dark != nullptr && env->GetArrayLength(dark) >= sample_count;
  ShortArrayAccess frame_access(env, frame, false);
  ShortArrayAccess accum_access(env, accum, true);
  ShortArrayAccess dark_access(env, has_dark ? dark : nullptr, false);
  if (!frame_access.ok() || !accum_access.ok() || (has_dark && !dark_access.ok())) {
    LogW("jni accumulate max: acquisition failed");
    return static_cast<jint>(photon::nativecompute::accumulate::kInvalidInput);
  }
  const int32_t hot_pixels = photon::nativecompute::accumulate::AccumulateMax(
      frame_access.data(), dark_access.ok() ? dark_access.data() : nullptr, accum_access.data(),
      static_cast<int32_t>(sample_count), static_cast<float>(gain),
      static_cast<int32_t>(hot_threshold), mask_hot_pixels == JNI_TRUE, seeded == JNI_TRUE);
  return static_cast<jint>(hot_pixels);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeAccumulateMean(
    JNIEnv* env,
    jobject /* thiz */,
    jintArray sum,
    jshortArray frame,
    jint sample_count) {
  if (env == nullptr || sum == nullptr || frame == nullptr || sample_count <= 0) {
    LogW("jni accumulate mean: invalid arguments");
    return JNI_FALSE;
  }
  if (env->GetArrayLength(sum) < sample_count || env->GetArrayLength(frame) < sample_count) {
    LogW("jni accumulate mean: array length mismatch");
    return JNI_FALSE;
  }
  IntArrayAccess sum_access(env, sum, true);
  ShortArrayAccess frame_access(env, frame, false);
  if (!sum_access.ok() || !frame_access.ok()) {
    LogW("jni accumulate mean: acquisition failed");
    return JNI_FALSE;
  }
  const bool ok = photon::nativecompute::accumulate::AccumulateMean(
      sum_access.data(), frame_access.data(), static_cast<int32_t>(sample_count));
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeSubtractMeanAccumulate(
    JNIEnv* env,
    jobject /* thiz */,
    jshortArray frame,
    jshortArray dark,
    jintArray sum,
    jint sample_count,
    jfloat gain,
    jint hot_threshold,
    jboolean mask_hot_pixels) {
  if (env == nullptr || frame == nullptr || sum == nullptr || sample_count <= 0) {
    LogW("jni subtract+mean: invalid arguments");
    return static_cast<jint>(photon::nativecompute::accumulate::kInvalidInput);
  }
  if (env->GetArrayLength(frame) < sample_count || env->GetArrayLength(sum) < sample_count) {
    LogW("jni subtract+mean: array length mismatch");
    return static_cast<jint>(photon::nativecompute::accumulate::kInvalidInput);
  }
  const bool has_dark = dark != nullptr && env->GetArrayLength(dark) >= sample_count;
  ShortArrayAccess frame_access(env, frame, false);
  ShortArrayAccess dark_access(env, has_dark ? dark : nullptr, false);
  IntArrayAccess sum_access(env, sum, true);
  if (!frame_access.ok() || !sum_access.ok() || (has_dark && !dark_access.ok())) {
    LogW("jni subtract+mean: acquisition failed");
    return static_cast<jint>(photon::nativecompute::accumulate::kInvalidInput);
  }
  const int32_t hot_pixels = photon::nativecompute::accumulate::SubtractMeanAccumulate(
      frame_access.data(), dark_access.ok() ? dark_access.data() : nullptr, sum_access.data(),
      static_cast<int32_t>(sample_count), static_cast<float>(gain),
      static_cast<int32_t>(hot_threshold), mask_hot_pixels == JNI_TRUE);
  return static_cast<jint>(hot_pixels);
}
