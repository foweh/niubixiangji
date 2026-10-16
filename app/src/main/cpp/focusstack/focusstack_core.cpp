// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 微距焦点堆叠 C++ 计算核实现。与纯 Kotlin 参考实现 `FocusClarityMap` 逐式对齐：
//   - 分离式盒式模糊：滑动窗口 + 边界钳位复制（O(w·h)）；
//   - 拉普拉斯方差：`Var = E[L²] - E[L]²`（负值截断 0）；
//   - 梯度能量：Sobel 幅值 → 局部均值；
//   - 归一化：按最大值定标后用 256 桶直方图取 99% 分位作为缩放基准；
//   - 高斯平滑：3 遍盒式（盒半径 = max(1, smooth_radius/2)）近似；
//   - 一致性滤波：权重超过「四邻域最大值 × 1.5」时压回邻域最大值。

#include "focusstack_core.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace focusstack {
namespace {

inline float ClampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

inline int ClampI(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

/** 可分离盒式模糊（半径 0 时原样拷贝）。 */
void BoxBlur(const float* src, float* dst, int width, int height, int radius) {
  if (width <= 0 || height <= 0) return;
  const int r = radius < 0 ? 0 : radius;
  if (r == 0) {
    std::copy(src, src + static_cast<size_t>(width) * height, dst);
    return;
  }
  const int window = 2 * r + 1;
  std::vector<float> tmp(static_cast<size_t>(width) * height);

  // 水平方向
  for (int y = 0; y < height; ++y) {
    const int row = y * width;
    float sum = 0.f;
    for (int k = -r; k <= r; ++k) sum += src[row + ClampI(k, 0, width - 1)];
    for (int x = 0; x < width; ++x) {
      tmp[row + x] = sum / static_cast<float>(window);
      sum += src[row + ClampI(x + r + 1, 0, width - 1)] -
             src[row + ClampI(x - r, 0, width - 1)];
    }
  }

  // 垂直方向
  for (int x = 0; x < width; ++x) {
    float sum = 0.f;
    for (int k = -r; k <= r; ++k) sum += tmp[ClampI(k, 0, height - 1) * width + x];
    for (int y = 0; y < height; ++y) {
      dst[y * width + x] = sum / static_cast<float>(window);
      sum += tmp[ClampI(y + r + 1, 0, height - 1) * width + x] -
             tmp[ClampI(y - r, 0, height - 1) * width + x];
    }
  }
}

/** 高斯平滑：3 遍盒式近似（radius <= 0 时拷贝）。 */
void GaussianBlur(const float* src, float* dst, int width, int height, int radius) {
  if (radius <= 0) {
    std::copy(src, src + static_cast<size_t>(width) * height, dst);
    return;
  }
  const int r = std::max(1, radius / 2);
  const size_t size = static_cast<size_t>(width) * height;
  std::vector<float> a(size);
  std::vector<float> b(size);
  BoxBlur(src, a.data(), width, height, r);
  BoxBlur(a.data(), b.data(), width, height, r);
  BoxBlur(b.data(), dst, width, height, r);
}

void LaplacianResponse(const float* luma, float* out, int width, int height) {
  for (int y = 0; y < height; ++y) {
    const int y0 = std::max(0, y - 1);
    const int y1 = std::min(height - 1, y + 1);
    for (int x = 0; x < width; ++x) {
      const int x0 = std::max(0, x - 1);
      const int x1 = std::min(width - 1, x + 1);
      const int i = y * width + x;
      const float c = luma[i];
      out[i] = 4.f * c - (luma[y * width + x0] + luma[y * width + x1] +
                          luma[y0 * width + x] + luma[y1 * width + x]);
    }
  }
}

void GradientMagnitude(const float* luma, float* out, int width, int height) {
  for (int y = 0; y < height; ++y) {
    const int ym = std::max(0, y - 1);
    const int yp = std::min(height - 1, y + 1);
    for (int x = 0; x < width; ++x) {
      const int xm = std::max(0, x - 1);
      const int xp = std::min(width - 1, x + 1);
      const float tl = luma[ym * width + xm];
      const float tc = luma[ym * width + x];
      const float tr = luma[ym * width + xp];
      const float ml = luma[y * width + xm];
      const float mr = luma[y * width + xp];
      const float bl = luma[yp * width + xm];
      const float bc = luma[yp * width + x];
      const float br = luma[yp * width + xp];
      const float gx = (tr + 2.f * mr + br) - (tl + 2.f * ml + bl);
      const float gy = (bl + 2.f * bc + br) - (tl + 2.f * tc + tr);
      out[y * width + x] = std::sqrt(gx * gx + gy * gy);
    }
  }
}

/** 局部方差：`Var = E[x²] - E[x]²`。 */
void LocalVariance(const float* values, float* out, int width, int height, int radius) {
  const size_t size = static_cast<size_t>(width) * height;
  std::vector<float> squared(size);
  for (size_t i = 0; i < size; ++i) squared[i] = values[i] * values[i];
  std::vector<float> mean(size);
  std::vector<float> mean_square(size);
  BoxBlur(values, mean.data(), width, height, radius);
  BoxBlur(squared.data(), mean_square.data(), width, height, radius);
  for (size_t i = 0; i < size; ++i) {
    const float v = mean_square[i] - mean[i] * mean[i];
    out[i] = (std::isnan(v) || v <= 0.f) ? 0.f : v;
  }
}

/** 稳健归一化（99% 分位作为缩放基准；全零返回全零）。 */
void Normalize(const float* src, float* out, size_t size, float percentile) {
  float max_value = 0.f;
  for (size_t i = 0; i < size; ++i) {
    const float v = src[i];
    if (std::isnan(v) || v <= 0.f) continue;
    if (v > max_value) max_value = v;
  }
  if (max_value <= 0.f) {
    std::fill(out, out + size, 0.f);
    return;
  }
  int hist[kHistBins] = {0};
  for (size_t i = 0; i < size; ++i) {
    const float v = src[i];
    if (std::isnan(v) || v <= 0.f) continue;
    const int bin = ClampI(static_cast<int>((v / max_value) * (kHistBins - 1)), 0, kHistBins - 1);
    hist[bin]++;
  }
  const float p = ClampF(percentile, 0.f, 1.f);
  long target = static_cast<long>(static_cast<double>(size) * p + 0.5);
  if (target < 1) target = 1;
  long cumulative = 0;
  float scale = max_value;
  for (int bin = 0; bin < kHistBins; ++bin) {
    cumulative += hist[bin];
    if (cumulative >= target) {
      scale = max_value * static_cast<float>(bin + 1) / static_cast<float>(kHistBins);
      break;
    }
  }
  if (!(scale > 0.f) || !std::isfinite(scale)) scale = max_value;
  for (size_t i = 0; i < size; ++i) {
    const float v = src[i];
    out[i] = (std::isnan(v) || v <= 0.f) ? 0.f : ClampF(v / scale, 0.f, 1.f);
  }
}

/** 跨帧归一化：逐像素 `mask_k = w_k / Σw`；Σw 为 0 时取均匀权重 1/n。 */
void NormalizeAcrossFrames(float* masks, int frame_count, size_t size) {
  if (frame_count <= 0 || size == 0) return;
  const float uniform = 1.f / static_cast<float>(frame_count);
  for (size_t i = 0; i < size; ++i) {
    float sum = 0.f;
    for (int k = 0; k < frame_count; ++k) {
      const float v = masks[static_cast<size_t>(k) * size + i];
      if (!std::isnan(v) && v > 0.f) sum += v;
    }
    if (sum <= kMaskEps) {
      for (int k = 0; k < frame_count; ++k) masks[static_cast<size_t>(k) * size + i] = uniform;
    } else {
      for (int k = 0; k < frame_count; ++k) {
        const size_t idx = static_cast<size_t>(k) * size + i;
        const float v = masks[idx];
        masks[idx] = (!std::isnan(v) && v > 0.f) ? v / sum : 0.f;
      }
    }
  }
}

/** 一致性滤波一轮：孤立（高于四邻域最大值 × 1.5）的权重压回邻域最大值。 */
void ConsistencyRound(float* masks, int frame_count, int width, int height) {
  const size_t size = static_cast<size_t>(width) * height;
  std::vector<float> src(size);
  for (int k = 0; k < frame_count; ++k) {
    float* mask = masks + static_cast<size_t>(k) * size;
    std::copy(mask, mask + size, src.begin());
    for (int y = 0; y < height; ++y) {
      for (int x = 0; x < width; ++x) {
        const int i = y * width + x;
        const float centre = src[i];
        float neighbour_max = 0.f;
        if (x > 0) neighbour_max = std::max(neighbour_max, src[i - 1]);
        if (x < width - 1) neighbour_max = std::max(neighbour_max, src[i + 1]);
        if (y > 0) neighbour_max = std::max(neighbour_max, src[i - width]);
        if (y < height - 1) neighbour_max = std::max(neighbour_max, src[i + width]);
        mask[i] = (neighbour_max > 0.f &&
                   centre > neighbour_max * kConsistencyRatioThreshold)
                      ? neighbour_max
                      : centre;
      }
    }
  }
}

}  // namespace

bool ComputeClarity(const float* luma,
                    float* out,
                    int width,
                    int height,
                    int metric,
                    int radius) {
  if (luma == nullptr || out == nullptr) return false;
  if (width <= 0 || height <= 0) return false;
  if (metric != static_cast<int>(Metric::kLaplacianVariance) &&
      metric != static_cast<int>(Metric::kGradientEnergy)) {
    return false;
  }
  const int r = ClampI(radius, kClarityRadiusMin, kClarityRadiusMax);
  const size_t size = static_cast<size_t>(width) * height;
  std::vector<float> detail(size);

  if (metric == static_cast<int>(Metric::kLaplacianVariance)) {
    LaplacianResponse(luma, detail.data(), width, height);
    LocalVariance(detail.data(), out, width, height, r);
  } else {
    GradientMagnitude(luma, detail.data(), width, height);
    BoxBlur(detail.data(), out, width, height, r);
  }
  return true;
}

bool SoftMask(const float* clarity_flat,
              float* mask_out,
              int frame_count,
              int width,
              int height,
              float exponent,
              int smooth_radius,
              int consistency) {
  if (clarity_flat == nullptr || mask_out == nullptr) return false;
  if (frame_count <= 0 || width <= 0 || height <= 0) return false;
  if (consistency < kConsistencyMin || consistency > kConsistencyMax) return false;
  const size_t size = static_cast<size_t>(width) * height;
  const float p = (std::isfinite(exponent) ? ClampF(exponent, 1.f, 16.f) : 4.f);

  std::vector<float> normalized(size);
  for (int k = 0; k < frame_count; ++k) {
    const float* src = clarity_flat + static_cast<size_t>(k) * size;
    float* dst = mask_out + static_cast<size_t>(k) * size;
    Normalize(src, normalized.data(), size, 0.99f);
    for (size_t i = 0; i < size; ++i) {
      const float v = normalized[i];
      dst[i] = (v <= 0.f) ? 0.f : std::pow(v, p);
    }
  }
  NormalizeAcrossFrames(mask_out, frame_count, size);

  const int smooth = ClampI(smooth_radius, kMaskSmoothRadiusMin, kMaskSmoothRadiusMax);
  if (smooth > 0) {
    std::vector<float> tmp(size);
    for (int k = 0; k < frame_count; ++k) {
      float* mask = mask_out + static_cast<size_t>(k) * size;
      GaussianBlur(mask, tmp.data(), width, height, smooth);
      std::copy(tmp.begin(), tmp.end(), mask);
    }
    NormalizeAcrossFrames(mask_out, frame_count, size);
  }

  const int rounds = ClampI(consistency, kConsistencyMin, kConsistencyMax);
  for (int i = 0; i < rounds; ++i) {
    ConsistencyRound(mask_out, frame_count, width, height);
    NormalizeAcrossFrames(mask_out, frame_count, size);
  }
  return true;
}

}  // namespace focusstack
