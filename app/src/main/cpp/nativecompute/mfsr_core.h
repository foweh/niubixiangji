// Copyright (c) 2026 PhotonCamera contributors.
// SPDX-License-Identifier: MIT
//
// 多帧超分（MFSR）计算层核心 —— C++/NDK 实现。
//
// 与 `com.hinnka.mycamera.multiframesr` 的纯 Kotlin 实现**镜像**：
//   * 相位相关亚像素对齐  ↔ MfsrPhaseCorrelation.estimate / MfsrFft.transform2D
//   * 移位叠加投影        ↔ MfsrHrAccumulator.add / toPlane / fillHoles
//   * 迭代反投影（IBP）   ↔ MfsrIterativeBackProjection.refine（逐帧正向仿真 + 反投影 + 更新步）
//   * 精修（双边 + 反锐化）↔ MfsrRefiner.bilateral / unsharp / MfsrPlane.gaussianBlur
//   * 逐点 clamp          ↔ MfsrPlane.clampInPlace
//
// 约定（PATTERN_SPEC §10.6）：
//   1. **本层不做任何内存/调度决策**：工作分辨率、HR 网格尺寸、迭代轮数、帧批大小、
//      空洞扩散轮数全部由 Kotlin 侧决定并作为参数传入；frame 停放（内存 or 落盘）
//      仍由 Kotlin 的 MfsrFrameStore 负责，native 只处理"已经被交给它的缓冲"；
//   2. 所有入口都做入参校验，**校验失败返回 false / NaN 之外的哨兵值**，绝不抛异常、
//      绝不越界写；Kotlin 侧收到失败即回退纯 Kotlin 同语义实现；
//   3. 逐像素循环里保持与 Kotlin 相同的**运算顺序**（含 float 加法顺序），
//      配合 CMakeLists 补丁中的 `-fno-fast-math;-ffp-contract=off` 保证同值。
//
// 无第三方依赖（自研原因见 delivery/changelog_drafts/nativecompute.md）：
// OpenCV 的 phaseCorrelate/CLAHE/bilateral 是 Apache-2.0 可参考（Star≈91191），
// 但其 arm64 .so 体积（单 op 依赖整库 ≈ 20MB+）超出本工程体积预算，故只参考算法思路、
// 代码为自写（FFT 为最小 radix-2 实现，与 Kotlin 侧同一份算法）。

#ifndef PHOTON_CAMERA_NATIVECOMPUTE_MFSR_CORE_H_
#define PHOTON_CAMERA_NATIVECOMPUTE_MFSR_CORE_H_

#include <cstdint>

namespace photon {
namespace nativecompute {
namespace mfsr {

/**
 * 相位相关亚像素平移估计。
 *
 * 流程与 Kotlin 一致：无纹理退化判定 → Hann 窗 → 零填充到 2 的幂 → 2D FFT →
 * 归一化互功率谱（`F_tgt · conj(F_ref)`）→ 逆 FFT → 窗口内峰值搜索（wrap 解缠）
 * → 峰值抛物线亚像素拟合 → 置信度（峰噪比软映射）→ 限量到 ±max_shift_px。
 *
 * @param ref/tgt       工作分辨率亮度平面（行主序，长度 = width*height）
 * @param width/height  平面尺寸
 * @param max_shift_px  允许的最大平移（像素），内部 clamp 到 [1, 4096]
 * @param align_max_side 相位相关内部工作长边上限，内部 clamp 到 [64, 1024]
 * @param out_dx/out_dy/out_confidence 非空时写回结果
 * @return true = 估计成功；false = 入参非法 / 退化（无纹理、尺寸不合法），调用方回退
 */
bool PhaseCorrelate(const float* ref,
                    const float* tgt,
                    int32_t width,
                    int32_t height,
                    int32_t max_shift_px,
                    int32_t align_max_side,
                    float* out_dx,
                    float* out_dy,
                    float* out_confidence);

/**
 * 移位叠加：把一帧按亚像素平移投到 HR 网格上加权累加（`sum`/`weight` 就地更新）。
 *
 * 与 Kotlin `MfsrHrAccumulator.add` 同式：HR 像素中心 `u(X) = (X + 0.5)/scale - 0.5`，
 * 采样该帧的 `u + shift`；越界样本（bilinear = NaN）不计入 weight。
 *
 * @param out_coverage 非空时写回覆盖率 = 有效写入像素数 / HR 像素数（0..1）
 * @return true = 成功；false = 入参非法（调用方回退）
 */
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
                        float* out_coverage);

/**
 * 移位叠加归一化 + 空洞扩散（等价 Kotlin `MfsrHrAccumulator.toPlane` + `fillHoles`）。
 *
 * 语义：`weight > 0` 处 `out = sum / weight`；空洞按 4 邻域逐轮扩散（每轮只吸收
 * 本轮开始前已 known 的邻居），最多 `hole_passes` 轮；仍未填补的用已填充像素均值兜底；
 * 完全无覆盖（knownCount == 0）时输出全 0。
 *
 * @param hole_passes 扩散轮数，内部 clamp 到 [0, 64]
 * @return true = 成功；false = 入参非法（调用方回退）
 */
bool ResolveShiftAdd(const float* sum,
                     const float* weight,
                     float* out,
                     int32_t hr_width,
                     int32_t hr_height,
                     int32_t hole_passes);

/**
 * IBP 单帧：HR → LR 正向仿真 + 残差反投影累加（`err` / `wsum` 就地累加）。
 *
 * 与 Kotlin `MfsrIterativeBackProjection.refine` 内层同式（含 `hx/hy` 越界 continue 的
 * 判定顺序与双线性权重分摊顺序），因此可在同一轮里逐帧调用并保持累加顺序。
 *
 * @param out_stats 非空且长度 >= 2 时写回 `[0] = Σ|e|（double）、[1] = 有效样本数`
 * @return true = 成功；false = 入参非法（调用方回退）
 */
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
                        double* out_stats);

/**
 * IBP 更新步：`hr[k] += gain · err[k]/wsum[k]`（wsum > 0 时），并做
 * boundedness 截断（非有限值保留原值）。
 *
 * @param gain 步长 λ（调用方已 clamp 到 [0.05, 1.2]）
 * @return true = 成功；false = 入参非法（调用方回退）
 */
bool IbpApplyStep(float* hr,
                  const float* err,
                  const float* wsum,
                  int32_t pixel_count,
                  float gain);

/**
 * 双边滤波（单通道亮度平面，等价 Kotlin `MfsrRefiner.bilateral`）：
 * 空间高斯（一维可分离）× 值域高斯（512 档 LUT），输出与原始值按 `mix` 混合。
 *
 * @param src/dst 长度均须 >= width*height（允许同缓冲，但 Kotlin 路径用独立 dst）
 * @return true = 成功；false = 入参非法（调用方回退）
 */
bool BilateralLuma(const float* src,
                   float* dst,
                   int32_t width,
                   int32_t height,
                   int32_t radius,
                   float sigma_range,
                   float mix);

/**
 * 反锐化掩模（等价 Kotlin `MfsrRefiner.unsharp` + `MfsrPlane.gaussianBlur`）：
 * `out = v + amount·(v - blur(v))`，结果 clamp 到 [0, 255]，非有限值保留 v。
 *
 * @return true = 成功；false = 入参非法（调用方回退）
 */
bool UnsharpLuma(const float* src,
                 float* dst,
                 int32_t width,
                 int32_t height,
                 float amount,
                 float sigma);

/**
 * 逐点截断（等价 Kotlin `MfsrPlane.clampInPlace`）：非有限值回落 lo。
 *
 * @return true = 成功；false = 入参非法（调用方回退）
 */
bool ClampPlane(float* data, int32_t count, float lo, float hi);

}  // namespace mfsr
}  // namespace nativecompute
}  // namespace photon

#endif  // PHOTON_CAMERA_NATIVECOMPUTE_MFSR_CORE_H_
