// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 「创意拍摄玩法」全景拼接的 native 加速核心（PATTERN_SPEC §10）。
//
// 下沉的四段最热循环（其余全部留在 Kotlin 侧）：
//   1) Harris 角点检测（积分图窗口求和 + 3×3 局部极大 + 间距抑制）
//   2) BRIEF 二值描述子（确定性格点对 + 3×3 均值预平滑）
//   3) 暴力匹配 + Lowe ratio + 汉明距离上限
//   4) RANSAC 单应估计（归一化 4 点最小解 + 自适应早停 + 内点 DLT 重拟合）
//
// 与 Kotlin 侧 `funshot.PanoramaFeature` / `funshot.PanoramaHomography` **同算法、同参数、
// 同数据布局**，因此「native 可用」与「native 不可用」两条路径行为一致，可互相兜底。
// 内存预算 / 工作分辨率 / 迭代次数全部由 Kotlin 侧决定并传入（§10.6）：
// 本层不做调度、不做降级决策，只做防御性入参校验。

#ifndef PHOTON_FUNSHOT_PANORAMA_H_
#define PHOTON_FUNSHOT_PANORAMA_H_

#include <cstdint>
#include <vector>

namespace funshot {

// 灰度图：行主序 float，长度 width × height。
struct GrayView {
  const float* pixels;
  int width;
  int height;
};

// 返回 [x, y, score] × n；无角点/输入非法时返回空表。
std::vector<float> HarrisCorners(const GrayView& gray, int max_corners, float quality,
                                 int min_distance);

// key_points 为 [x, y] × key_point_count；返回 key_point_count × words 的行主序描述子。
std::vector<int64_t> BriefDescribe(const GrayView& gray, const float* key_points,
                                   int key_point_count, int bits, int64_t seed);

// 返回 [index_a, index_b, hamming_distance] × k。
std::vector<int32_t> MatchFeatures(const int64_t* desc_a, int count_a, const int64_t* desc_b,
                                   int count_b, int words, float ratio, int max_matches,
                                   int max_distance);

// 返回 [inlier_count, h0..h8, flag0..flag(count-1)]；失败返回空表。
std::vector<double> EstimateHomography(const double* src, const double* dst, int count,
                                       int iterations, double threshold, int min_inliers,
                                       double min_inlier_ratio, int64_t seed);

}  // namespace funshot

#endif  // PHOTON_FUNSHOT_PANORAMA_H_
