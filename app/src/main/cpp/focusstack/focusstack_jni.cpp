// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 微距焦点堆叠 JNI 桥。JNI 符号与 Kotlin 声明必须严格对齐（对齐表见
// delivery/selfchecks/focusstack.md）：
//
//   Kotlin: com.hinnka.mycamera.focusstack.FocusStackNative
//             .nativeComputeClarity(FloatArray, FloatArray, Int, Int, Int, Int): Boolean
//             .nativeSoftMask(FloatArray, FloatArray, Int, Int, Int, Float, Int, Int): Boolean
//
// 约定：本层**只做入参校验 + 取数组 + 调核心算法**；工作分辨率、帧数、分片、内存预算
// 全部由 Kotlin 侧决定（PATTERN_SPEC §10.6）。任何校验失败都返回 JNI_FALSE，
// 由 Kotlin 侧无痛回退纯 Kotlin 实现（绝不崩溃/闪退）。

#include "focusstack_core.h"

#include <android/log.h>
#include <jni.h>

#include <cstddef>
#include <vector>

namespace {

constexpr char kLogTag[] = "PLog_FocusStackNative";

void LogW(const char* message) {
  __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message);
}

/** 数组长度是否满足要求（负长度直接判非法）。 */
inline bool LengthAtLeast(jsize actual, long long required) {
  return actual >= 0 && static_cast<long long>(actual) >= required;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_focusstack_FocusStackNative_nativeComputeClarity(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray luma,
    jfloatArray out,
    jint width,
    jint height,
    jint metric,
    jint radius) {
  if (luma == nullptr || out == nullptr) return JNI_FALSE;
  if (width <= 0 || height <= 0) return JNI_FALSE;
  const long long size = static_cast<long long>(width) * static_cast<long long>(height);
  if (!LengthAtLeast(env->GetArrayLength(luma), size) ||
      !LengthAtLeast(env->GetArrayLength(out), size)) {
    LogW("clarity: array too small");
    return JNI_FALSE;
  }

  std::vector<float> luma_buffer(static_cast<size_t>(size));
  env->GetFloatArrayRegion(luma, 0, static_cast<jsize>(size), luma_buffer.data());
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    LogW("clarity: read luma failed");
    return JNI_FALSE;
  }
  std::vector<float> out_buffer(static_cast<size_t>(size));

  const bool ok = focusstack::ComputeClarity(
      luma_buffer.data(), out_buffer.data(), width, height, metric, radius);
  if (!ok) {
    LogW("clarity: invalid arguments");
    return JNI_FALSE;
  }

  env->SetFloatArrayRegion(out, 0, static_cast<jsize>(size), out_buffer.data());
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    LogW("clarity: write out failed");
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_focusstack_FocusStackNative_nativeSoftMask(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray clarity_flat,
    jfloatArray mask_out,
    jint frame_count,
    jint width,
    jint height,
    jfloat exponent,
    jint smooth_radius,
    jint consistency) {
  if (clarity_flat == nullptr || mask_out == nullptr) return JNI_FALSE;
  if (frame_count <= 0 || width <= 0 || height <= 0) return JNI_FALSE;
  const long long size = static_cast<long long>(frame_count) *
                         static_cast<long long>(width) *
                         static_cast<long long>(height);
  if (!LengthAtLeast(env->GetArrayLength(clarity_flat), size) ||
      !LengthAtLeast(env->GetArrayLength(mask_out), size)) {
    LogW("soft mask: array too small");
    return JNI_FALSE;
  }

  std::vector<float> clarity_buffer(static_cast<size_t>(size));
  env->GetFloatArrayRegion(clarity_flat, 0, static_cast<jsize>(size), clarity_buffer.data());
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    LogW("soft mask: read clarity failed");
    return JNI_FALSE;
  }
  std::vector<float> mask_buffer(static_cast<size_t>(size));

  const bool ok = focusstack::SoftMask(
      clarity_buffer.data(), mask_buffer.data(), frame_count, width, height, exponent,
      smooth_radius, consistency);
  if (!ok) {
    LogW("soft mask: invalid arguments");
    return JNI_FALSE;
  }

  env->SetFloatArrayRegion(mask_out, 0, static_cast<jsize>(size), mask_buffer.data());
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    LogW("soft mask: write out failed");
    return JNI_FALSE;
  }
  return JNI_TRUE;
}
