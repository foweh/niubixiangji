// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 全景拼接 JNI 桥。JNI 符号与 Kotlin 声明必须严格对齐（对齐表见
// delivery/selfchecks/funshot.md，逐条列了参数与顺序）：
//
//   Kotlin: com.hinnka.mycamera.funshot.FunshotPanoramaNative
//     .nativeHarrisCorners(FloatArray, Int, Int, Int, Float, Int): FloatArray?
//     .nativeBriefDescribe(FloatArray, Int, Int, FloatArray, Int, Long): LongArray?
//     .nativeMatchFeatures(LongArray, Int, LongArray, Int, Int, Float, Int, Int): IntArray?
//     .nativeEstimateHomography(DoubleArray, DoubleArray, Int, Int, Double, Int, Double, Long): DoubleArray?
//
// 约定（PATTERN_SPEC §10）：
//   * 本层**只做入参校验 + 取指针 + 调核心算法**；工作分辨率、迭代次数、阈值全部由 Kotlin 决定；
//   * 所有失败路径返回 nullptr → Kotlin 侧无痛回退纯 Kotlin 实现，绝不抛异常穿透到 JVM；
//   * 数组下标沿用 Kotlin 侧约定（角点 [x,y,score]×n、匹配 [ia,ib,dist]×k、单应 [n,h0..h8,flags]）。

#include "funshot_panorama.h"

#include <android/log.h>
#include <jni.h>

#include <cstdint>
#include <vector>

namespace {

constexpr char kLogTag[] = "PLog_FunshotPanorama";

void LogW(const char* message) {
  __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message);
}

inline bool LengthIs(jsize actual, long long expected) {
  return static_cast<long long>(actual) == expected;
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hinnka_mycamera_funshot_FunshotPanoramaNative_nativeHarrisCorners(
    JNIEnv* env, jobject /* thiz */, jfloatArray gray, jint width, jint height, jint max_corners,
    jfloat quality, jint min_distance) {
  if (env == nullptr || gray == nullptr) return nullptr;
  if (width < 5 || height < 5 || max_corners <= 0) return nullptr;
  if (!LengthIs(env->GetArrayLength(gray), static_cast<long long>(width) * height)) {
    LogW("harris: gray length mismatch");
    return nullptr;
  }
  const float* pixels = nullptr;
  jboolean is_copy = JNI_FALSE;
  try {
    pixels = static_cast<const float*>(env->GetPrimitiveArrayCritical(gray, &is_copy));
    if (pixels == nullptr) return nullptr;
    funshot::GrayView view{pixels, width, height};
    const std::vector<float> corners =
        funshot::HarrisCorners(view, max_corners, quality, min_distance);
    env->ReleasePrimitiveArrayCritical(gray, const_cast<float*>(pixels), JNI_ABORT);
    pixels = nullptr;
    const jsize size = static_cast<jsize>(corners.size());
    jfloatArray out = env->NewFloatArray(size);
    if (out == nullptr) return nullptr;
    if (size > 0) env->SetFloatArrayRegion(out, 0, size, corners.data());
    return out;
  } catch (...) {
    if (pixels != nullptr) {
      env->ReleasePrimitiveArrayCritical(gray, const_cast<float*>(pixels), JNI_ABORT);
    }
    LogW("harris: native exception, falling back");
    return nullptr;
  }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_hinnka_mycamera_funshot_FunshotPanoramaNative_nativeBriefDescribe(
    JNIEnv* env, jobject /* thiz */, jfloatArray gray, jint width, jint height,
    jfloatArray key_points, jint bits, jlong seed) {
  if (env == nullptr || gray == nullptr || key_points == nullptr) return nullptr;
  if (width <= 0 || height <= 0) return nullptr;
  if (bits < 64 || bits > 512) return nullptr;
  if (!LengthIs(env->GetArrayLength(gray), static_cast<long long>(width) * height)) {
    LogW("brief: gray length mismatch");
    return nullptr;
  }
  const jsize point_values = env->GetArrayLength(key_points);
  if (point_values <= 0 || point_values % 2 != 0) {
    LogW("brief: key point array must be [x, y] pairs");
    return nullptr;
  }
  const int point_count = point_values / 2;
  const int words = bits / 64;

  const float* gray_pixels = nullptr;
  const float* points = nullptr;
  jboolean is_copy = JNI_FALSE;
  try {
    gray_pixels = static_cast<const float*>(env->GetPrimitiveArrayCritical(gray, &is_copy));
    if (gray_pixels == nullptr) return nullptr;
    points = static_cast<const float*>(env->GetPrimitiveArrayCritical(key_points, &is_copy));
    if (points == nullptr) {
      env->ReleasePrimitiveArrayCritical(gray, const_cast<float*>(gray_pixels), JNI_ABORT);
      return nullptr;
    }
    funshot::GrayView view{gray_pixels, width, height};
    const std::vector<int64_t> descriptors =
        funshot::BriefDescribe(view, points, point_count, bits, static_cast<int64_t>(seed));
    env->ReleasePrimitiveArrayCritical(key_points, const_cast<float*>(points), JNI_ABORT);
    points = nullptr;
    env->ReleasePrimitiveArrayCritical(gray, const_cast<float*>(gray_pixels), JNI_ABORT);
    gray_pixels = nullptr;
    if (static_cast<int>(descriptors.size()) != point_count * words) {
      LogW("brief: unexpected descriptor length");
      return nullptr;
    }
    const jsize size = static_cast<jsize>(descriptors.size());
    jlongArray out = env->NewLongArray(size);
    if (out == nullptr) return nullptr;
    if (size > 0) {
      env->SetLongArrayRegion(out, 0, size, reinterpret_cast<const jlong*>(descriptors.data()));
    }
    return out;
  } catch (...) {
    if (points != nullptr) {
      env->ReleasePrimitiveArrayCritical(key_points, const_cast<float*>(points), JNI_ABORT);
    }
    if (gray_pixels != nullptr) {
      env->ReleasePrimitiveArrayCritical(gray, const_cast<float*>(gray_pixels), JNI_ABORT);
    }
    LogW("brief: native exception, falling back");
    return nullptr;
  }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_hinnka_mycamera_funshot_FunshotPanoramaNative_nativeMatchFeatures(
    JNIEnv* env, jobject /* thiz */, jlongArray desc_a, jint count_a, jlongArray desc_b,
    jint count_b, jint words, jfloat ratio, jint max_matches, jint max_distance) {
  if (env == nullptr || desc_a == nullptr || desc_b == nullptr) return nullptr;
  if (count_a <= 0 || count_b <= 0 || words <= 0 || words > 8) return nullptr;
  if (!LengthIs(env->GetArrayLength(desc_a), static_cast<long long>(count_a) * words) ||
      !LengthIs(env->GetArrayLength(desc_b), static_cast<long long>(count_b) * words)) {
    LogW("match: descriptor length mismatch");
    return nullptr;
  }
  const int64_t* a = nullptr;
  const int64_t* b = nullptr;
  jboolean is_copy = JNI_FALSE;
  try {
    a = reinterpret_cast<const int64_t*>(env->GetPrimitiveArrayCritical(desc_a, &is_copy));
    if (a == nullptr) return nullptr;
    b = reinterpret_cast<const int64_t*>(env->GetPrimitiveArrayCritical(desc_b, &is_copy));
    if (b == nullptr) {
      env->ReleasePrimitiveArrayCritical(desc_a, const_cast<int64_t*>(a), JNI_ABORT);
      return nullptr;
    }
    const std::vector<int32_t> matches =
        funshot::MatchFeatures(a, count_a, b, count_b, words, ratio, max_matches, max_distance);
    env->ReleasePrimitiveArrayCritical(desc_b, const_cast<int64_t*>(b), JNI_ABORT);
    b = nullptr;
    env->ReleasePrimitiveArrayCritical(desc_a, const_cast<int64_t*>(a), JNI_ABORT);
    a = nullptr;
    const jsize size = static_cast<jsize>(matches.size());
    jintArray out = env->NewIntArray(size);
    if (out == nullptr) return nullptr;
    if (size > 0) {
      env->SetIntArrayRegion(out, 0, size, reinterpret_cast<const jint*>(matches.data()));
    }
    return out;
  } catch (...) {
    if (b != nullptr) env->ReleasePrimitiveArrayCritical(desc_b, const_cast<int64_t*>(b), JNI_ABORT);
    if (a != nullptr) env->ReleasePrimitiveArrayCritical(desc_a, const_cast<int64_t*>(a), JNI_ABORT);
    LogW("match: native exception, falling back");
    return nullptr;
  }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_hinnka_mycamera_funshot_FunshotPanoramaNative_nativeEstimateHomography(
    JNIEnv* env, jobject /* thiz */, jdoubleArray src, jdoubleArray dst, jint count,
    jint iterations, jdouble threshold, jint min_inliers, jdouble min_inlier_ratio, jlong seed) {
  if (env == nullptr || src == nullptr || dst == nullptr) return nullptr;
  if (count < 4 || iterations < 1) return nullptr;
  const long long expected = static_cast<long long>(count) * 2;
  if (!LengthIs(env->GetArrayLength(src), expected) || !LengthIs(env->GetArrayLength(dst), expected)) {
    LogW("homography: correspondence length mismatch");
    return nullptr;
  }
  const double* s = nullptr;
  const double* d = nullptr;
  jboolean is_copy = JNI_FALSE;
  try {
    s = static_cast<const double*>(env->GetPrimitiveArrayCritical(src, &is_copy));
    if (s == nullptr) return nullptr;
    d = static_cast<const double*>(env->GetPrimitiveArrayCritical(dst, &is_copy));
    if (d == nullptr) {
      env->ReleasePrimitiveArrayCritical(src, const_cast<double*>(s), JNI_ABORT);
      return nullptr;
    }
    const std::vector<double> raw =
        funshot::EstimateHomography(s, d, count, iterations, threshold, min_inliers,
                                    min_inlier_ratio, static_cast<int64_t>(seed));
    env->ReleasePrimitiveArrayCritical(dst, const_cast<double*>(d), JNI_ABORT);
    d = nullptr;
    env->ReleasePrimitiveArrayCritical(src, const_cast<double*>(s), JNI_ABORT);
    s = nullptr;
    if (raw.empty()) {
      // 无有效假设（退化/无法拟合）：回传全零的 [0, h0..h8, flags...] ，
      // Kotlin 侧读到首位 0 即判定「native: no valid hypothesis」——
      // 与纯 Kotlin 路径的失败语义一致，且无需再跑一遍同样的 RANSAC。
      const jsize fallback_size = static_cast<jsize>(10 + count);
      jdoubleArray out = env->NewDoubleArray(fallback_size);
      if (out == nullptr) return nullptr;
      std::vector<jdouble> zeros(static_cast<size_t>(fallback_size), 0.0);
      env->SetDoubleArrayRegion(out, 0, fallback_size, zeros.data());
      return out;
    }
    const jsize size = static_cast<jsize>(raw.size());
    jdoubleArray out = env->NewDoubleArray(size);
    if (out == nullptr) return nullptr;
    env->SetDoubleArrayRegion(out, 0, size, raw.data());
    return out;
  } catch (...) {
    if (d != nullptr) env->ReleasePrimitiveArrayCritical(dst, const_cast<double*>(d), JNI_ABORT);
    if (s != nullptr) env->ReleasePrimitiveArrayCritical(src, const_cast<double*>(s), JNI_ABORT);
    LogW("homography: native exception, falling back");
    return nullptr;
  }
}
