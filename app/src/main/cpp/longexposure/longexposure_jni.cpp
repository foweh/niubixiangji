// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 长曝光 JNI 桥。JNI 符号与 Kotlin 声明必须严格对齐（对齐表见
// delivery/selfchecks/longexposure.md）：
//
//   Kotlin: com.hinnka.mycamera.longexposure.LongExposureNative
//             .nativeSubtractMaxAccumulate(ShortArray, ShortArray?, ShortArray,
//                                         Int, Float, Int, Boolean, Boolean): Int
//             .nativeToneMap(ShortArray, Int, Int, Float, Float, Float, Float, Int, Float): Boolean
//
// 约定：本层**只做入参校验 + 取指针 + 调核心算法**；工作分辨率、批大小、内存预算
// 全部由 Kotlin 侧决定（PATTERN_SPEC §10.6）。任何校验失败都返回"无效"值
// （-1 / JNI_FALSE），由 Kotlin 侧无痛回退纯 Kotlin 实现。

#include "light_painting_core.h"

#include <android/log.h>
#include <jni.h>

namespace {

constexpr char kLogTag[] = "PLog_LongExposureNative";

void LogW(const char* message) {
  __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_longexposure_LongExposureNative_nativeSubtractMaxAccumulate(
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
    LogW("subtract+max: null array or non-positive sample count");
    return photon::longexposure::kInvalidInput;
  }
  if (env->GetArrayLength(frame) != sample_count || env->GetArrayLength(accum) != sample_count) {
    LogW("subtract+max: array length mismatch");
    return photon::longexposure::kInvalidInput;
  }
  // dark 长度不符时按"无暗帧"处理（与 Kotlin 的 takeIf { size == expected } 一致）。
  const bool has_dark =
      dark != nullptr && env->GetArrayLength(dark) == sample_count;

  jshort* frame_ptr = env->GetShortArrayElements(frame, nullptr);
  if (frame_ptr == nullptr) {
    LogW("subtract+max: GetShortArrayElements(frame) failed");
    return photon::longexposure::kInvalidInput;
  }
  jshort* accum_ptr = env->GetShortArrayElements(accum, nullptr);
  if (accum_ptr == nullptr) {
    env->ReleaseShortArrayElements(frame, frame_ptr, JNI_ABORT);
    LogW("subtract+max: GetShortArrayElements(accum) failed");
    return photon::longexposure::kInvalidInput;
  }
  jshort* dark_ptr = nullptr;
  if (has_dark) {
    dark_ptr = env->GetShortArrayElements(dark, nullptr);
    if (dark_ptr == nullptr) {
      LogW("subtract+max: GetShortArrayElements(dark) failed, continue without dark frame");
    }
  }

  const int32_t hot_pixels = photon::longexposure::SubtractMaxAccumulate(
      reinterpret_cast<const int16_t*>(frame_ptr),
      dark_ptr != nullptr ? reinterpret_cast<const int16_t*>(dark_ptr) : nullptr,
      reinterpret_cast<int16_t*>(accum_ptr),
      static_cast<int32_t>(sample_count),
      static_cast<float>(gain),
      static_cast<int32_t>(hot_threshold),
      mask_hot_pixels == JNI_TRUE,
      seeded == JNI_TRUE);

  if (dark_ptr != nullptr) env->ReleaseShortArrayElements(dark, dark_ptr, JNI_ABORT);
  env->ReleaseShortArrayElements(frame, frame_ptr, JNI_ABORT);
  env->ReleaseShortArrayElements(accum, accum_ptr, 0);
  return static_cast<jint>(hot_pixels);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_longexposure_LongExposureNative_nativeToneMap(
    JNIEnv* env,
    jobject /* thiz */,
    jshortArray samples,
    jint width,
    jint height,
    jfloat highlight_threshold,
    jfloat highlight_strength,
    jfloat shadow_lift,
    jfloat gamma,
    jint local_radius,
    jfloat local_amount) {
  if (env == nullptr || samples == nullptr || width <= 0 || height <= 0) {
    LogW("tone map: invalid arguments");
    return JNI_FALSE;
  }
  const jsize length = env->GetArrayLength(samples);
  const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height) * 3;
  if (static_cast<int64_t>(length) != expected) {
    LogW("tone map: sample count mismatch");
    return JNI_FALSE;
  }

  jshort* samples_ptr = env->GetShortArrayElements(samples, nullptr);
  if (samples_ptr == nullptr) {
    LogW("tone map: GetShortArrayElements failed");
    return JNI_FALSE;
  }
  const bool ok = photon::longexposure::ToneMap(
      reinterpret_cast<int16_t*>(samples_ptr),
      static_cast<int32_t>(width),
      static_cast<int32_t>(height),
      static_cast<float>(highlight_threshold),
      static_cast<float>(highlight_strength),
      static_cast<float>(shadow_lift),
      static_cast<float>(gamma),
      static_cast<int32_t>(local_radius),
      static_cast<float>(local_amount));
  env->ReleaseShortArrayElements(samples, samples_ptr, ok ? 0 : JNI_ABORT);
  return ok ? JNI_TRUE : JNI_FALSE;
}
