package com.hinnka.mycamera.camera

/**
 * 计算摄影场景（跨代理契约符号，名称勿改）。
 *
 * - STANDARD：标准拍照/录像，不启用额外多帧/堆栈路径。
 * - HDR：包围曝光多帧（复用 HdrBracketConfig 路径，优先 RAW 多帧）。
 * - NIGHT：夜景长曝光连拍（MultiFrameExposurePlanner.planLongExposure 规划，多帧堆栈降噪）。
 * - ASTRO：天文/星空堆栈（多帧短曝光对齐堆栈；星轨变体走长叠加工单）。
 * - PORTRAIT：人像散景（接入 OglBokehProcessor 散景管线）。
 * - STACK：手机端 PS 式堆栈（均值/中值/最大值/最小值，降噪/去移动物体/星轨叠加）。
 *
 * 所有模式在控制器内做能力检测，能力不足时自动回退 STANDARD 并写入
 * [CameraState.computationalSceneNote] 供 UI 观测。
 */
enum class ComputationalScene {
    STANDARD,
    HDR,
    NIGHT,
    ASTRO,
    PORTRAIT,
    STACK,
}

/**
 * PS 式堆栈合成类型（跨代理契约符号，名称勿改）。
 * MEAN   ：逐帧平均 → 降噪、提升动态范围。
 * MEDIAN ：逐帧中值 → 去除移动物体/路人/星空去卫星轨迹。
 * MAX    ：逐帧取最大亮度 → 星轨/光迹叠加、高亮累积。
 * MIN    ：逐帧取最小亮度 → 暗部异常/噪点去除。
 */
enum class PsStackType {
    MEAN,
    MEDIAN,
    MAX,
    MIN,
}
