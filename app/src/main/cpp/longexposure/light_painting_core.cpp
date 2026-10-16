// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 长曝光核心算法实现。与 Kotlin 侧逐位一致的说明见头文件。

#include "light_painting_core.h"

#include <cmath>
#include <vector>

namespace photon {
namespace longexposure {
namespace {

// 与 Kotlin kotlin.math.roundToInt()（= java.lang.Math.round，floor(x + 0.5)）一致。
// 注意：这里刻意不用 std::nearbyint / std::rint（其舍入模式受 FP 环境影响）。
inline int32_t RoundHalfUp(float value) {
  return static_cast<int32_t>(std::floor(static_cast<double>(value) + 0.5));
}

inline int32_t ClampInt(int32_t value, int32_t low, int32_t high) {
  if (value < low) return low;
  if (value > high) return high;
  return value;
}

inline float ClampFloat(float value, float low, float high) {
  if (!(value == value)) return low;  // NaN -> low
  if (value < low) return low;
  if (value > high) return high;
  return value;
}

// 与 SuperMoonToneMapper.buildGlobalCurve 一致。
std::vector<float> BuildGlobalCurve(float threshold,
                                    float strength,
                                    float lift_exponent,
                                    float gamma) {
  std::vector<float> table(static_cast<size_t>(kToneMapLutSize) + 1, 0.0f);
  const float t = ClampFloat(threshold, 0.05f, 0.99f);
  const float denominator = 1.0f - t;
  for (int32_t i = 0; i <= kToneMapLutSize; ++i) {
    const float l = static_cast<float>(i) / static_cast<float>(kToneMapLutSize);
    // 1) 高光肩部：严格单调、上界 < 1。
    float shaped = l;
    if (l > t && denominator > 0.0f) {
      const float x = (l - t) / denominator;
      const float shoulder = strength > 0.0f ? x / (1.0f + strength * x) : x;
      shaped = t + denominator * shoulder;
    }
    // 2) 暗部提升（0 -> 0）。
    float lifted = shaped;
    if (lift_exponent != 1.0f) {
      lifted = std::pow(ClampFloat(shaped, 0.0f, 1.0f), lift_exponent);
    }
    // 3) gamma。
    float toned = lifted;
    if (gamma != 1.0f) {
      toned = std::pow(ClampFloat(lifted, 0.0f, 1.0f), gamma);
    }
    table[static_cast<size_t>(i)] = ClampFloat(toned, 0.0f, 1.0f);
  }
  table[0] = 0.0f;
  return table;
}

inline float LookupCurve(const std::vector<float>& table, float l) {
  const int32_t last = static_cast<int32_t>(table.size()) - 1;
  const int32_t index = ClampInt(RoundHalfUp(l * static_cast<float>(last)), 0, last);
  return table[static_cast<size_t>(index)];
}

// 与 SuperMoonToneMapper.boxBlur 一致的可分离盒模糊（clamp-to-edge）。
void BoxBlur(const float* src, float* out, float* tmp, int32_t width, int32_t height, int32_t radius) {
  if (radius <= 0) {
    for (int32_t i = 0; i < width * height; ++i) out[i] = src[i];
    return;
  }
  const int32_t r = radius;
  const float window = static_cast<float>(2 * r + 1);

  for (int32_t y = 0; y < height; ++y) {
    const int32_t row = y * width;
    float sum = 0.0f;
    for (int32_t dx = -r; dx <= r; ++dx) {
      sum += src[row + ClampInt(dx, 0, width - 1)];
    }
    for (int32_t x = 0; x < width; ++x) {
      tmp[row + x] = sum / window;
      const float remove = src[row + ClampInt(x - r, 0, width - 1)];
      const float add = src[row + ClampInt(x + r + 1, 0, width - 1)];
      sum += add - remove;
    }
  }
  for (int32_t x = 0; x < width; ++x) {
    float sum = 0.0f;
    for (int32_t dy = -r; dy <= r; ++dy) {
      sum += tmp[ClampInt(dy, 0, height - 1) * width + x];
    }
    for (int32_t y = 0; y < height; ++y) {
      out[y * width + x] = sum / window;
      const float remove = tmp[ClampInt(y - r, 0, height - 1) * width + x];
      const float add = tmp[ClampInt(y + r + 1, 0, height - 1) * width + x];
      sum += add - remove;
    }
  }
}

}  // namespace

int32_t SubtractMaxAccumulate(const int16_t* frame,
                              const int16_t* dark,
                              int16_t* accum,
                              int32_t sample_count,
                              float gain,
                              int32_t hot_threshold,
                              bool mask_hot_pixels,
                              bool seeded) {
  if (frame == nullptr || accum == nullptr || sample_count <= 0) return kInvalidInput;

  const float g = (gain == gain) ? ClampFloat(gain, kGainMin, kGainMax) : 1.0f;
  const bool mask = mask_hot_pixels && hot_threshold > 0 && dark != nullptr;
  int32_t hot_pixels = 0;
  for (int32_t i = 0; i < sample_count; ++i) {
    const int32_t dark_sample = dark != nullptr ? static_cast<int32_t>(dark[i]) : 0;
    int32_t value;
    if (mask && dark_sample >= hot_threshold) {
      value = 0;
      ++hot_pixels;
    } else {
      const int32_t subtracted = static_cast<int32_t>(frame[i]) -
          RoundHalfUp(static_cast<float>(dark_sample) * g);
      value = ClampInt(subtracted, 0, kSampleMax);
    }
    if (!seeded) {
      accum[i] = static_cast<int16_t>(value);
    } else if (value > static_cast<int32_t>(accum[i])) {
      accum[i] = static_cast<int16_t>(value);
    }
  }
  return hot_pixels;
}

bool ToneMap(int16_t* samples,
             int32_t width,
             int32_t height,
             float highlight_threshold,
             float highlight_strength,
             float shadow_lift,
             float gamma,
             int32_t local_radius,
             float local_amount) {
  if (samples == nullptr || width <= 0 || height <= 0) return false;
  const int64_t pixel_count = static_cast<int64_t>(width) * static_cast<int64_t>(height);
  const int64_t sample_count = pixel_count * 3;
  if (sample_count <= 0) return false;

  const float strength = highlight_strength == highlight_strength ? highlight_strength : 0.0f;
  const float lift = shadow_lift == shadow_lift ? shadow_lift : 0.0f;
  const float g = (gamma == gamma && gamma != 0.0f) ? gamma : 1.0f;
  const float amount = local_amount == local_amount ? local_amount : 0.0f;
  const float lift_exponent = 1.0f / (1.0f + ClampFloat(lift, 0.0f, 1.0f));

  const std::vector<float> curve =
      BuildGlobalCurve(highlight_threshold, strength, lift_exponent, g);

  std::vector<float> luma(static_cast<size_t>(pixel_count), 0.0f);
  std::vector<float> global(static_cast<size_t>(pixel_count), 0.0f);
  const float max_luma = static_cast<float>(kSampleMax);
  for (int64_t px = 0; px < pixel_count; ++px) {
    const int64_t base = px * 3;
    const float l = (0.2126f * static_cast<float>(samples[base]) +
                     0.7152f * static_cast<float>(samples[base + 1]) +
                     0.0722f * static_cast<float>(samples[base + 2])) / max_luma;
    const float clamped_luma = ClampFloat(l, 0.0f, 1.0f);
    luma[static_cast<size_t>(px)] = clamped_luma;
    global[static_cast<size_t>(px)] = LookupCurve(curve, clamped_luma);
  }

  std::vector<float> blurred;
  const bool use_blur = amount > 0.0f;
  if (use_blur) {
    blurred.assign(static_cast<size_t>(pixel_count), 0.0f);
    std::vector<float> tmp(static_cast<size_t>(pixel_count), 0.0f);
    BoxBlur(global.data(), blurred.data(), tmp.data(), width, height, local_radius);
  }

  for (int64_t px = 0; px < pixel_count; ++px) {
    const int64_t base = px * 3;
    const float l = luma[static_cast<size_t>(px)];
    if (l <= 0.0f) {
      // 纯黑保持纯黑（不放大噪声、不提亮天空）。
      samples[base] = 0;
      samples[base + 1] = 0;
      samples[base + 2] = 0;
      continue;
    }
    float mapped = global[static_cast<size_t>(px)];
    if (use_blur) {
      const float detail = global[static_cast<size_t>(px)] - blurred[static_cast<size_t>(px)];
      mapped += amount * detail * (1.0f - global[static_cast<size_t>(px)]);
    }
    mapped = ClampFloat(mapped, 0.0f, 1.0f);
    const float ratio = ClampFloat(mapped / l, 0.0f, kToneMapMaxRatio);
    for (int32_t c = 0; c < 3; ++c) {
      const int32_t scaled = RoundHalfUp(static_cast<float>(samples[base + c]) * ratio);
      samples[base + c] = static_cast<int16_t>(ClampInt(scaled, 0, kSampleMax));
    }
  }
  return true;
}

}  // namespace longexposure
}  // namespace photon
