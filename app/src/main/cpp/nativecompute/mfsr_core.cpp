// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 多帧超分（MFSR）计算层：C++ 核心 + JNI 桥。
//
// JNI 符号与 Kotlin 声明（`com.hinnka.mycamera.nativecompute.NativeComputeBridge`）逐参对齐，
// 对齐表见 `delivery/selfchecks/nativecompute.md`。
//
// 本文件内的核心函数与 `com.hinnka.mycamera.multiframesr` 的纯 Kotlin 实现逐式镜像：
// 相同的循环边界、相同的 float 运算顺序、相同的 clamp/退化为哨兵的策略。
// 为保持与 JVM（Kotlin）同值，所有超越函数按 `Math.xxx(v.toDouble()).toFloat()` 的方式
// 先升 double 再回落 float（见 nativecompute_common.h）。

#include "mfsr_core.h"

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#include "nativecompute_common.h"

namespace photon {
namespace nativecompute {
namespace mfsr {
namespace {

// ---------------------------------------------------------------------------
// 常量：与 Kotlin 侧唯一定义处对齐（禁止在此处另立魔法数）
// ---------------------------------------------------------------------------

/** MfsrIterativeBackProjection.CLAMP_LO / CLAMP_HI（8bit luma 域）。 */
constexpr float kIbpClampLo = 0.0f;
constexpr float kIbpClampHi = 255.0f;

/** MfsrConfig.DENOISE_RADIUS_MAX（MfsrRefiner.bilateral 的 radius 上限）。 */
constexpr int32_t kDenoiseRadiusMax = 4;

/** MfsrPhaseCorrelation.CONF_HALF_POINT（峰噪比 → 置信度的软映射半点）。 */
constexpr float kConfidenceHalfPoint = 3.5f;

/** MfsrSubpixelAligner.PHASE_ALIGN_MAX_SIDE 的允许范围（桥接层同时 clamp）。 */
constexpr int32_t kAlignMaxSideMin = 64;
constexpr int32_t kAlignMaxSideMax = 1024;

/** MfsrShiftAndAdd.fillHoles 的默认扩散轮数（Kotlin: passes = 8）。 */
constexpr int32_t kHolePassesMax = 64;

/** 相位相关 FFT 面的像素上限（1024×1024 = 1M；4 张浮点面 ≈ 16MB，超限即回退 Kotlin）。 */
constexpr int64_t kMaxFftPixels = 1LL << 21;

constexpr double kPi = 3.14159265358979323846;

// ---------------------------------------------------------------------------
// 平面采样工具（与 MfsrPlane 同名语义）
// ---------------------------------------------------------------------------

inline float ClampiAt(const float* data,
                      int32_t width,
                      int32_t height,
                      int32_t x,
                      int32_t y) {
  return data[ClampInt(y, 0, height - 1) * width + ClampInt(x, 0, width - 1)];
}

/** 双线性采样（像素中心坐标）；越界返回 NaN（而非 clamp），与 MfsrPlane.bilinear 一致。 */
inline float BilinearAt(const float* data,
                        int32_t width,
                        int32_t height,
                        float x,
                        float y) {
  if (!IsFinite(x) || !IsFinite(y)) return std::numeric_limits<float>::quiet_NaN();
  if (x < -0.5f || y < -0.5f || x > static_cast<float>(width) - 0.5f ||
      y > static_cast<float>(height) - 0.5f) {
    return std::numeric_limits<float>::quiet_NaN();
  }
  const float cx = ClampFloat(x, 0.0f, static_cast<float>(width - 1));
  const float cy = ClampFloat(y, 0.0f, static_cast<float>(height - 1));
  const int32_t x0 = TruncToInt(cx);
  const int32_t y0 = TruncToInt(cy);
  const int32_t x1 = std::min(x0 + 1, width - 1);
  const int32_t y1 = std::min(y0 + 1, height - 1);
  const float fx = cx - static_cast<float>(x0);
  const float fy = cy - static_cast<float>(y0);
  const float v00 = data[y0 * width + x0];
  const float v10 = data[y0 * width + x1];
  const float v01 = data[y1 * width + x0];
  const float v11 = data[y1 * width + x1];
  const float top = v00 + (v10 - v00) * fx;
  const float bot = v01 + (v11 - v01) * fx;
  return top + (bot - top) * fy;
}

float MeanOf(const float* data, int32_t count) {
  double sum = 0.0;
  for (int32_t i = 0; i < count; ++i) sum += static_cast<double>(data[i]);
  return static_cast<float>(sum / static_cast<double>(count));
}

/** 均值绝对偏差（衡量纹理强度），等价 MfsrStat.meanAbsDeviation。 */
float MeanAbsDeviation(const float* data, int32_t count) {
  const float m = MeanOf(data, count);
  double sum = 0.0;
  for (int32_t i = 0; i < count; ++i) {
    sum += static_cast<double>(std::fabs(data[i] - m));
  }
  return static_cast<float>(sum / static_cast<double>(count));
}

/** 归一化 sigmoid，等价 MfsrStat.softConfidence。 */
float SoftConfidence(float metric, float half_point) {
  if (!IsFinite(metric)) return 0.0f;
  if (half_point <= 0.0f) return metric > 0.0f ? 1.0f : 0.0f;
  const float x = metric / half_point;
  return ClampFloat(x / (1.0f + x), 0.0f, 1.0f);
}

/** 区域平均采样（降采样抗混叠），等价 MfsrPlane.areaSample。 */
float AreaSample(const float* data,
                 int32_t width,
                 int32_t height,
                 float cx,
                 float cy,
                 float span_x,
                 float span_y) {
  const int32_t half_x = std::max(1, RoundHalfUp(span_x / 2.0f));
  const int32_t half_y = std::max(1, RoundHalfUp(span_y / 2.0f));
  const int32_t cxi = TruncToInt(cx);
  const int32_t cyi = TruncToInt(cy);
  const int32_t x0 = cxi - half_x + 1;
  const int32_t x1 = cxi + half_x;
  const int32_t y0 = cyi - half_y + 1;
  const int32_t y1 = cyi + half_y;
  double sum = 0.0;
  int32_t n = 0;
  for (int32_t y = y0; y <= y1; ++y) {
    const int32_t yy = ClampInt(y, 0, height - 1);
    for (int32_t x = x0; x <= x1; ++x) {
      const int32_t xx = ClampInt(x, 0, width - 1);
      sum += static_cast<double>(data[yy * width + xx]);
      ++n;
    }
  }
  return n == 0 ? data[0] : static_cast<float>(sum / static_cast<double>(n));
}

/** 尺寸重采样，等价 MfsrPlane.resampleTo（降用区域平均、升用双线性）。 */
void ResampleTo(const float* src,
                int32_t src_w,
                int32_t src_h,
                int32_t dst_w,
                int32_t dst_h,
                std::vector<float>& out) {
  out.assign(static_cast<size_t>(dst_w) * static_cast<size_t>(dst_h), 0.0f);
  const float scx = static_cast<float>(src_w) / static_cast<float>(dst_w);
  const float scy = static_cast<float>(src_h) / static_cast<float>(dst_h);
  const bool down = scx > 1.001f || scy > 1.001f;
  for (int32_t y = 0; y < dst_h; ++y) {
    for (int32_t x = 0; x < dst_w; ++x) {
      const float cx = (static_cast<float>(x) + 0.5f) * scx - 0.5f;
      const float cy = (static_cast<float>(y) + 0.5f) * scy - 0.5f;
      const float v = down ? AreaSample(src, src_w, src_h, cx, cy, scx, scy)
                           : BilinearAt(src, src_w, src_h, cx, cy);
      out[static_cast<size_t>(y) * dst_w + x] =
          IsFinite(v) ? v : ClampiAt(src, src_w, src_h, x, y);
    }
  }
}

// ---------------------------------------------------------------------------
// 最小 radix-2 复数 FFT（与 MfsrFft 同一算法：同一位反转、同一蝶形顺序）
// ---------------------------------------------------------------------------

int32_t NextPow2(int32_t n) {
  int32_t p = 1;
  while (p < n) p <<= 1;
  return p;
}

void Fft1D(std::vector<float>& re, std::vector<float>& im, bool inverse) {
  const int32_t n = static_cast<int32_t>(re.size());
  if (n <= 1) return;

  int32_t j = 0;
  for (int32_t i = 0; i < n - 1; ++i) {
    if (i < j) {
      std::swap(re[i], re[j]);
      std::swap(im[i], im[j]);
    }
    int32_t k = n >> 1;
    while (k <= j) {
      j -= k;
      k >>= 1;
    }
    j += k;
  }

  for (int32_t len = 2; len <= n; len <<= 1) {
    const double ang = (inverse ? 2.0 : -2.0) * kPi / static_cast<double>(len);
    const float w_re = static_cast<float>(std::cos(ang));
    const float w_im = static_cast<float>(std::sin(ang));
    const int32_t half = len >> 1;
    for (int32_t i = 0; i < n; i += len) {
      float cur_re = 1.0f;
      float cur_im = 0.0f;
      for (int32_t k = 0; k < half; ++k) {
        const int32_t ia = i + k;
        const int32_t ib = ia + half;
        const float v_re = re[ib] * cur_re - im[ib] * cur_im;
        const float v_im = re[ib] * cur_im + im[ib] * cur_re;
        re[ib] = re[ia] - v_re;
        im[ib] = im[ia] - v_im;
        re[ia] += v_re;
        im[ia] += v_im;
        const float next_re = cur_re * w_re - cur_im * w_im;
        cur_im = cur_re * w_im + cur_im * w_re;
        cur_re = next_re;
      }
    }
  }

  if (inverse) {
    const float inv = 1.0f / static_cast<float>(n);
    for (int32_t i = 0; i < n; ++i) {
      re[i] *= inv;
      im[i] *= inv;
    }
  }
}

/** 二维 FFT：先行后列（宽高都必须是 2 的幂），等价 MfsrFft.transform2D。 */
void Fft2D(std::vector<float>& re, std::vector<float>& im, int32_t w, int32_t h, bool inverse) {
  std::vector<float> row_re(static_cast<size_t>(w));
  std::vector<float> row_im(static_cast<size_t>(w));
  for (int32_t y = 0; y < h; ++y) {
    const int32_t base = y * w;
    for (int32_t x = 0; x < w; ++x) {
      row_re[x] = re[base + x];
      row_im[x] = im[base + x];
    }
    Fft1D(row_re, row_im, inverse);
    for (int32_t x = 0; x < w; ++x) {
      re[base + x] = row_re[x];
      im[base + x] = row_im[x];
    }
  }
  std::vector<float> col_re(static_cast<size_t>(h));
  std::vector<float> col_im(static_cast<size_t>(h));
  for (int32_t x = 0; x < w; ++x) {
    for (int32_t y = 0; y < h; ++y) {
      col_re[y] = re[y * w + x];
      col_im[y] = im[y * w + x];
    }
    Fft1D(col_re, col_im, inverse);
    for (int32_t y = 0; y < h; ++y) {
      re[y * w + x] = col_re[y];
      im[y * w + x] = col_im[y];
    }
  }
}

/** FFT 峰值坐标的 wrap 解缠（> n/2 视为负频率）。 */
float WrapIndex(int32_t idx, int32_t n) {
  const int32_t half = n / 2;
  return idx > half ? static_cast<float>(idx - n) : static_cast<float>(idx);
}

/** 峰值邻域抛物线插值（0.5px 内），带轴 wrap，等价 MfsrPhaseCorrelation.parabolic。 */
float Parabolic(const std::vector<float>& surface,
                int32_t w,
                int32_t h,
                int32_t px,
                int32_t py,
                bool along_x) {
  const int32_t n = along_x ? w : h;
  const int32_t cur = along_x ? px : py;
  auto sample = [&](int32_t i) -> float {
    const int32_t ii = ((i % n) + n) % n;
    return along_x ? surface[py * w + ii] : surface[ii * w + px];
  };
  const float prev = sample(cur - 1);
  const float center = sample(cur);
  const float next = sample(cur + 1);
  const float denom = prev - 2.0f * center + next;
  if (!IsFinite(denom) || std::fabs(denom) < 1e-9f) return 0.0f;
  const float delta = 0.5f * (prev - next) / denom;
  return IsFinite(delta) ? ClampFloat(delta, -0.5f, 0.5f) : 0.0f;
}

/** 平移矢量限量到 ±max_shift，等价 MfsrShift.clampedTo。 */
void ClampShiftTo(float max_shift, float* dx, float* dy) {
  if (!IsFinite(*dx) || !IsFinite(*dy)) {
    *dx = 0.0f;
    *dy = 0.0f;
    return;
  }
  const float magnitude = SqrtF((*dx) * (*dx) + (*dy) * (*dy));
  if (magnitude <= max_shift || magnitude <= 0.0f) return;
  const float k = max_shift / magnitude;
  *dx *= k;
  *dy *= k;
}

}  // namespace

// ---------------------------------------------------------------------------
// 核心实现
// ---------------------------------------------------------------------------

bool PhaseCorrelate(const float* ref,
                    const float* tgt,
                    int32_t width,
                    int32_t height,
                    int32_t max_shift_px,
                    int32_t align_max_side,
                    float* out_dx,
                    float* out_dy,
                    float* out_confidence) {
  if (ref == nullptr || tgt == nullptr || out_dx == nullptr || out_dy == nullptr ||
      out_confidence == nullptr) {
    LogW("phase correlate: null pointer");
    return false;
  }
  if (width <= 0 || height <= 0) {
    LogW("phase correlate: non-positive size");
    return false;
  }
  const int32_t count = width * height;
  const int32_t max_shift = ClampInt(max_shift_px, 1, 4096);

  // 无纹理帧（纯色/全黑）相位相关无意义，直接判退化（与 Kotlin 同阈值 1e-3）。
  if (MeanAbsDeviation(ref, count) < 1e-3f || MeanAbsDeviation(tgt, count) < 1e-3f) {
    return false;
  }

  // ① 内部工作尺寸（控制 FFT 规模；与"帧数上限"无关）。
  const int32_t max_side = ClampInt(align_max_side, kAlignMaxSideMin, kAlignMaxSideMax);
  std::vector<float> ref_scaled;
  std::vector<float> tgt_scaled;
  const float* a_data = ref;
  const float* b_data = tgt;
  int32_t w = width;
  int32_t h = height;
  const int32_t long_edge = std::max(width, height);
  if (long_edge > max_side) {
    const float k = static_cast<float>(max_side) / static_cast<float>(long_edge);
    w = std::max(8, TruncToInt(static_cast<float>(width) * k));
    h = std::max(8, TruncToInt(static_cast<float>(height) * k));
    ResampleTo(ref, width, height, w, h, ref_scaled);
    ResampleTo(tgt, width, height, w, h, tgt_scaled);
    a_data = ref_scaled.data();
    b_data = tgt_scaled.data();
  }

  const int32_t pw = NextPow2(w);
  const int32_t ph = NextPow2(h);
  const int64_t fft_pixels = static_cast<int64_t>(pw) * static_cast<int64_t>(ph);
  if (fft_pixels <= 0 || fft_pixels > kMaxFftPixels) {
    LogW("phase correlate: fft size out of budget");
    return false;
  }

  std::vector<float> re_a(static_cast<size_t>(fft_pixels), 0.0f);
  std::vector<float> im_a(static_cast<size_t>(fft_pixels), 0.0f);
  std::vector<float> re_b(static_cast<size_t>(fft_pixels), 0.0f);
  std::vector<float> im_b(static_cast<size_t>(fft_pixels), 0.0f);

  // ② Hann 窗后搬入零填充面。
  // Kotlin: 0.5f * (1f - cos(2.0 * PI * i / (n - 1)).toFloat())，除法在 double 域。
  for (int32_t y = 0; y < h; ++y) {
    const float wy = h > 1
                         ? 0.5f * (1.0f - static_cast<float>(std::cos(
                                              2.0 * kPi * static_cast<double>(y) /
                                              static_cast<double>(h - 1))))
                         : 1.0f;
    for (int32_t x = 0; x < w; ++x) {
      const float wx = w > 1
                           ? 0.5f * (1.0f - static_cast<float>(std::cos(
                                                2.0 * kPi * static_cast<double>(x) /
                                                static_cast<double>(w - 1))))
                           : 1.0f;
      const float window = wx * wy;
      const size_t src_index = static_cast<size_t>(y) * w + x;
      const size_t dst_index = static_cast<size_t>(y) * pw + x;
      re_a[dst_index] = a_data[src_index] * window;
      re_b[dst_index] = b_data[src_index] * window;
    }
  }

  Fft2D(re_a, im_a, pw, ph, false);
  Fft2D(re_b, im_b, pw, ph, false);

  // ③ 归一化互功率谱 C = F_tgt · conj(F_ref)。
  const float eps = 1e-6f;
  for (int64_t i = 0; i < fft_pixels; ++i) {
    const float ar = re_a[i];
    const float ai = im_a[i];
    const float br = re_b[i];
    const float bi = im_b[i];
    const float cr = br * ar + bi * ai;
    const float ci = bi * ar - br * ai;
    const float magnitude = SqrtF(cr * cr + ci * ci);
    const float d = magnitude < eps ? eps : magnitude;
    re_a[i] = cr / d;
    im_a[i] = ci / d;
  }

  Fft2D(re_a, im_a, pw, ph, true);
  const std::vector<float>& surface = re_a;

  // ④ 峰值搜索：只在与 maxShift 相符的窗口内搜（避免周期纹理选错峰）。
  const float kx = static_cast<float>(width) / static_cast<float>(w);
  const float ky = static_cast<float>(height) / static_cast<float>(h);
  const float limit_x = static_cast<float>(max_shift) / kx + 2.0f;
  const float limit_y = static_cast<float>(max_shift) / ky + 2.0f;
  int32_t peak_x = 0;
  int32_t peak_y = 0;
  float peak_val = -std::numeric_limits<float>::max();
  for (int32_t y = 0; y < ph; ++y) {
    if (std::fabs(WrapIndex(y, ph)) > limit_y) continue;
    const int32_t base = y * pw;
    for (int32_t x = 0; x < pw; ++x) {
      if (std::fabs(WrapIndex(x, pw)) > limit_x) continue;
      const float v = surface[base + x];
      if (v > peak_val) {
        peak_val = v;
        peak_x = x;
        peak_y = y;
      }
    }
  }
  if (peak_val == -std::numeric_limits<float>::max()) return false;

  // ⑤ 峰噪比 → 置信度。
  double sum = 0.0;
  for (int64_t i = 0; i < fft_pixels; ++i) sum += static_cast<double>(surface[i]);
  const float mean_surface = static_cast<float>(sum / static_cast<double>(fft_pixels));
  double var_sum = 0.0;
  for (int64_t i = 0; i < fft_pixels; ++i) {
    const float d = surface[i] - mean_surface;
    var_sum += static_cast<double>(d * d);
  }
  const float std_surface = std::max(
      SqrtF(static_cast<float>(var_sum / static_cast<double>(fft_pixels))), 1e-6f);
  const float peak_to_noise = (peak_val - mean_surface) / std_surface;
  const float confidence = SoftConfidence(peak_to_noise, kConfidenceHalfPoint);

  // ⑥ 亚像素：峰值处沿 x / y 轴抛物线拟合（带轴解缠）。
  const float sub_x = Parabolic(surface, pw, ph, peak_x, peak_y, true);
  const float sub_y = Parabolic(surface, pw, ph, peak_x, peak_y, false);
  float dx = (WrapIndex(peak_x, pw) + sub_x) * kx;
  float dy = (WrapIndex(peak_y, ph) + sub_y) * ky;
  // 非有限值一律判退化（绝不静默当成 0 平移交给上层）。
  if (!IsFinite(dx) || !IsFinite(dy)) return false;
  ClampShiftTo(static_cast<float>(max_shift), &dx, &dy);
  if (!IsFinite(dx) || !IsFinite(dy)) return false;

  *out_dx = dx;
  *out_dy = dy;
  *out_confidence = IsFinite(confidence) ? confidence : 0.0f;
  return true;
}

bool ShiftAddAccumulate(const float* lr,
                        int32_t lr_width,
                        int32_t lr_height,
                        float* sum,
                        float* weight,
                        int32_t hr_width,
                        int32_t hr_height,
                        float scale,
                        float dx,
                        float dy,
                        float* out_coverage) {
  if (lr == nullptr || sum == nullptr || weight == nullptr || out_coverage == nullptr) {
    LogW("shift-add: null buffer");
    return false;
  }
  if (lr_width <= 0 || lr_height <= 0 || hr_width <= 0 || hr_height <= 0) {
    LogW("shift-add: non-positive size");
    return false;
  }
  if (!IsFinite(scale) || scale <= 0.0f) {
    LogW("shift-add: invalid scale");
    return false;
  }
  const float sx = IsFinite(dx) ? dx : 0.0f;
  const float sy = IsFinite(dy) ? dy : 0.0f;
  const int64_t hr_pixels = static_cast<int64_t>(hr_width) * static_cast<int64_t>(hr_height);
  int64_t valid = 0;
  int64_t index = 0;
  for (int32_t y = 0; y < hr_height; ++y) {
    const float uy = (static_cast<float>(y) + 0.5f) / scale - 0.5f;
    const float vy = uy + sy;
    for (int32_t x = 0; x < hr_width; ++x) {
      const float ux = (static_cast<float>(x) + 0.5f) / scale - 0.5f;
      const float v = BilinearAt(lr, lr_width, lr_height, ux + sx, vy);
      if (IsFinite(v)) {
        sum[index] += v;
        weight[index] += 1.0f;
        ++valid;
      }
      ++index;
    }
  }
  *out_coverage = static_cast<float>(valid) / static_cast<float>(hr_pixels);
  return true;
}

bool ResolveShiftAdd(const float* sum,
                     const float* weight,
                     float* out,
                     int32_t hr_width,
                     int32_t hr_height,
                     int32_t hole_passes) {
  if (sum == nullptr || weight == nullptr || out == nullptr) {
    LogW("resolve: null buffer");
    return false;
  }
  if (hr_width <= 0 || hr_height <= 0) {
    LogW("resolve: non-positive size");
    return false;
  }
  const int32_t pixels = hr_width * hr_height;
  std::vector<uint8_t> known(static_cast<size_t>(pixels), 0);
  double known_sum = 0.0;
  int32_t known_count = 0;
  for (int32_t i = 0; i < pixels; ++i) {
    const float w = weight[i];
    if (w > 0.0f) {
      const float v = sum[i] / w;
      out[i] = v;
      known[static_cast<size_t>(i)] = 1;
      known_sum += static_cast<double>(v);
      ++known_count;
    }
  }
  if (known_count == 0) {
    // 极端情况：一帧都没覆盖 → 输出中性灰，绝不越界写（与 Kotlin 同语义）。
    for (int32_t i = 0; i < pixels; ++i) out[i] = 0.0f;
    return true;
  }
  const float fallback = static_cast<float>(known_sum / static_cast<double>(known_count));

  int32_t holes = pixels - known_count;
  const int32_t passes = ClampInt(hole_passes, 0, kHolePassesMax);
  std::vector<int32_t> newly;
  std::vector<float> values;
  newly.reserve(static_cast<size_t>(holes));
  values.reserve(static_cast<size_t>(holes));
  for (int32_t pass = 0; pass < passes; ++pass) {
    if (holes == 0) break;
    newly.clear();
    values.clear();
    for (int32_t i = 0; i < pixels; ++i) {
      if (known[static_cast<size_t>(i)] != 0) continue;
      const int32_t x = i % hr_width;
      const int32_t y = i / hr_width;
      float s = 0.0f;
      int32_t n = 0;
      if (x > 0 && known[static_cast<size_t>(i) - 1] != 0) {
        s += out[i - 1];
        ++n;
      }
      if (x < hr_width - 1 && known[static_cast<size_t>(i) + 1] != 0) {
        s += out[i + 1];
        ++n;
      }
      if (y > 0 && known[static_cast<size_t>(i) - hr_width] != 0) {
        s += out[i - hr_width];
        ++n;
      }
      if (y < hr_height - 1 && known[static_cast<size_t>(i) + hr_width] != 0) {
        s += out[i + hr_width];
        ++n;
      }
      if (n > 0) {
        newly.push_back(i);
        values.push_back(s / static_cast<float>(n));
      }
    }
    for (size_t k = 0; k < newly.size(); ++k) {
      const int32_t i = newly[k];
      out[i] = values[k];
      known[static_cast<size_t>(i)] = 1;
    }
    holes -= static_cast<int32_t>(newly.size());
  }
  if (holes > 0) {
    for (int32_t i = 0; i < pixels; ++i) {
      if (known[static_cast<size_t>(i)] == 0) out[i] = fallback;
    }
  }
  return true;
}

bool IbpAccumulateFrame(const float* hr,
                        int32_t hr_width,
                        int32_t hr_height,
                        const float* lr,
                        int32_t lr_width,
                        int32_t lr_height,
                        float dx,
                        float dy,
                        float scale,
                        float* err,
                        float* wsum,
                        double* out_stats) {
  if (hr == nullptr || lr == nullptr || err == nullptr || wsum == nullptr || out_stats == nullptr) {
    LogW("ibp frame: null buffer");
    return false;
  }
  if (hr_width <= 0 || hr_height <= 0 || lr_width <= 0 || lr_height <= 0) {
    LogW("ibp frame: non-positive size");
    return false;
  }
  if (!IsFinite(dx) || !IsFinite(dy) || !IsFinite(scale) || scale <= 0.0f) {
    LogW("ibp frame: invalid shift/scale");
    return false;
  }

  const float hr_max_x = static_cast<float>(hr_width) - 1.0f;
  const float hr_max_y = static_cast<float>(hr_height) - 1.0f;
  double abs_sum = 0.0;
  int32_t sample_count = 0;

  for (int32_t y = 0; y < lr_height; ++y) {
    // LR 像素中心 → HR 连续坐标。
    const float hy = (static_cast<float>(y) - dy + 0.5f) * scale - 0.5f;
    if (hy < 0.0f || hy > hr_max_y) continue;
    const int32_t y0 = ClampInt(TruncToInt(hy), 0, hr_height - 1);
    const int32_t y1 = std::min(y0 + 1, hr_height - 1);
    const float fy = hy - static_cast<float>(y0);
    for (int32_t x = 0; x < lr_width; ++x) {
      const float hx = (static_cast<float>(x) - dx + 0.5f) * scale - 0.5f;
      if (hx < 0.0f || hx > hr_max_x) continue;
      const int32_t x0 = ClampInt(TruncToInt(hx), 0, hr_width - 1);
      const int32_t x1 = std::min(x0 + 1, hr_width - 1);
      const float fx = hx - static_cast<float>(x0);

      // 正向仿真：HR → LR（双线性）。
      const float v00 = hr[y0 * hr_width + x0];
      const float v10 = hr[y0 * hr_width + x1];
      const float v01 = hr[y1 * hr_width + x0];
      const float v11 = hr[y1 * hr_width + x1];
      const float top = v00 + (v10 - v00) * fx;
      const float bot = v01 + (v11 - v01) * fx;
      const float sim = top + (bot - top) * fy;

      const float e = lr[y * lr_width + x] - sim;
      if (!IsFinite(e)) continue;
      abs_sum += static_cast<double>(std::fabs(e));
      ++sample_count;

      // 反投影：双线性权重分摊（正向采样的伴随算子）。
      const float w00 = (1.0f - fx) * (1.0f - fy);
      const float w10 = fx * (1.0f - fy);
      const float w01 = (1.0f - fx) * fy;
      const float w11 = fx * fy;
      err[y0 * hr_width + x0] += e * w00;
      wsum[y0 * hr_width + x0] += w00;
      err[y0 * hr_width + x1] += e * w10;
      wsum[y0 * hr_width + x1] += w10;
      err[y1 * hr_width + x0] += e * w01;
      wsum[y1 * hr_width + x0] += w01;
      err[y1 * hr_width + x1] += e * w11;
      wsum[y1 * hr_width + x1] += w11;
    }
  }

  out_stats[0] = abs_sum;
  out_stats[1] = static_cast<double>(sample_count);
  return true;
}

bool IbpApplyStep(float* hr,
                  const float* err,
                  const float* wsum,
                  int32_t pixel_count,
                  float gain) {
  if (hr == nullptr || err == nullptr || wsum == nullptr) {
    LogW("ibp step: null buffer");
    return false;
  }
  if (pixel_count < 0) {
    LogW("ibp step: negative pixel count");
    return false;
  }
  for (int32_t k = 0; k < pixel_count; ++k) {
    const float ws = wsum[k];
    if (ws > 0.0f) {
      const float v = hr[k] + gain * (err[k] / ws);
      hr[k] = IsFinite(v) ? ClampFloat(v, kIbpClampLo, kIbpClampHi) : hr[k];
    }
  }
  return true;
}

bool BilateralLuma(const float* src,
                   float* dst,
                   int32_t width,
                   int32_t height,
                   int32_t radius,
                   float sigma_range,
                   float mix) {
  if (src == nullptr || dst == nullptr) {
    LogW("bilateral: null buffer");
    return false;
  }
  if (width <= 0 || height <= 0) {
    LogW("bilateral: non-positive size");
    return false;
  }
  const int32_t r = std::min(radius, kDenoiseRadiusMax);
  if (r <= 0 || !(mix > 0.0f)) {
    // 退化（radius <= 0 / mix <= 0）由调用方按"原样返回"处理，这里不接。
    return false;
  }
  const float sr = (IsFinite(sigma_range) && sigma_range > 1e-3f) ? sigma_range : 10.0f;

  // 空间核：exp(-i²/(2·max(1,r)²))，Kotlin 侧分母是 Double 运算。
  std::vector<float> spatial(static_cast<size_t>(r) + 1);
  const double r_eff = static_cast<double>(std::max(1, r));
  for (int32_t i = 0; i <= r; ++i) {
    spatial[static_cast<size_t>(i)] = static_cast<float>(
        std::exp(-static_cast<double>(i * i) / (2.0 * r_eff * r_eff)));
  }

  // 值域核 LUT：512 档，索引 = (v - center) + 256。
  std::vector<float> range_lut(512);
  for (int32_t i = 0; i < 512; ++i) {
    const float d = (static_cast<float>(i) - 256.0f) / 256.0f * sr * 2.0f;
    range_lut[static_cast<size_t>(i)] = static_cast<float>(
        std::exp(-static_cast<double>(d * d) / (2.0 * static_cast<double>(sr) *
                                                static_cast<double>(sr))));
  }

  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const float center = src[y * width + x];
      float acc = 0.0f;
      float wsum = 0.0f;
      for (int32_t dy = -r; dy <= r; ++dy) {
        const int32_t yy = ClampInt(y + dy, 0, height - 1);
        const float sy = spatial[static_cast<size_t>(std::abs(dy))];
        for (int32_t dx = -r; dx <= r; ++dx) {
          const int32_t xx = ClampInt(x + dx, 0, width - 1);
          const float v = src[yy * width + xx];
          const float diff = v - center;
          const int32_t index = ClampInt(TruncToInt(diff + 256.0f), 0, 511);
          const float wgt = sy * spatial[static_cast<size_t>(std::abs(dx))] *
                            range_lut[static_cast<size_t>(index)];
          acc += v * wgt;
          wsum += wgt;
        }
      }
      const float filtered = wsum > 0.0f ? acc / wsum : center;
      dst[y * width + x] = center + (filtered - center) * mix;
    }
  }
  return true;
}

namespace {

/** 可分离高斯模糊，等价 MfsrPlane.gaussianBlur（核半径 = ceil(3σ)，边界 clamp）。 */
void GaussianBlurPlane(const float* src,
                       int32_t width,
                       int32_t height,
                       float sigma,
                       std::vector<float>& out) {
  const float s = (IsFinite(sigma) && sigma > 0.05f) ? sigma : 0.05f;
  const int32_t radius = std::max(1, TruncToInt(CeilF(3.0f * s)));
  const int32_t size = radius * 2 + 1;
  std::vector<float> kernel(static_cast<size_t>(size));
  float sum = 0.0f;
  for (int32_t i = -radius; i <= radius; ++i) {
    // Kotlin: exp(-(i * i) / (2.0 * s * s))（分母 double）。
    const float v = static_cast<float>(std::exp(
        -static_cast<double>(i * i) /
        (2.0 * static_cast<double>(s) * static_cast<double>(s))));
    kernel[static_cast<size_t>(i + radius)] = v;
    sum += v;
  }
  for (int32_t i = 0; i < size; ++i) kernel[static_cast<size_t>(i)] /= sum;

  const int32_t pixels = width * height;
  std::vector<float> tmp(static_cast<size_t>(pixels), 0.0f);
  out.assign(static_cast<size_t>(pixels), 0.0f);
  // 水平趟
  for (int32_t y = 0; y < height; ++y) {
    const int32_t row = y * width;
    for (int32_t x = 0; x < width; ++x) {
      float acc = 0.0f;
      for (int32_t k = -radius; k <= radius; ++k) {
        acc += kernel[static_cast<size_t>(k + radius)] * ClampiAt(src, width, height, x + k, y);
      }
      tmp[static_cast<size_t>(row + x)] = acc;
    }
  }
  // 垂直趟
  for (int32_t y = 0; y < height; ++y) {
    const int32_t row = y * width;
    for (int32_t x = 0; x < width; ++x) {
      float acc = 0.0f;
      for (int32_t k = -radius; k <= radius; ++k) {
        const int32_t yy = ClampInt(k, 0, height - 1);
        acc += kernel[static_cast<size_t>(k + radius)] * tmp[static_cast<size_t>(yy * width + x)];
      }
      out[static_cast<size_t>(row + x)] = acc;
    }
  }
}

}  // namespace

bool UnsharpLuma(const float* src,
                 float* dst,
                 int32_t width,
                 int32_t height,
                 float amount,
                 float sigma) {
  if (src == nullptr || dst == nullptr) {
    LogW("unsharp: null buffer");
    return false;
  }
  if (width <= 0 || height <= 0) {
    LogW("unsharp: non-positive size");
    return false;
  }
  const int32_t pixels = width * height;
  if (!(amount > 0.0f)) {
    // 与 Kotlin `unsharp` 的 amount <= 0 分支等价：原样返回。
    for (int32_t i = 0; i < pixels; ++i) dst[i] = src[i];
    return true;
  }
  std::vector<float> blur;
  GaussianBlurPlane(src, width, height, sigma, blur);
  for (int32_t i = 0; i < pixels; ++i) {
    const float v = src[i];
    const float v2 = v + amount * (v - blur[static_cast<size_t>(i)]);
    dst[i] = IsFinite(v2) ? ClampFloat(v2, kIbpClampLo, kIbpClampHi) : v;
  }
  return true;
}

bool ClampPlane(float* data, int32_t count, float lo, float hi) {
  if (data == nullptr) {
    LogW("clamp: null buffer");
    return false;
  }
  if (count < 0) {
    LogW("clamp: negative count");
    return false;
  }
  const float a = std::min(lo, hi);
  const float b = std::max(lo, hi);
  for (int32_t i = 0; i < count; ++i) {
    const float v = data[i];
    data[i] = IsFinite(v) ? ClampFloat(v, a, b) : a;
  }
  return true;
}

}  // namespace mfsr

// ---------------------------------------------------------------------------
// JNI 桥
//
// 约定：只做「入参校验 + 取指针 + 调核心」，工作分辨率/迭代次数/批大小/空洞轮数
// 全部由 Kotlin 侧传入（PATTERN_SPEC §10.6）。任何校验失败都返回"不可用"哨兵
// （null / JNI_FALSE / -1），由 Kotlin 侧无痛回退纯 Kotlin 实现。
// ---------------------------------------------------------------------------

namespace {

/** 入参数组的 RAII 句柄：析构时自动 Release，避免任何 early-return 泄漏。 */
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

extern "C" JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeAbiVersion(JNIEnv* /* env */,
                                                                            jobject /* thiz */) {
  return static_cast<jint>(photon::nativecompute::kAbiVersion);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativePhaseCorrelate(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray ref,
    jfloatArray tgt,
    jint width,
    jint height,
    jint max_shift_px,
    jint align_max_side) {
  if (env == nullptr || ref == nullptr || tgt == nullptr || width <= 0 || height <= 0) {
    LogW("jni phase correlate: invalid arguments");
    return nullptr;
  }
  const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
  if (static_cast<int64_t>(env->GetArrayLength(ref)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(tgt)) < expected) {
    LogW("jni phase correlate: plane too small");
    return nullptr;
  }
  FloatArrayAccess ref_access(env, ref, false);
  FloatArrayAccess tgt_access(env, tgt, false);
  if (!ref_access.ok() || !tgt_access.ok()) {
    LogW("jni phase correlate: acquisition failed");
    return nullptr;
  }
  float dx = 0.0f;
  float dy = 0.0f;
  float confidence = 0.0f;
  const bool ok = photon::nativecompute::mfsr::PhaseCorrelate(
      ref_access.data(), tgt_access.data(), static_cast<int32_t>(width),
      static_cast<int32_t>(height), static_cast<int32_t>(max_shift_px),
      static_cast<int32_t>(align_max_side), &dx, &dy, &confidence);
  if (!ok) return nullptr;
  jfloatArray result = env->NewFloatArray(3);
  if (result == nullptr) return nullptr;
  const jfloat values[3] = {static_cast<jfloat>(dx), static_cast<jfloat>(dy),
                            static_cast<jfloat>(confidence)};
  env->SetFloatArrayRegion(result, 0, 3, values);
  return result;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeShiftAddAccumulate(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray lr,
    jfloatArray sum,
    jfloatArray weight,
    jint lr_width,
    jint lr_height,
    jint hr_width,
    jint hr_height,
    jfloat scale,
    jfloat dx,
    jfloat dy) {
  if (env == nullptr || lr == nullptr || sum == nullptr || weight == nullptr) {
    LogW("jni shift-add: null array");
    return -1.0f;
  }
  if (lr_width <= 0 || lr_height <= 0 || hr_width <= 0 || hr_height <= 0) {
    LogW("jni shift-add: non-positive size");
    return -1.0f;
  }
  const int64_t lr_expected = static_cast<int64_t>(lr_width) * static_cast<int64_t>(lr_height);
  const int64_t hr_expected = static_cast<int64_t>(hr_width) * static_cast<int64_t>(hr_height);
  if (static_cast<int64_t>(env->GetArrayLength(lr)) < lr_expected ||
      static_cast<int64_t>(env->GetArrayLength(sum)) < hr_expected ||
      static_cast<int64_t>(env->GetArrayLength(weight)) < hr_expected) {
    LogW("jni shift-add: array length mismatch");
    return -1.0f;
  }
  FloatArrayAccess lr_access(env, lr, false);
  FloatArrayAccess sum_access(env, sum, true);
  FloatArrayAccess weight_access(env, weight, true);
  if (!lr_access.ok() || !sum_access.ok() || !weight_access.ok()) {
    LogW("jni shift-add: acquisition failed");
    return -1.0f;
  }
  float coverage = -1.0f;
  const bool ok = photon::nativecompute::mfsr::ShiftAddAccumulate(
      lr_access.data(), static_cast<int32_t>(lr_width), static_cast<int32_t>(lr_height),
      sum_access.data(), weight_access.data(), static_cast<int32_t>(hr_width),
      static_cast<int32_t>(hr_height), static_cast<float>(scale), static_cast<float>(dx),
      static_cast<float>(dy), &coverage);
  return ok ? static_cast<jfloat>(coverage) : -1.0f;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeResolveShiftAdd(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray sum,
    jfloatArray weight,
    jfloatArray out,
    jint hr_width,
    jint hr_height,
    jint hole_passes) {
  if (env == nullptr || sum == nullptr || weight == nullptr || out == nullptr) {
    LogW("jni resolve: null array");
    return JNI_FALSE;
  }
  if (hr_width <= 0 || hr_height <= 0) {
    LogW("jni resolve: non-positive size");
    return JNI_FALSE;
  }
  const int64_t expected = static_cast<int64_t>(hr_width) * static_cast<int64_t>(hr_height);
  if (static_cast<int64_t>(env->GetArrayLength(sum)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(weight)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(out)) < expected) {
    LogW("jni resolve: array length mismatch");
    return JNI_FALSE;
  }
  FloatArrayAccess sum_access(env, sum, false);
  FloatArrayAccess weight_access(env, weight, false);
  FloatArrayAccess out_access(env, out, true);
  if (!sum_access.ok() || !weight_access.ok() || !out_access.ok()) {
    LogW("jni resolve: acquisition failed");
    return JNI_FALSE;
  }
  const bool ok = photon::nativecompute::mfsr::ResolveShiftAdd(
      sum_access.data(), weight_access.data(), out_access.data(), static_cast<int32_t>(hr_width),
      static_cast<int32_t>(hr_height), static_cast<int32_t>(hole_passes));
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeIbpAccumulateFrame(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray hr,
    jfloatArray lr,
    jfloatArray err,
    jfloatArray wsum,
    jint hr_width,
    jint hr_height,
    jint lr_width,
    jint lr_height,
    jfloat dx,
    jfloat dy,
    jfloat scale) {
  if (env == nullptr || hr == nullptr || lr == nullptr || err == nullptr || wsum == nullptr) {
    LogW("jni ibp frame: null array");
    return nullptr;
  }
  if (hr_width <= 0 || hr_height <= 0 || lr_width <= 0 || lr_height <= 0) {
    LogW("jni ibp frame: non-positive size");
    return nullptr;
  }
  const int64_t hr_expected = static_cast<int64_t>(hr_width) * static_cast<int64_t>(hr_height);
  const int64_t lr_expected = static_cast<int64_t>(lr_width) * static_cast<int64_t>(lr_height);
  if (static_cast<int64_t>(env->GetArrayLength(hr)) < hr_expected ||
      static_cast<int64_t>(env->GetArrayLength(err)) < hr_expected ||
      static_cast<int64_t>(env->GetArrayLength(wsum)) < hr_expected ||
      static_cast<int64_t>(env->GetArrayLength(lr)) < lr_expected) {
    LogW("jni ibp frame: array length mismatch");
    return nullptr;
  }
  FloatArrayAccess hr_access(env, hr, false);
  FloatArrayAccess lr_access(env, lr, false);
  FloatArrayAccess err_access(env, err, true);
  FloatArrayAccess wsum_access(env, wsum, true);
  if (!hr_access.ok() || !lr_access.ok() || !err_access.ok() || !wsum_access.ok()) {
    LogW("jni ibp frame: acquisition failed");
    return nullptr;
  }
  double stats[2] = {0.0, 0.0};
  const bool ok = photon::nativecompute::mfsr::IbpAccumulateFrame(
      hr_access.data(), static_cast<int32_t>(hr_width), static_cast<int32_t>(hr_height),
      lr_access.data(), static_cast<int32_t>(lr_width), static_cast<int32_t>(lr_height),
      static_cast<float>(dx), static_cast<float>(dy), static_cast<float>(scale), err_access.data(),
      wsum_access.data(), stats);
  if (!ok) return nullptr;
  jdoubleArray result = env->NewDoubleArray(2);
  if (result == nullptr) return nullptr;
  const jdouble values[2] = {stats[0], stats[1]};
  env->SetDoubleArrayRegion(result, 0, 2, values);
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeIbpApplyStep(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray hr,
    jfloatArray err,
    jfloatArray wsum,
    jint pixel_count,
    jfloat gain) {
  if (env == nullptr || hr == nullptr || err == nullptr || wsum == nullptr) {
    LogW("jni ibp step: null array");
    return JNI_FALSE;
  }
  if (pixel_count < 0) {
    LogW("jni ibp step: negative pixel count");
    return JNI_FALSE;
  }
  if (static_cast<int64_t>(env->GetArrayLength(hr)) < static_cast<int64_t>(pixel_count) ||
      static_cast<int64_t>(env->GetArrayLength(err)) < static_cast<int64_t>(pixel_count) ||
      static_cast<int64_t>(env->GetArrayLength(wsum)) < static_cast<int64_t>(pixel_count)) {
    LogW("jni ibp step: array length mismatch");
    return JNI_FALSE;
  }
  FloatArrayAccess hr_access(env, hr, true);
  FloatArrayAccess err_access(env, err, false);
  FloatArrayAccess wsum_access(env, wsum, false);
  if (!hr_access.ok() || !err_access.ok() || !wsum_access.ok()) {
    LogW("jni ibp step: acquisition failed");
    return JNI_FALSE;
  }
  const bool ok = photon::nativecompute::mfsr::IbpApplyStep(
      hr_access.data(), err_access.data(), wsum_access.data(), static_cast<int32_t>(pixel_count),
      static_cast<float>(gain));
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeBilateralLuma(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray src,
    jfloatArray dst,
    jint width,
    jint height,
    jint radius,
    jfloat sigma_range,
    jfloat mix) {
  if (env == nullptr || src == nullptr || dst == nullptr) {
    LogW("jni bilateral: null array");
    return JNI_FALSE;
  }
  if (width <= 0 || height <= 0) {
    LogW("jni bilateral: non-positive size");
    return JNI_FALSE;
  }
  const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
  if (static_cast<int64_t>(env->GetArrayLength(src)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(dst)) < expected) {
    LogW("jni bilateral: array length mismatch");
    return JNI_FALSE;
  }
  FloatArrayAccess src_access(env, src, false);
  FloatArrayAccess dst_access(env, dst, true);
  if (!src_access.ok() || !dst_access.ok()) {
    LogW("jni bilateral: acquisition failed");
    return JNI_FALSE;
  }
  const bool ok = photon::nativecompute::mfsr::BilateralLuma(
      src_access.data(), dst_access.data(), static_cast<int32_t>(width),
      static_cast<int32_t>(height), static_cast<int32_t>(radius), static_cast<float>(sigma_range),
      static_cast<float>(mix));
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeUnsharpLuma(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray src,
    jfloatArray dst,
    jint width,
    jint height,
    jfloat amount,
    jfloat sigma) {
  if (env == nullptr || src == nullptr || dst == nullptr) {
    LogW("jni unsharp: null array");
    return JNI_FALSE;
  }
  if (width <= 0 || height <= 0) {
    LogW("jni unsharp: non-positive size");
    return JNI_FALSE;
  }
  const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
  if (static_cast<int64_t>(env->GetArrayLength(src)) < expected ||
      static_cast<int64_t>(env->GetArrayLength(dst)) < expected) {
    LogW("jni unsharp: array length mismatch");
    return JNI_FALSE;
  }
  FloatArrayAccess src_access(env, src, false);
  FloatArrayAccess dst_access(env, dst, true);
  if (!src_access.ok() || !dst_access.ok()) {
    LogW("jni unsharp: acquisition failed");
    return JNI_FALSE;
  }
  const bool ok = photon::nativecompute::mfsr::UnsharpLuma(
      src_access.data(), dst_access.data(), static_cast<int32_t>(width),
      static_cast<int32_t>(height), static_cast<float>(amount), static_cast<float>(sigma));
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_nativecompute_NativeComputeBridge_nativeClampPlane(JNIEnv* env,
                                                                           jobject /* thiz */,
                                                                           jfloatArray data,
                                                                           jint count,
                                                                           jfloat lo,
                                                                           jfloat hi) {
  if (env == nullptr || data == nullptr) {
    LogW("jni clamp: null array");
    return JNI_FALSE;
  }
  if (count < 0) {
    LogW("jni clamp: negative count");
    return JNI_FALSE;
  }
  if (static_cast<int64_t>(env->GetArrayLength(data)) < static_cast<int64_t>(count)) {
    LogW("jni clamp: array length mismatch");
    return JNI_FALSE;
  }
  FloatArrayAccess data_access(env, data, true);
  if (!data_access.ok()) {
    LogW("jni clamp: acquisition failed");
    return JNI_FALSE;
  }
  const bool ok = photon::nativecompute::mfsr::ClampPlane(
      data_access.data(), static_cast<int32_t>(count), static_cast<float>(lo),
      static_cast<float>(hi));
  return ok ? JNI_TRUE : JNI_FALSE;
}

}  // namespace nativecompute
}  // namespace photon
