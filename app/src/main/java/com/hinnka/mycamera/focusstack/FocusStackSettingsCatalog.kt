package com.hinnka.mycamera.focusstack

import com.hinnka.mycamera.uisettings.ProSettingBuilder
import com.hinnka.mycamera.uisettings.ProSettingSpec

/**
 * 微距焦点堆叠的 Pro 设置目录（挂到统一设置页）。
 *
 * **模块 id 说明**：设置项 id 前缀统一为 `macrostack.`，与 `ProSettingsTaxonomy` 中
 * 预登记的模块 `macrostack`（"微距焦点堆叠"）一致 —— 该模块原本是 `PENDING` 占位，
 * 本模块落地后由补丁把它改为 `AVAILABLE` 并补齐分栏 / 读写位置映射（见
 * `delivery/patches/focusstack_patch.md` 的 TAXONOMY 节）。
 *
 * **单一事实来源**：默认值与范围全部引用 [FocusStackConfig] 的公开常量，本文件不重复声明业务常量；
 * 键名引用 [FocusStackConfig] 的 `KEY_*`（直接落既有 `user_preferences` DataStore）。
 */
object FocusStackSettingsCatalog {

    val entries: List<ProSettingSpec> = buildList {

        add(
            ProSettingBuilder.sw(
                "macrostack.enabled", FocusStackConfig.KEY_ENABLED,
                "微距焦点堆叠", "总开关；关闭时相机不进入焦点扫描流程", true, "OpenCamera",
            ),
        )
        add(
            ProSettingBuilder.it(
                "macrostack.frameCount", FocusStackConfig.KEY_FRAME_COUNT,
                "堆叠帧数", "参与融合的帧数；不设算法上限（内存不够自动降工作分辨率）",
                FocusStackConfig.DEFAULT_FRAME_COUNT,
                FocusStackConfig.FRAME_MIN, FocusStackConfig.FRAME_RECOMMENDED_MAX, 1, "帧", "OpenCamera",
            ),
        )
        add(
            ProSettingBuilder.it(
                "macrostack.stepCount", FocusStackConfig.KEY_STEP_COUNT,
                "对焦步进数", "对焦距离的步进段数（默认与帧数一致）",
                FocusStackConfig.DEFAULT_STEP_COUNT,
                FocusStackConfig.FRAME_MIN, FocusStackConfig.FRAME_RECOMMENDED_MAX, 1, "步", "OpenCamera",
            ),
        )
        add(
            ProSettingBuilder.sw(
                "macrostack.autoNearToFar", FocusStackConfig.KEY_AUTO_NEAR_TO_FAR,
                "自动近到远", "按镜头最近对焦距离自动铺满整段焦点范围", true, "OpenCamera",
            ),
        )
        add(
            ProSettingBuilder.fl(
                "macrostack.focusStartDiopters", FocusStackConfig.KEY_FOCUS_START_DIOPTERS,
                "远端起点", "屈光度；0 = 无穷远（1/值 = 米）", 0f,
                FocusStackConfig.FOCUS_DIOPTERS_MIN, FocusStackConfig.FOCUS_DIOPTERS_MAX, 2, "D", "OpenCamera",
            ),
        )
        add(
            ProSettingBuilder.fl(
                "macrostack.focusEndDiopters", FocusStackConfig.KEY_FOCUS_END_DIOPTERS,
                "近端终点", "负值 = 自动（取镜头最近对焦距离）", FocusStackConfig.AUTO_FOCUS_DIOPTERS,
                FocusStackConfig.AUTO_FOCUS_DIOPTERS, FocusStackConfig.FOCUS_DIOPTERS_MAX, 2, "D", "OpenCamera",
            ),
        )
        add(
            ProSettingBuilder.dd(
                "macrostack.clarityMetric", FocusStackConfig.KEY_CLARITY_METRIC,
                "清晰度度量", "拉普拉斯方差对细节更敏感；梯度能量更抗噪",
                ClarityMetric.LAPLACIAN_VARIANCE.name, ClarityMetric.entries.map { it.name }, "OpenCamera",
            ),
        )
        add(
            ProSettingBuilder.it(
                "macrostack.clarityRadius", FocusStackConfig.KEY_CLARITY_RADIUS,
                "清晰度半径", "清晰度统计邻域半径；越大越抗噪但越糊",
                FocusStackConfig.DEFAULT_CLARITY_RADIUS,
                FocusStackConfig.CLARITY_RADIUS_MIN, FocusStackConfig.CLARITY_RADIUS_MAX, 1, "px", "OpenCamera",
            ),
        )
        add(
            ProSettingBuilder.it(
                "macrostack.maskSmoothRadius", FocusStackConfig.KEY_MASK_SMOOTH_RADIUS,
                "掩模平滑半径", "抑制掩模噪点与融合接缝；0 = 不平滑",
                FocusStackConfig.DEFAULT_MASK_SMOOTH_RADIUS,
                FocusStackConfig.MASK_SMOOTH_RADIUS_MIN, FocusStackConfig.MASK_SMOOTH_RADIUS_MAX, 1, "px",
                "PetteriAimonen/focus-stack",
            ),
        )
        add(
            ProSettingBuilder.it(
                "macrostack.maskConsistency", FocusStackConfig.KEY_MASK_CONSISTENCY,
                "邻域一致性", "抑制孤立像素抢焦点：0 关 / 1 / 2",
                FocusStackConfig.DEFAULT_MASK_CONSISTENCY,
                FocusStackConfig.MASK_CONSISTENCY_MIN, FocusStackConfig.MASK_CONSISTENCY_MAX, 1, "",
                "PetteriAimonen/focus-stack",
            ),
        )
        add(
            ProSettingBuilder.fl(
                "macrostack.fusionStrength", FocusStackConfig.KEY_FUSION_STRENGTH,
                "融合强度", "1 = 完全用融合结果；<1 时与最清晰单帧混合", FocusStackConfig.DEFAULT_FUSION_STRENGTH,
                FocusStackConfig.STRENGTH_MIN, FocusStackConfig.STRENGTH_MAX, 2, "",
                "PetteriAimonen/focus-stack",
            ),
        )
        add(
            ProSettingBuilder.fl(
                "macrostack.clarityExponent", FocusStackConfig.KEY_CLARITY_EXPONENT,
                "掩模权重指数", "越大越「赢家通吃」，越小越接近均值融合",
                FocusStackConfig.DEFAULT_CLARITY_EXPONENT,
                FocusStackConfig.CLARITY_EXPONENT_MIN, FocusStackConfig.CLARITY_EXPONENT_MAX, 2, "",
                "PetteriAimonen/focus-stack",
            ),
        )
        add(
            ProSettingBuilder.sw(
                "macrostack.alignEnabled", FocusStackConfig.KEY_ALIGN_ENABLED,
                "平移对齐", "机身固定时关闭（默认）；手持/有风时开启",
                FocusStackConfig.DEFAULT_ALIGN_ENABLED, "PetteriAimonen/focus-stack",
            ),
        )
        add(
            ProSettingBuilder.it(
                "macrostack.alignMaxShiftPx", FocusStackConfig.KEY_ALIGN_MAX_SHIFT_PX,
                "对齐最大位移", "平移搜索半径（像素）",
                FocusStackConfig.DEFAULT_ALIGN_MAX_SHIFT_PX,
                FocusStackConfig.ALIGN_MAX_SHIFT_MIN, FocusStackConfig.ALIGN_MAX_SHIFT_MAX, 1, "px",
                "PetteriAimonen/focus-stack",
            ),
        )
        add(
            ProSettingBuilder.it(
                "macrostack.alignMaxLongEdge", FocusStackConfig.KEY_ALIGN_MAX_LONG_EDGE,
                "对齐工作长边", "对齐使用的工作分辨率长边；0 = 全分辨率",
                FocusStackConfig.DEFAULT_ALIGN_MAX_LONG_EDGE,
                FocusStackConfig.ALIGN_MAX_LONG_EDGE_MIN, FocusStackConfig.ALIGN_MAX_LONG_EDGE_MAX, 64, "px",
                "PetteriAimonen/focus-stack",
            ),
        )
        add(
            ProSettingBuilder.dd(
                "macrostack.workingResolution", FocusStackConfig.KEY_WORKING_RESOLUTION,
                "工作分辨率", "清晰度/掩模计算分辨率；内存不足会自动再降档",
                FocusWorkingResolution.FULL.name, FocusWorkingResolution.entries.map { it.name },
                "PetteriAimonen/focus-stack",
            ),
        )
        add(
            ProSettingBuilder.sw(
                "macrostack.outputFullResolution", FocusStackConfig.KEY_OUTPUT_FULL_RESOLUTION,
                "输出全分辨率", "关闭则以工作分辨率输出（更快、更省内存）",
                true, "OpenCamera",
            ),
        )
        add(
            ProSettingBuilder.it(
                "macrostack.jpegQuality", FocusStackConfig.KEY_JPEG_QUALITY,
                "输出 JPEG 质量", "60..100",
                FocusStackConfig.DEFAULT_JPEG_QUALITY,
                FocusStackConfig.JPEG_QUALITY_MIN, FocusStackConfig.JPEG_QUALITY_MAX, 1, "",
                "OpenCamera",
            ),
        )
    }
}
