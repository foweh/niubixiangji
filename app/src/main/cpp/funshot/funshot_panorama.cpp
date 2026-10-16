// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 全景拼接 native 核心实现（自研；算法管线参考 OpenCV Stitcher/goodFeaturesToTrack/
// findHomography 的成熟做法，代码未复制第三方实现）。
//
// 与 Kotlin 侧一一对应：
//   HarrisCorners          ↔ FunHarrisCorner.detect
//   BriefDescribe          ↔ FunBriefDescriptor.describe（pattern 由同一 LCG 生成，可对齐复现）
//   MatchFeatures          ↔ FunFeatureMatcher.match
//   EstimateHomography     ↔ FunRansacHomography.estimate
//
// 数值约定与 Kotlin 完全一致：LCG（LCG_6364136223846793005 + 1442695040888963407）、
// 阈值 1e-6 的相对角点阈值、1e-3 的重合点判据、1e-2 的共线面积判据、1e-10 的主元下限、
// 1e-12 的齐次分母下限、0.995 的 RANSAC 置信度。

#include "funshot_panorama.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>

namespace funshot {
namespace {

constexpr float kHarrisK = 0.04f;
constexpr int kLocalRadius = 1;
constexpr int kWindowRadius = 2;
constexpr float kMinMaxResponse = 1e-6f;

constexpr int kPatternRadius = 13;
constexpr int kPatchSmoothRadius = 1;

constexpr int kMinPoints = 4;
constexpr double kCollinearAreaEps = 1e-2;
constexpr double kMinPairDistance = 1e-3;
constexpr double kPivotEps = 1e-10;
constexpr double kMatrixEps = 1e-12;
constexpr double kConfidence = 0.995;
constexpr double kRequiredIterationsCap = 1e9;

constexpr int64_t kLcgMultiplier = 6364136223846793005LL;
constexpr int64_t kLcgIncrement = 1442695040888963407LL;
constexpr uint64_t kLcgDefaultSeed = 0x2545F4914F6CDD1DULL;

// ----------------------------------------------------------------- LCG（与 Kotlin FunLcg 同序列）
class Lcg {
 public:
  explicit Lcg(int64_t seed) : state_(seed == 0 ? kLcgDefaultSeed : static_cast<uint64_t>(seed)) {}

  uint64_t Next() {
    state_ = state_ * static_cast<uint64_t>(kLcgMultiplier) +
             static_cast<uint64_t>(kLcgIncrement);
    return state_;
  }

  float NextRange(int min_value, int max_value) {
    const int span = max_value - min_value + 1;
    if (span <= 0) return static_cast<float>(min_value);
    const uint64_t r = (Next() >> 33) % static_cast<uint64_t>(span);
    return static_cast<float>(min_value + static_cast<int>(r));
  }

  uint64_t NextPositive() { return Next() >> 1; }

 private:
  uint64_t state_;
};

// ----------------------------------------------------------------- 灰度图采样
float At(const GrayView& gray, int x, int y) {
  if (x < 0 || y < 0 || x >= gray.width || y >= gray.height) return 0.f;
  return gray.pixels[static_cast<size_t>(y) * static_cast<size_t>(gray.width) + x];
}

// 双线性采样；采样点越界返回 false（对应 Kotlin 侧返回 null）。
bool Bilinear(const GrayView& gray, double x, double y, float* out) {
  if (!std::isfinite(x) || !std::isfinite(y)) return false;
  if (x < -0.5 || y < -0.5 || x > gray.width - 0.5 || y > gray.height - 0.5) return false;
  const int x0 = static_cast<int>(std::floor(x));
  const int y0 = static_cast<int>(std::floor(y));
  const float fx = static_cast<float>(x - x0);
  const float fy = static_cast<float>(y - y0);
  const float p00 = At(gray, x0, y0);
  const float p10 = At(gray, x0 + 1, y0);
  const float p01 = At(gray, x0, y0 + 1);
  const float p11 = At(gray, x0 + 1, y0 + 1);
  const float top = p00 + (p10 - p00) * fx;
  const float bottom = p01 + (p11 - p01) * fx;
  *out = top + (bottom - top) * fy;
  return true;
}

// 半径 radius 的方形窗口均值（积分图，O(1)/像素）。
std::vector<float> BoxSum(const std::vector<float>& src, int w, int h, int radius) {
  std::vector<double> integral(static_cast<size_t>(w + 1) * static_cast<size_t>(h + 1), 0.0);
  const size_t stride = static_cast<size_t>(w + 1);
  for (int y = 0; y < h; ++y) {
    double row_sum = 0.0;
    for (int x = 0; x < w; ++x) {
      row_sum += src[static_cast<size_t>(y) * w + x];
      integral[(static_cast<size_t>(y) + 1) * stride + (x + 1)] =
          integral[static_cast<size_t>(y) * stride + (x + 1)] + row_sum;
    }
  }
  std::vector<float> out(static_cast<size_t>(w) * h, 0.f);
  for (int y = 0; y < h; ++y) {
    const int y0 = std::max(0, y - radius);
    const int y1 = std::min(h - 1, y + radius);
    for (int x = 0; x < w; ++x) {
      const int x0 = std::max(0, x - radius);
      const int x1 = std::min(w - 1, x + radius);
      const double sum = integral[(static_cast<size_t>(y1) + 1) * stride + (x1 + 1)] -
                         integral[static_cast<size_t>(y0) * stride + (x1 + 1)] -
                         integral[(static_cast<size_t>(y1) + 1) * stride + x0] +
                         integral[static_cast<size_t>(y0) * stride + x0];
      const int area = (x1 - x0 + 1) * (y1 - y0 + 1);
      out[static_cast<size_t>(y) * w + x] = static_cast<float>(sum / area);
    }
  }
  return out;
}

bool IsLocalMax(const std::vector<float>& response, int w, int x, int y, float value) {
  for (int dy = -kLocalRadius; dy <= kLocalRadius; ++dy) {
    for (int dx = -kLocalRadius; dx <= kLocalRadius; ++dx) {
      if (dx == 0 && dy == 0) continue;
      const float v = response[static_cast<size_t>(y + dy) * w + (x + dx)];
      if (v > value) return false;
      if (v == value && (dy < 0 || (dy == 0 && dx < 0))) return false;
    }
  }
  return true;
}

// ----------------------------------------------------------------- 3×3 齐次矩阵
struct Matrix3 {
  double v[9];

  bool IsFinite() const {
    for (int i = 0; i < 9; ++i) {
      if (!std::isfinite(v[i])) return false;
    }
    return true;
  }

  double Determinant() const {
    return v[0] * (v[4] * v[8] - v[5] * v[7]) - v[1] * (v[3] * v[8] - v[5] * v[6]) +
           v[2] * (v[3] * v[7] - v[4] * v[6]);
  }

  bool Inverse(Matrix3* out) const {
    const double d = Determinant();
    if (!std::isfinite(d) || std::fabs(d) < kMatrixEps) return false;
    const double inv = 1.0 / d;
    out->v[0] = (v[4] * v[8] - v[5] * v[7]) * inv;
    out->v[1] = (v[2] * v[7] - v[1] * v[8]) * inv;
    out->v[2] = (v[1] * v[5] - v[2] * v[4]) * inv;
    out->v[3] = (v[5] * v[6] - v[3] * v[8]) * inv;
    out->v[4] = (v[0] * v[8] - v[2] * v[6]) * inv;
    out->v[5] = (v[2] * v[3] - v[0] * v[5]) * inv;
    out->v[6] = (v[3] * v[7] - v[4] * v[6]) * inv;
    out->v[7] = (v[1] * v[6] - v[0] * v[7]) * inv;
    out->v[8] = (v[0] * v[4] - v[1] * v[3]) * inv;
    return true;
  }

  Matrix3 Multiply(const Matrix3& other) const {
    Matrix3 r{};
    for (int row = 0; row < 3; ++row) {
      for (int col = 0; col < 3; ++col) {
        double sum = 0.0;
        for (int k = 0; k < 3; ++k) {
          sum += v[row * 3 + k] * other.v[k * 3 + col];
        }
        r.v[row * 3 + col] = sum;
      }
    }
    return r;
  }

  // 透视变换；|w| < kMatrixEps 或结果非有限返回 false。
  bool Transform(double px, double py, double* ox, double* oy) const {
    const double x = v[0] * px + v[1] * py + v[2];
    const double y = v[3] * px + v[4] * py + v[5];
    const double w = v[6] * px + v[7] * py + v[8];
    if (!std::isfinite(w) || std::fabs(w) < kMatrixEps) return false;
    const double rx = x / w;
    const double ry = y / w;
    if (!std::isfinite(rx) || !std::isfinite(ry)) return false;
    *ox = rx;
    *oy = ry;
    return true;
  }
};

// ----------------------------------------------------------------- RANSAC 支撑函数
bool SampleFour(int n, Lcg* rnd, int picked[4]) {
  if (n < kMinPoints) return false;
  int count = 0;
  int guard = 0;
  while (count < kMinPoints && guard < 64) {
    const int candidate = static_cast<int>(rnd->NextPositive() % static_cast<uint64_t>(n));
    bool duplicate = false;
    for (int i = 0; i < count; ++i) {
      if (picked[i] == candidate) {
        duplicate = true;
        break;
      }
    }
    if (!duplicate) picked[count++] = candidate;
    guard++;
  }
  return count == kMinPoints;
}

// 退化判定：存在重合点，或最大三角形面积过小（近似共线）。
bool Degenerate(const double* points, int count) {
  for (int i = 0; i < count; ++i) {
    for (int j = i + 1; j < count; ++j) {
      const double dx = points[i * 2] - points[j * 2];
      const double dy = points[i * 2 + 1] - points[j * 2 + 1];
      if (std::sqrt(dx * dx + dy * dy) < kMinPairDistance) return true;
    }
  }
  double max_area = 0.0;
  for (int i = 0; i < count; ++i) {
    for (int j = i + 1; j < count; ++j) {
      for (int k = j + 1; k < count; ++k) {
        const double area =
            std::fabs((points[j * 2] - points[i * 2]) * (points[k * 2 + 1] - points[i * 2 + 1]) -
                      (points[k * 2] - points[i * 2]) * (points[j * 2 + 1] - points[i * 2 + 1])) *
            0.5;
        if (area > max_area) max_area = area;
      }
    }
  }
  return max_area < kCollinearAreaEps;
}

// Hartley 归一化：质心到原点 + 平均距离缩放到 √2。
bool Normalize(const double* points, int count, std::vector<double>* out, Matrix3* transform) {
  if (count <= 0) return false;
  double cx = 0.0;
  double cy = 0.0;
  for (int i = 0; i < count; ++i) {
    cx += points[i * 2];
    cy += points[i * 2 + 1];
  }
  cx /= count;
  cy /= count;
  double mean_dist = 0.0;
  for (int i = 0; i < count; ++i) {
    const double dx = points[i * 2] - cx;
    const double dy = points[i * 2 + 1] - cy;
    mean_dist += std::sqrt(dx * dx + dy * dy);
  }
  mean_dist /= count;
  const double scale = mean_dist > 1e-9 ? std::sqrt(2.0) / mean_dist : 1.0;
  transform->v[0] = scale;
  transform->v[1] = 0.0;
  transform->v[2] = -scale * cx;
  transform->v[3] = 0.0;
  transform->v[4] = scale;
  transform->v[5] = -scale * cy;
  transform->v[6] = 0.0;
  transform->v[7] = 0.0;
  transform->v[8] = 1.0;
  out->assign(static_cast<size_t>(count) * 2, 0.0);
  for (int i = 0; i < count; ++i) {
    (*out)[i * 2] = (points[i * 2] - cx) * scale;
    (*out)[i * 2 + 1] = (points[i * 2 + 1] - cy) * scale;
  }
  return true;
}

// 高斯消元（部分主元）；奇异/非有限返回 false。
bool SolveLinear(const std::vector<std::vector<double> >& a, const std::vector<double>& b,
                 std::vector<double>* x) {
  const int n = static_cast<int>(b.size());
  if (n <= 0) return false;
  std::vector<std::vector<double> > m = a;
  std::vector<double> rhs = b;
  for (int col = 0; col < n; ++col) {
    int pivot_row = col;
    double pivot_value = std::fabs(m[col][col]);
    for (int row = col + 1; row < n; ++row) {
      const double candidate = std::fabs(m[row][col]);
      if (candidate > pivot_value) {
        pivot_value = candidate;
        pivot_row = row;
      }
    }
    if (pivot_value < kPivotEps) return false;
    if (pivot_row != col) {
      m[col].swap(m[pivot_row]);
      std::swap(rhs[col], rhs[pivot_row]);
    }
    const double pivot = m[col][col];
    for (int row = col + 1; row < n; ++row) {
      const double factor = m[row][col] / pivot;
      if (factor == 0.0) continue;
      for (int k = col; k < n; ++k) m[row][k] -= factor * m[col][k];
      rhs[row] -= factor * rhs[col];
    }
  }
  x->assign(static_cast<size_t>(n), 0.0);
  for (int row = n - 1; row >= 0; --row) {
    double sum = rhs[row];
    for (int k = row + 1; k < n; ++k) sum -= m[row][k] * (*x)[k];
    if (std::fabs(m[row][row]) < kPivotEps) return false;
    (*x)[row] = sum / m[row][row];
    if (!std::isfinite((*x)[row])) return false;
  }
  return true;
}

// 4 点最小解：固定 h33 = 1，解 8×8 线性系统（归一化坐标下求解后反归一化）。
bool SolveFourPoint(const double* src, const double* dst, Matrix3* out) {
  std::vector<double> ns;
  std::vector<double> nd;
  Matrix3 t_src{};
  Matrix3 t_dst{};
  if (!Normalize(src, kMinPoints, &ns, &t_src)) return false;
  if (!Normalize(dst, kMinPoints, &nd, &t_dst)) return false;

  std::vector<std::vector<double> > a(kMinPoints * 2, std::vector<double>(8, 0.0));
  std::vector<double> b(kMinPoints * 2, 0.0);
  for (int i = 0; i < kMinPoints; ++i) {
    const double x = ns[i * 2];
    const double y = ns[i * 2 + 1];
    const double u = nd[i * 2];
    const double v = nd[i * 2 + 1];
    a[i * 2][0] = x;
    a[i * 2][1] = y;
    a[i * 2][2] = 1.0;
    a[i * 2][6] = -u * x;
    a[i * 2][7] = -u * y;
    b[i * 2] = u;
    a[i * 2 + 1][3] = x;
    a[i * 2 + 1][4] = y;
    a[i * 2 + 1][5] = 1.0;
    a[i * 2 + 1][6] = -v * x;
    a[i * 2 + 1][7] = -v * y;
    b[i * 2 + 1] = v;
  }
  std::vector<double> solved;
  if (!SolveLinear(a, b, &solved)) return false;

  Matrix3 hn{};
  hn.v[0] = solved[0];
  hn.v[1] = solved[1];
  hn.v[2] = solved[2];
  hn.v[3] = solved[3];
  hn.v[4] = solved[4];
  hn.v[5] = solved[5];
  hn.v[6] = solved[6];
  hn.v[7] = solved[7];
  hn.v[8] = 1.0;

  Matrix3 t_dst_inv{};
  if (!t_dst.Inverse(&t_dst_inv)) return false;
  const Matrix3 h = t_dst_inv.Multiply(hn).Multiply(t_src);
  if (!h.IsFinite()) return false;
  *out = h;
  return true;
}

// 对称矩阵最小特征值对应的特征向量（循环 Jacobi，与 Kotlin 实现同序）。
bool JacobiSmallestEigenvector(const std::vector<std::vector<double> >& matrix,
                               std::vector<double>* eigen) {
  const int n = static_cast<int>(matrix.size());
  if (n == 0) return false;
  for (int i = 0; i < n; ++i) {
    if (static_cast<int>(matrix[i].size()) != n) return false;
    for (int j = 0; j < n; ++j) {
      if (!std::isfinite(matrix[i][j])) return false;
    }
  }
  std::vector<std::vector<double> > a = matrix;
  std::vector<std::vector<double> > v(n, std::vector<double>(n, 0.0));
  for (int i = 0; i < n; ++i) v[i][i] = 1.0;

  for (int sweep = 0; sweep < 64; ++sweep) {
    double off_diagonal = 0.0;
    for (int p = 0; p < n - 1; ++p) {
      for (int q = p + 1; q < n; ++q) off_diagonal += a[p][q] * a[p][q];
    }
    if (off_diagonal < 1e-24) break;
    for (int p = 0; p < n - 1; ++p) {
      for (int q = p + 1; q < n; ++q) {
        const double apq = a[p][q];
        if (std::fabs(apq) < 1e-18) continue;
        const double theta = (a[q][q] - a[p][p]) / (2.0 * apq);
        const double t = (theta >= 0 ? 1.0 : -1.0) / (std::fabs(theta) + std::sqrt(theta * theta + 1.0));
        const double c = 1.0 / std::sqrt(t * t + 1.0);
        const double s = t * c;
        for (int k = 0; k < n; ++k) {
          const double akp = a[k][p];
          const double akq = a[k][q];
          a[k][p] = c * akp - s * akq;
          a[k][q] = s * akp + c * akq;
        }
        for (int k = 0; k < n; ++k) {
          const double apk = a[p][k];
          const double aqk = a[q][k];
          a[p][k] = c * apk - s * aqk;
          a[q][k] = s * apk + c * aqk;
        }
        for (int k = 0; k < n; ++k) {
          const double vkp = v[k][p];
          const double vkq = v[k][q];
          v[k][p] = c * vkp - s * vkq;
          v[k][q] = s * vkp + c * vkq;
        }
      }
    }
  }

  int best_index = 0;
  double best_value = a[0][0];
  for (int i = 1; i < n; ++i) {
    if (a[i][i] < best_value) {
      best_value = a[i][i];
      best_index = i;
    }
  }
  eigen->assign(static_cast<size_t>(n), 0.0);
  double norm = 0.0;
  for (int i = 0; i < n; ++i) {
    (*eigen)[i] = v[i][best_index];
    norm += (*eigen)[i] * (*eigen)[i];
  }
  norm = std::sqrt(norm);
  if (norm < 1e-18 || !std::isfinite(norm)) return false;
  for (int i = 0; i < n; ++i) (*eigen)[i] /= norm;

  // 固定符号：令最大绝对分量为正，保证同一输入产生同一输出。
  double max_abs = 0.0;
  int max_index = 0;
  for (int i = 0; i < n; ++i) {
    const double abs_value = std::fabs((*eigen)[i]);
    if (abs_value > max_abs) {
      max_abs = abs_value;
      max_index = i;
    }
  }
  if ((*eigen)[max_index] < 0) {
    for (int i = 0; i < n; ++i) (*eigen)[i] = -(*eigen)[i];
  }
  return true;
}

// 多点 DLT：AᵀA 的最小特征向量作为 h，再归一化到 h33 = 1。
bool SolveDlt(const double* src, const double* dst, int count, Matrix3* out) {
  if (count < kMinPoints) return false;
  std::vector<double> ns;
  std::vector<double> nd;
  Matrix3 t_src{};
  Matrix3 t_dst{};
  if (!Normalize(src, count, &ns, &t_src)) return false;
  if (!Normalize(dst, count, &nd, &t_dst)) return false;

  std::vector<std::vector<double> > ata(9, std::vector<double>(9, 0.0));
  for (int i = 0; i < count; ++i) {
    const double x = ns[i * 2];
    const double y = ns[i * 2 + 1];
    const double u = nd[i * 2];
    const double v = nd[i * 2 + 1];
    const double r1[9] = {x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, -u};
    const double r2[9] = {0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, -v};
    for (int row = 0; row < 9; ++row) {
      for (int col = row; col < 9; ++col) {
        const double value = r1[row] * r1[col] + r2[row] * r2[col];
        ata[row][col] += value;
        if (row != col) ata[col][row] += value;
      }
    }
  }

  std::vector<double> smallest;
  if (!JacobiSmallestEigenvector(ata, &smallest)) return false;
  Matrix3 hn{};
  for (int i = 0; i < 9; ++i) hn.v[i] = smallest[i];

  Matrix3 t_dst_inv{};
  if (!t_dst.Inverse(&t_dst_inv)) return false;
  const Matrix3 h = t_dst_inv.Multiply(hn).Multiply(t_src);
  if (!h.IsFinite() || std::fabs(h.Determinant()) < 1e-9) return false;

  const double w = h.v[8];
  if (std::fabs(w) > 1e-9) {
    const double s = 1.0 / w;
    Matrix3 scaled{};
    for (int i = 0; i < 9; ++i) scaled.v[i] = h.v[i] * s;
    if (!scaled.IsFinite()) return false;
    *out = scaled;
  } else {
    *out = h;
  }
  return true;
}

// 重投影误差（src → dst 方向）；不可投影返回 DBL_MAX。
double TransferError(const Matrix3& h, double px, double py, double qx, double qy) {
  double ox = 0.0;
  double oy = 0.0;
  if (!h.Transform(px, py, &ox, &oy)) return std::numeric_limits<double>::max();
  const double dx = ox - qx;
  const double dy = oy - qy;
  const double err = std::sqrt(dx * dx + dy * dy);
  return std::isfinite(err) ? err : std::numeric_limits<double>::max();
}

void CollectInliers(const Matrix3& h, const double* src, const double* dst, int count,
                    double threshold, std::vector<int>* inliers) {
  inliers->clear();
  for (int i = 0; i < count; ++i) {
    if (TransferError(h, src[i * 2], src[i * 2 + 1], dst[i * 2], dst[i * 2 + 1]) <= threshold) {
      inliers->push_back(i);
    }
  }
}

// 自适应迭代次数下界（RANSAC 置信度 0.995）。
double RequiredIterations(double ratio) {
  if (ratio <= 0.0) return std::numeric_limits<double>::max();
  const double inlier_prob = std::min(ratio, 0.999);
  const double denom = std::log(1.0 - inlier_prob * inlier_prob * inlier_prob * inlier_prob);
  if (denom >= -1e-12) return std::numeric_limits<double>::max();
  return std::log(1.0 - kConfidence) / denom;
}

}  // namespace

// ----------------------------------------------------------------- 角点检测
std::vector<float> HarrisCorners(const GrayView& gray, int max_corners, float quality,
                                 int min_distance) {
  std::vector<float> result;
  const int w = gray.width;
  const int h = gray.height;
  if (gray.pixels == nullptr || w < 5 || h < 5 || max_corners <= 0) return result;

  const size_t total = static_cast<size_t>(w) * static_cast<size_t>(h);
  std::vector<float> gx(total, 0.f);
  std::vector<float> gy(total, 0.f);
  for (int y = 1; y < h - 1; ++y) {
    for (int x = 1; x < w - 1; ++x) {
      const size_t i = static_cast<size_t>(y) * w + x;
      gx[i] = (gray.pixels[i + 1] - gray.pixels[i - 1]) * 0.5f;
      gy[i] = (gray.pixels[i + w] - gray.pixels[i - w]) * 0.5f;
    }
  }

  std::vector<float> ixx(total, 0.f);
  std::vector<float> iyy(total, 0.f);
  std::vector<float> ixy(total, 0.f);
  for (size_t i = 0; i < total; ++i) {
    ixx[i] = gx[i] * gx[i];
    iyy[i] = gy[i] * gy[i];
    ixy[i] = gx[i] * gy[i];
  }

  const std::vector<float> sxx = BoxSum(ixx, w, h, kWindowRadius);
  const std::vector<float> syy = BoxSum(iyy, w, h, kWindowRadius);
  const std::vector<float> sxy = BoxSum(ixy, w, h, kWindowRadius);

  std::vector<float> response(total, 0.f);
  float max_response = 0.f;
  for (size_t i = 0; i < total; ++i) {
    const float det = sxx[i] * syy[i] - sxy[i] * sxy[i];
    const float trace = sxx[i] + syy[i];
    const float r = det - kHarrisK * trace * trace;
    response[i] = r;
    if (r > max_response) max_response = r;
  }
  if (max_response <= kMinMaxResponse) return result;

  const float q = std::min(1.f, std::max(1e-6f, quality));
  const float threshold = q * max_response;

  std::vector<PanoramaKeyPoint> candidates;
  for (int y = kLocalRadius; y < h - kLocalRadius; ++y) {
    for (int x = kLocalRadius; x < w - kLocalRadius; ++x) {
      const size_t i = static_cast<size_t>(y) * w + x;
      const float r = response[i];
      if (r < threshold) continue;
      if (!IsLocalMax(response, w, x, y, r)) continue;
      PanoramaKeyPoint candidate;
      candidate.x = static_cast<float>(x);
      candidate.y = static_cast<float>(y);
      candidate.score = r;
      candidates.push_back(candidate);
    }
  }
  if (candidates.empty()) return result;

  std::stable_sort(candidates.begin(), candidates.end(),
                   [](const PanoramaKeyPoint& a, const PanoramaKeyPoint& b) {
                     return a.score > b.score;
                   });

  const int clamped_distance = std::max(1, min_distance);
  const float min_dist_sq = static_cast<float>(clamped_distance) * static_cast<float>(clamped_distance);
  for (size_t c = 0; c < candidates.size(); ++c) {
    const PanoramaKeyPoint& candidate = candidates[c];
    bool ok = true;
    for (size_t k = 0; k < result.size(); k += 3) {
      const float dx = result[k] - candidate.x;
      const float dy = result[k + 1] - candidate.y;
      if (dx * dx + dy * dy < min_dist_sq) {
        ok = false;
        break;
      }
    }
    if (!ok) continue;
    result.push_back(candidate.x);
    result.push_back(candidate.y);
    result.push_back(candidate.score);
    if (static_cast<int>(result.size() / 3) >= max_corners) break;
  }
  return result;
}

// ----------------------------------------------------------------- BRIEF 描述子
std::vector<int64_t> BriefDescribe(const GrayView& gray, const float* key_points,
                                   int key_point_count, int bits, int64_t seed) {
  std::vector<int64_t> result;
  const int w = gray.width;
  const int h = gray.height;
  if (gray.pixels == nullptr || key_points == nullptr || key_point_count <= 0 || w <= 0 || h <= 0) {
    return result;
  }
  const int clamped_bits = std::min(512, std::max(64, bits));
  const int words = clamped_bits / 64;
  const int total_bits = words * 64;

  // 3×3 均值预平滑（与 Kotlin BoxSmooth 同定义：只统计越界外不计入的窗口内像素）。
  std::vector<float> smoothed(static_cast<size_t>(w) * h, 0.f);
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      float sum = 0.f;
      int count = 0;
      for (int dy = -kPatchSmoothRadius; dy <= kPatchSmoothRadius; ++dy) {
        for (int dx = -kPatchSmoothRadius; dx <= kPatchSmoothRadius; ++dx) {
          const int xx = x + dx;
          const int yy = y + dy;
          if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
          sum += gray.pixels[static_cast<size_t>(yy) * w + xx];
          count++;
        }
      }
      smoothed[static_cast<size_t>(y) * w + x] = count > 0 ? sum / count : 0.f;
    }
  }
  GrayView smooth_view;
  smooth_view.pixels = smoothed.data();
  smooth_view.width = w;
  smooth_view.height = h;

  // 确定性格点对（与 Kotlin patternOffsets 完全相同的取值顺序）。
  std::vector<float> pattern(static_cast<size_t>(total_bits) * 4, 0.f);
  {
    Lcg rnd(seed);
    for (int b = 0; b < total_bits; ++b) {
      pattern[b * 4] = rnd.NextRange(-kPatternRadius, kPatternRadius);
      pattern[b * 4 + 1] = rnd.NextRange(-kPatternRadius, kPatternRadius);
      pattern[b * 4 + 2] = rnd.NextRange(-kPatternRadius, kPatternRadius);
      pattern[b * 4 + 3] = rnd.NextRange(-kPatternRadius, kPatternRadius);
    }
  }

  result.assign(static_cast<size_t>(key_point_count) * words, 0);
  for (int index = 0; index < key_point_count; ++index) {
    const float px = key_points[index * 2];
    const float py = key_points[index * 2 + 1];
    for (int b = 0; b < total_bits; ++b) {
      const double ax = px + pattern[b * 4];
      const double ay = py + pattern[b * 4 + 1];
      const double bx = px + pattern[b * 4 + 2];
      const double by = py + pattern[b * 4 + 3];
      float va = 0.f;
      float vb = 0.f;
      if (!Bilinear(smooth_view, ax, ay, &va)) continue;
      if (!Bilinear(smooth_view, bx, by, &vb)) continue;
      if (va < vb) {
        result[static_cast<size_t>(index) * words + b / 64] |=
            static_cast<int64_t>(static_cast<uint64_t>(1) << (b % 64));
      }
    }
  }
  return result;
}

// ----------------------------------------------------------------- 特征匹配
std::vector<int32_t> MatchFeatures(const int64_t* desc_a, int count_a, const int64_t* desc_b,
                                   int count_b, int words, float ratio, int max_matches,
                                   int max_distance) {
  std::vector<int32_t> matches;
  if (desc_a == nullptr || desc_b == nullptr) return matches;
  if (count_a <= 0 || count_b <= 0 || words <= 0 || words > 8) return matches;

  const float clamped_ratio = std::min(1.f, std::max(0.1f, ratio));
  const int limit = std::max(1, max_matches);

  for (int i = 0; i < count_a; ++i) {
    int best = std::numeric_limits<int>::max();
    int second = std::numeric_limits<int>::max();
    int best_index = -1;
    for (int j = 0; j < count_b; ++j) {
      int d = 0;
      for (int k = 0; k < words; ++k) {
        d += __builtin_popcountll(static_cast<unsigned long long>(
            desc_a[static_cast<size_t>(i) * words + k] ^ desc_b[static_cast<size_t>(j) * words + k]));
      }
      if (d < best) {
        second = best;
        best = d;
        best_index = j;
      } else if (d < second) {
        second = d;
      }
    }
    if (best_index < 0) continue;
    if (best > max_distance) continue;
    if (second != std::numeric_limits<int>::max() && best > clamped_ratio * second) continue;
    matches.push_back(static_cast<int32_t>(i));
    matches.push_back(static_cast<int32_t>(best_index));
    matches.push_back(static_cast<int32_t>(best));
    if (static_cast<int>(matches.size() / 3) >= limit) break;
  }
  return matches;
}

// ----------------------------------------------------------------- RANSAC 单应估计
std::vector<double> EstimateHomography(const double* src, const double* dst, int count,
                                       int iterations, double threshold, int min_inliers,
                                       double min_inlier_ratio, int64_t seed) {
  std::vector<double> result;
  if (src == nullptr || dst == nullptr || count < kMinPoints) return result;

  const double threshold_px = (std::isfinite(threshold) && threshold > 0.0) ? threshold : 3.0;
  const int max_iter = std::min(100000, std::max(1, iterations));
  Lcg rnd(seed);

  std::vector<int> best_inliers;
  Matrix3 best_h{};
  bool has_best = false;
  int iterations_done = 0;

  for (int iter = 0; iter < max_iter; ++iter) {
    iterations_done = iter + 1;
    int picked[kMinPoints];
    if (!SampleFour(count, &rnd, picked)) continue;

    double sample_src[kMinPoints * 2];
    double sample_dst[kMinPoints * 2];
    for (int i = 0; i < kMinPoints; ++i) {
      sample_src[i * 2] = src[picked[i] * 2];
      sample_src[i * 2 + 1] = src[picked[i] * 2 + 1];
      sample_dst[i * 2] = dst[picked[i] * 2];
      sample_dst[i * 2 + 1] = dst[picked[i] * 2 + 1];
    }
    if (Degenerate(sample_src, kMinPoints) || Degenerate(sample_dst, kMinPoints)) continue;

    Matrix3 hypothesis{};
    if (!SolveFourPoint(sample_src, sample_dst, &hypothesis)) continue;

    std::vector<int> inliers;
    CollectInliers(hypothesis, src, dst, count, threshold_px, &inliers);
    if (inliers.size() > best_inliers.size()) {
      best_inliers.swap(inliers);
      best_h = hypothesis;
      has_best = true;
      const double inlier_ratio = static_cast<double>(best_inliers.size()) / count;
      const double required = RequiredIterations(inlier_ratio);
      if (required < kRequiredIterationsCap &&
          iterations_done >= static_cast<int>(required)) {
        break;
      }
    }
  }

  if (!has_best || best_inliers.size() < static_cast<size_t>(kMinPoints)) return result;

  // 内点重拟合（>=5 点时走 DLT，否则沿用 4 点解）。
  Matrix3 final_h = best_h;
  if (best_inliers.size() >= 5) {
    std::vector<double> refit_src(best_inliers.size() * 2, 0.0);
    std::vector<double> refit_dst(best_inliers.size() * 2, 0.0);
    for (size_t i = 0; i < best_inliers.size(); ++i) {
      refit_src[i * 2] = src[best_inliers[i] * 2];
      refit_src[i * 2 + 1] = src[best_inliers[i] * 2 + 1];
      refit_dst[i * 2] = dst[best_inliers[i] * 2];
      refit_dst[i * 2 + 1] = dst[best_inliers[i] * 2 + 1];
    }
    Matrix3 refined{};
    if (SolveDlt(refit_src.data(), refit_dst.data(), static_cast<int>(best_inliers.size()),
                 &refined) &&
        refined.IsFinite()) {
      final_h = refined;
    }
  }

  std::vector<int> final_inliers;
  CollectInliers(final_h, src, dst, count, threshold_px, &final_inliers);

  result.assign(static_cast<size_t>(10 + count), 0.0);
  result[0] = static_cast<double>(final_inliers.size());
  for (int i = 0; i < 9; ++i) result[1 + i] = final_h.v[i];
  for (size_t i = 0; i < final_inliers.size(); ++i) {
    const int index = final_inliers[i];
    if (index >= 0 && index < count) result[10 + index] = 1.0;
  }
  // 说明：min_inliers / min_inlier_ratio 的 success 判定由 Kotlin 侧统一完成
  // （`FunshotPanoramaNative.nativeEstimate`），本层只回传内点数 + 矩阵 + 逐点标记，
  // 避免两条路径出现两套 success 口径。
  (void)min_inliers;
  (void)min_inlier_ratio;
  return result;
}

}  // namespace funshot
