// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 经典后处理计算层：C++ 核心 + JNI 桥。
//
// JNI 符号与 `com.hinnka.mycamera.nativecompute.NativeComputeBridge` 的 external 声明
// 逐参对齐（对齐表见 `delivery/selfchecks/nativecompute.md`）。
//
// 与 Kotlin 实现的关系：逐式镜像（相同循环边界、相同 float 运算顺序、相同退化保护），
// 因此同一份输入在两条路径上得到同一结果；差异只在"谁分配工作缓冲"——本层每次调用
// 只有 1~2 个 O(width*height) 的临时缓冲，内存预算仍由 Kotlin 侧的
// EnhancePipeline.resolveWorkingScale + BgJob.guardMemory 决定（PATTERN_SPEC §10.6）。

#include "enhance_core.h"

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "nativecompute_common.h"

namespace photon {
namespace nativecompute {
namespace enhance {
namespace {

/** 恒等映射 LUT（常量 tile / 退化情形的统一出口，与 Kotlin 一致）。 */
void FillIdentityLut(int32_t* lut) {
  for (int32_t i = 0; i < kLumaBins; ++i) lut[i] = i;
}

/**
 * 单 tile：直方图 → 裁剪 + 余量重分配 → CDF 映射表。
 * 等价 `ClaheProcessor.buildTileLut`（含"常量 tile 早退"与"cdfMin 退化早退"两条保护）。
 */
void BuildTileLut(const float* luma,
                  int32_t width,
                  int32_t x0,
                  int32_t y0,
                  int32_t x1,
                  int32_t y1,
                  float clip_limit,
                  int32_t* lut) {
  const int32_t area = (x1 - x0) * (y1 - y0);
  if (area <= 0) {
    FillIdentityLut(lut);
    return;
  }

  int32_t hist[kLumaBins] = {0};
  for (int32_t y = y0; y < y1; ++y) {
    const int32_t base = y * width;
    for (int32_t x = x0; x < x1; ++x) {
      ++hist[ToByteChannel(luma[base + x])];
    }
  }

  // 常量判断必须基于**原始**直方图（早于裁剪），否则余量重分配会抬高纯色块亮度。
  int32_t occupied_bins = 0;
  for (int32_t i = 0; i < kLumaBins; ++i) {
    if (hist[i] > 0) ++occupied_bins;
  }
  if (occupied_bins <= 1) {
    FillIdentityLut(lut);
    return;
  }

  const float safe_clip_limit = IsFinite(clip_limit) ? std::max(0.0f, clip_limit) : 0.0f;
  const int32_t cap = std::max(
      1, RoundHalfUp(safe_clip_limit * static_cast<float>(area) / static_cast<float>(kLumaBins)));

  int32_t clipped[kLumaBins];
  for (int32_t i = 0; i < kLumaBins; ++i) clipped[i] = hist[i];
  // 对比度裁剪 + 余量重分配（逐 bin 递增，总数严格守恒）。
  int32_t excess = 0;
  for (int32_t i = 0; i < kLumaBins; ++i) {
    if (clipped[i] > cap) {
      excess += clipped[i] - cap;
      clipped[i] = cap;
    }
  }
  while (excess > 0) {
    int32_t added = 0;
    for (int32_t i = 0; i < kLumaBins; ++i) {
      if (excess <= 0) break;
      if (clipped[i] < cap) {
        ++clipped[i];
        --excess;
        ++added;
      }
    }
    if (added == 0) break;  // 所有 bin 都已到顶，余量无法再分配
  }

  int32_t cdf = 0;
  int32_t cdf_min = -1;
  int32_t cdf_arr[kLumaBins];
  for (int32_t i = 0; i < kLumaBins; ++i) {
    cdf += clipped[i];
    cdf_arr[i] = cdf;
    if (cdf_min < 0 && cdf > 0) cdf_min = cdf;
  }
  const int32_t denominator = area - cdf_min;
  if (cdf_min < 0 || denominator <= 0) {
    FillIdentityLut(lut);
    return;
  }
  const float scale = static_cast<float>(kLumaBins - 1) / static_cast<float>(denominator);
  for (int32_t i = 0; i < kLumaBins; ++i) {
    lut[i] = ClampInt(RoundHalfUp(static_cast<float>(cdf_arr[i] - cdf_min) * scale), 0,
                      kLumaBins - 1);
  }
}

/**
 * 单通道可分离盒式模糊（滑动窗口和，边界 clamp），等价 `BoxBlur.box`。
 *
 * 缓冲别名契约（避免水平趟读写同一缓冲）：`scratch` 必须与 `src` / `dst` 都不同；
 * 而 `dst` 允许与 `src` 相同（垂直趟只读 `scratch`，就地写回是安全的）——
 * 这样三次盒式迭代只需要 2 个 O(w*h) 临时缓冲。
 */
void BoxBlurInto(const float* src,
                 float* scratch,
                 float* dst,
                 int32_t width,
                 int32_t height,
                 int32_t radius) {
  if (src == nullptr || scratch == nullptr || dst == nullptr) return;
  const int32_t n = width * height;
  if (radius <= 0 || width <= 0 || height <= 0) {
    for (int32_t i = 0; i < n; ++i) dst[i] = src[i];
    return;
  }
  const int32_t r = radius;
  const float window = static_cast<float>(2 * r + 1);
  // 水平趟：读 src，写 scratch
  for (int32_t y = 0; y < height; ++y) {
    const int32_t base = y * width;
    float sum = 0.0f;
    for (int32_t k = -r; k <= r; ++k) sum += src[base + ClampInt(k, 0, width - 1)];
    for (int32_t x = 0; x < width; ++x) {
      scratch[base + x] = sum / window;
      const int32_t add_index = std::min(x + r + 1, width - 1);
      const int32_t remove_index = std::max(x - r, 0);
      sum = sum + (src[base + add_index] - src[base + remove_index]);
    }
  }
  // 垂直趟：读 scratch，写 dst
  for (int32_t x = 0; x < width; ++x) {
    float sum = 0.0f;
    for (int32_t k = -r; k <= r; ++k) sum += scratch[ClampInt(k, 0, height - 1) * width + x];
    for (int32_t y = 0; y < height; ++y) {
      dst[y * width + x] = sum / window;
      const int32_t add_index = std::min(y + r + 1, height - 1);
      const int32_t remove_index = std::max(y - r, 0);
      sum = sum + (scratch[add_index * width + x] - scratch[remove_index * width + x]);
    }
  }
}

/** 单通道 Rec.709 亮度（与 `PlanarRgb.luma` 同一运算顺序）。 */
inline float LumaOf(float r, float g, float b) {
  return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

}  // namespace

bool ClaheEqualize(const float* luma,
                   int32_t width,
                   int32_t height,
                   int32_t tiles_x,
                   int32_t tiles_y,
                   float clip_limit,
                   float* out) {
  if (luma == nullptr || out == nullptr) {
    LogW("clahe: null buffer");
    return false;
  }
  if (width <= 0 || height <= 0) {
    LogW("clahe: non-positive size");
    return false;
  }
  const int32_t tiles_nx = std::min(ClampInt(tiles_x, kTilesMin, kTilesMax), width);
  const int32_t tiles_ny = std::min(ClampInt(tiles_y, kTilesMin, kTilesMax), height);
  const int32_t tile_w = (width + tiles_nx - 1) / tiles_nx;
  const int32_t tile_h = (height + tiles_ny - 1) / tiles_ny;

  // 逐 tile 映射表（≤ 32×32×256×4B = 1MB，与 Kotlin 的 Array<IntArray> 同规模）。
  std::vector<int32_t> luts(static_cast<size_t>(tiles_nx) * static_cast<size_t>(tiles_ny) *
                            static_cast<size_t>(kLumaBins));
  for (int32_t ty = 0; ty < tiles_ny; ++ty) {
    for (int32_t tx = 0; tx < tiles_nx; ++tx) {
      const int32_t x0 = tx * tile_w;
      const int32_t y0 = ty * tile_h;
      const int32_t x1 = std::min(x0 + tile_w, width);
      const int32_t y1 = std::min(y0 + tile_h, height);
      int32_t* lut = luts.data() +
                     (static_cast<size_t>(ty) * tiles_nx + static_cast<size_t>(tx)) * kLumaBins;
      BuildTileLut(luma, width, x0, y0, x1, y1, clip_limit, lut);
    }
  }

  // tile 间双线性插值（消除块状边界）。
  for (int32_t y = 0; y < height; ++y) {
    const float yf = (static_cast<float>(y) + 0.5f) / static_cast<float>(tile_h) - 0.5f;
    const int32_t yt0_raw = TruncToInt(std::floor(yf));
    const float fy = yf - static_cast<float>(yt0_raw);
    const int32_t yt0 = ClampInt(yt0_raw, 0, tiles_ny - 1);
    const int32_t yt1 = ClampInt(yt0_raw + 1, 0, tiles_ny - 1);
    for (int32_t x = 0; x < width; ++x) {
      const float xf = (static_cast<float>(x) + 0.5f) / static_cast<float>(tile_w) - 0.5f;
      const int32_t xt0_raw = TruncToInt(std::floor(xf));
      const float fx = xf - static_cast<float>(xt0_raw);
      const int32_t xt0 = ClampInt(xt0_raw, 0, tiles_nx - 1);
      const int32_t xt1 = ClampInt(xt0_raw + 1, 0, tiles_nx - 1);

      const int32_t bin = ToByteChannel(luma[y * width + x]);
      const int32_t* row0 =
          luts.data() + (static_cast<size_t>(yt0) * tiles_nx) * kLumaBins;
      const int32_t* row1 =
          luts.data() + (static_cast<size_t>(yt1) * tiles_nx) * kLumaBins;
      const int32_t top_left = row0[static_cast<size_t>(xt0) * kLumaBins + bin];
      const int32_t top_right = row0[static_cast<size_t>(xt1) * kLumaBins + bin];
      const int32_t bottom_left = row1[static_cast<size_t>(xt0) * kLumaBins + bin];
      const int32_t bottom_right = row1[static_cast<size_t>(xt1) * kLumaBins + bin];
      const float top = static_cast<float>(top_left) +
                        static_cast<float>(top_right - top_left) * fx;
      const float bottom = static_cast<float>(bottom_left) +
                           static_cast<float>(bottom_right - bottom_left) * fx;
      out[y * width + x] = (top + (bottom - top) * fy) / static_cast<float>(kLumaBins - 1);
    }
  }
  return true;
}

bool ApplyLumaGain(float* r,
                   float* g,
                   float* b,
                   const float* luma,
                   const float* equalized,
                   int32_t count,
                   float mix) {
  if (r == nullptr || g == nullptr || b == nullptr || luma == nullptr || equalized == nullptr) {
    LogW("luma gain: null buffer");
    return false;
  }
  if (count < 0) {
    LogW("luma gain: negative count");
    return false;
  }
  for (int32_t i = 0; i < count; ++i) {
    const float l = luma[i];
    const float target = MixFloat(l, equalized[i], mix);
    if (target == l) continue;
    if (l > 1e-3f) {
      const float gain = target / l;
      r[i] = CoerceInFloat(r[i] * gain, 0.0f, 1.0f);
      g[i] = CoerceInFloat(g[i] * gain, 0.0f, 1.0f);
      b[i] = CoerceInFloat(b[i] * gain, 0.0f, 1.0f);
    } else {
      // 极暗像素：加性补偿，避免把黑位噪声放大成彩噪。
      const float delta = target - l;
      r[i] = CoerceInFloat(r[i] + delta, 0.0f, 1.0f);
      g[i] = CoerceInFloat(g[i] + delta, 0.0f, 1.0f);
      b[i] = CoerceInFloat(b[i] + delta, 0.0f, 1.0f);
    }
  }
  return true;
}

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
                   float sigma_color) {
  if (src_r == nullptr || src_g == nullptr || src_b == nullptr || dst_r == nullptr ||
      dst_g == nullptr || dst_b == nullptr) {
    LogW("bilateral rows: null buffer");
    return false;
  }
  if (width <= 0 || height <= 0) {
    LogW("bilateral rows: non-positive size");
    return false;
  }
  const int32_t r = std::max(1, radius);
  const float sigma_s = CoerceAtLeastFloat(sigma_space, 1e-3f);
  const float sigma_c = CoerceAtLeastFloat(sigma_color, 1e-3f);

  const int32_t kernel_size = 2 * r + 1;
  std::vector<float> spatial(static_cast<size_t>(kernel_size));
  for (int32_t k = 0; k < kernel_size; ++k) {
    const float d = static_cast<float>(k - r);
    // Kotlin: exp(-(d * d) / (2f * sigmaSpace * sigmaSpace))（全 Float，最后一次舍入）。
    const float t = -(d * d) / (2.0f * sigma_s * sigma_s);
    spatial[static_cast<size_t>(k)] = static_cast<float>(std::exp(static_cast<double>(t)));
  }
  const float range_scale = -1.0f / (2.0f * sigma_c * sigma_c);

  const int32_t y0 = ClampInt(row_start, 0, height);
  const int32_t y1 = ClampInt(row_end, y0, height);
  for (int32_t y = y0; y < y1; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const int32_t center = y * width + x;
      const float center_luma =
          LumaOf(src_r[center], src_g[center], src_b[center]);
      float sum_r = 0.0f;
      float sum_g = 0.0f;
      float sum_b = 0.0f;
      float sum_w = 0.0f;
      for (int32_t dy = -r; dy <= r; ++dy) {
        const int32_t sy = ClampInt(y + dy, 0, height - 1);
        const float wy = spatial[static_cast<size_t>(dy + r)];
        const int32_t row_base = sy * width;
        for (int32_t dx = -r; dx <= r; ++dx) {
          const int32_t sx = ClampInt(x + dx, 0, width - 1);
          const int32_t index = row_base + sx;
          const float luma_diff = LumaOf(src_r[index], src_g[index], src_b[index]) - center_luma;
          const float weight = wy * spatial[static_cast<size_t>(dx + r)] *
                               static_cast<float>(std::exp(
                                   static_cast<double>(luma_diff * luma_diff * range_scale)));
          sum_r += src_r[index] * weight;
          sum_g += src_g[index] * weight;
          sum_b += src_b[index] * weight;
          sum_w += weight;
        }
      }
      if (sum_w <= 1e-12f) {
        dst_r[center] = src_r[center];
        dst_g[center] = src_g[center];
        dst_b[center] = src_b[center];
      } else {
        dst_r[center] = CoerceInFloat(sum_r / sum_w, 0.0f, 1.0f);
        dst_g[center] = CoerceInFloat(sum_g / sum_w, 0.0f, 1.0f);
        dst_b[center] = CoerceInFloat(sum_b / sum_w, 0.0f, 1.0f);
      }
    }
  }
  return true;
}

bool UnsharpChannel(float* channel,
                    int32_t width,
                    int32_t height,
                    int32_t radius,
                    float amount,
                    int32_t threshold) {
  if (channel == nullptr) {
    LogW("unsharp channel: null buffer");
    return false;
  }
  if (width <= 0 || height <= 0) {
    LogW("unsharp channel: non-positive size");
    return false;
  }
  const int32_t r = radius;
  if (r <= 0 || !(amount > 0.0f)) {
    // 退化（radius <= 0 / amount <= 0）由调用方按"原样返回"处理。
    return false;
  }
  const int32_t n = width * height;
  std::vector<float> blur(static_cast<size_t>(n), 0.0f);
  std::vector<float> scratch(static_cast<size_t>(n), 0.0f);
  // 三次盒式模糊近似高斯（中心极限定理），与 BoxBlur.gaussianApprox 的三趟一致。
  // 契约：scratch 与 blur 互不相同；blur 允许与输入不同（channel 需要保留给锐化用）。
  BoxBlurInto(channel, scratch.data(), blur.data(), width, height, r);
  BoxBlurInto(blur.data(), scratch.data(), blur.data(), width, height, r);
  BoxBlurInto(blur.data(), scratch.data(), blur.data(), width, height, r);

  const float threshold_norm =
      threshold <= 0 ? 0.0f : static_cast<float>(threshold) / static_cast<float>(kLumaBins - 1);
  for (int32_t i = 0; i < n; ++i) {
    const float diff = channel[i] - blur[static_cast<size_t>(i)];
    if (threshold_norm > 0.0f && std::fabs(diff) < threshold_norm) continue;
    channel[i] = CoerceInFloat(channel[i] + amount * diff, 0.0f, 1.0f);
  }
  return true;
}

}  // namespace enhance

// ---------------------------------------------------------------------------
// JNI 桥
// ---------------------------------------------------------------------------

namespace {

/** FloatArray 的 RAII 句柄（析构时自动 Release，避免 early-return 泄漏）。 */
class FloatArrayAccess {
 public:
  FloatArrayAccess(JNIEnv* env, jfloatArray array, bool write_back)
      : env_(env), array_(array), write_back_(write_back) {
    if (env_ != nullptr && array_ != nullptr) {
      data_ = env_->GetFloatArrayElements(array_, nullptr);
    }
  }

  ~FloatArrayAccess() {
    if (data_ != nullptr) {
      env_->ReleaseFloatArrayElements(array_, data_, write_back_ ? 0 : JNI_ABORT);
    }
  }

  FloatArrayAccess(const FloatArrayAccess&) = delete;
  FloatArrayAccess& operator=(const FloatArrayAccess&) = delete;

  bool ok() const { return data_ != nullptr; }
  float* data() const { return data_; }
  jsize length() const {
    return (env_ != nullptr && array_ != nullptr) ? env_->GetArrayLength(array_) : 0;
  }

 private:
  JNIEnv* env_;
  jfloatArray array_;
  float* data_ = nullptr;
  bool write_back_;
};

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeClaheEqualize(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray luma,
    jint width,
    jint height,
    jint tiles_x,
    jint tiles_y,
    jfloat clip_limit) {
  if (env == nullptr || luma == nullptr || width <= 0 || height <= 0) {
    LogW("jni clahe: invalid arguments");
    return nullptr;
  }
  const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
  if (static_cast<int64_t>(env->GetArrayLength(luma)) < expected) {
    LogW("jni clahe: luma buffer too small");
    return nullptr;
  }
  FloatArrayAccess luma_access(env, luma, false);
  if (!luma_access.ok()) {
    LogW("jni clahe: acquisition failed");
    return nullptr;
  }
  jfloatArray result = env->NewFloatArray(static_cast<jsize>(expected));
  if (result == nullptr) return nullptr;
  float* out = env->GetFloatArrayElements(result, nullptr);
  if (out == nullptr) return nullptr;
  const bool ok = photon::nativecompute::enhance::ClaheEqualize(
      luma_access.data(), static_cast<int32_t>(width), static_cast<int32_t>(height),
      static_cast<int32_t>(tiles_x), static_cast<int32_t>(tiles_y),
      static_cast<float>(clip_limit), out);
  env->ReleaseFloatArrayElements(result, out, ok ? 0 : JNI_ABORT);
  if (!ok) return nullptr;
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeApplyLumaGain(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray r,
    jfloatArray g,
    jfloatArray b,
    jfloatArray luma,
    jfloatArray equalized,
    jint count,
    jfloat mix) {
  if (env == nullptr || r == nullptr || g == nullptr || b == nullptr || luma == nullptr ||
      equalized == nullptr) {
    LogW("jni luma gain: null array");
    return JNI_FALSE;
  }
  if (count < 0) {
    LogW("jni luma gain: negative count");
    return JNI_FALSE;
  }
  const int64_t expected = static_cast<int64_t>(count);
  if (static_cast<int64_t>(env->GetArrayLength(r)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(g)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(b)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(luma)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(equalized)) < expected) {
    LogW("jni luma gain: array length mismatch");
    return JNI_FALSE;
  }
  FloatArrayAccess r_access(env, r, true);
  FloatArrayAccess g_access(env, g, true);
  FloatArrayAccess b_access(env, b, true);
  FloatArrayAccess luma_access(env, luma, false);
  FloatArrayAccess equalized_access(env, equalized, false);
  if (!r_access.ok() || !g_access.ok() || !b_access.ok() || !luma_access.ok() ||
      !equalized_access.ok()) {
    LogW("jni luma gain: acquisition failed");
    return JNI_FALSE;
  }
  const bool ok = photon::nativecompute::enhance::ApplyLumaGain(
      r_access.data(), g_access.data(), b_access.data(), luma_access.data(), equalized_access.data(),
      static_cast<int32_t>(count), static_cast<float>(mix));
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeBilateralRows(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray src_r,
    jfloatArray src_g,
    jfloatArray src_b,
    jfloatArray dst_r,
    jfloatArray dst_g,
    jfloatArray dst_b,
    jint width,
    jint height,
    jint row_start,
    jint row_end,
    jint radius,
    jfloat sigma_space,
    jfloat sigma_color) {
  if (env == nullptr || src_r == nullptr || src_g == nullptr || src_b == nullptr ||
      dst_r == nullptr || dst_g == nullptr || dst_b == nullptr) {
    LogW("jni bilateral rows: null array");
    return JNI_FALSE;
  }
  if (width <= 0 || height <= 0) {
    LogW("jni bilateral rows: non-positive size");
    return JNI_FALSE;
  }
  const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
  if (static_cast<int64_t>(env->GetArrayLength(src_r)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(src_g)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(src_b)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(dst_r)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(dst_g)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(dst_b)) < expected) {
    LogW("jni bilateral rows: array length mismatch");
    return JNI_FALSE;
  }
  FloatArrayAccess src_r_access(env, src_r, false);
  FloatArrayAccess src_g_access(env, src_g, false);
  FloatArrayAccess src_b_access(env, src_b, false);
  FloatArrayAccess dst_r_access(env, dst_r, true);
  FloatArrayAccess dst_g_access(env, dst_g, true);
  FloatArrayAccess dst_b_access(env, dst_b, true);
  if (!src_r_access.ok() || !src_g_access.ok() || !src_b_access.ok() || !dst_r_access.ok() ||
      !dst_g_access.ok() || !dst_b_access.ok()) {
    LogW("jni bilateral rows: acquisition failed");
    return JNI_FALSE;
  }
  const bool ok = photon::nativecompute::enhance::BilateralRows(
      src_r_access.data(), src_g_access.data(), src_b_access.data(), dst_r_access.data(),
      dst_g_access.data(), dst_b_access.data(), static_cast<int32_t>(width),
      static_cast<int32_t>(height), static_cast<int32_t>(row_start),
      static_cast<int32_t>(row_end), static_cast<int32_t>(radius),
      static_cast<float>(sigma_space), static_cast<float>(sigma_color));
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeUnsharpChannel(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray channel,
    jint width,
    jint height,
    jint radius,
    jfloat amount,
    jint threshold) {
  if (env == nullptr || channel == nullptr) {
    LogW("jni unsharp channel: null array");
    return JNI_FALSE;
  }
  if (width <= 0 || height <= 0) {
    LogW("jni unsharp channel: non-positive size");
    return JNI_FALSE;
  }
  const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
  if (static_cast<int64_t>(env->GetArrayLength(channel)) < expected) {
    LogW("jni unsharp channel: array too small");
    return JNI_FALSE;
  }
  FloatArrayAccess channel_access(env, channel, true);
  if (!channel_access.ok()) {
    LogW("jni unsharp channel: acquisition failed");
    return JNI_FALSE;
  }
  const bool ok = photon::nativecompute::enhance::UnsharpChannel(
      channel_access.data(), static_cast<int32_t>(width), static_cast<int32_t>(height),
      static_cast<int32_t>(radius), static_cast<float>(amount), static_cast<int32_t>(threshold));
  return ok ? JNI_TRUE : JNI_FALSE;
}

}  // namespace nativecompute
}  // namespace photon
