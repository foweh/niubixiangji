package com.hinnka.mycamera.superres

/** 模块最终要执行的动作（UI/管线据此分派）。 */
enum class SuperResAction {
    /** 跑 AI 超分（tiled 推理）。 */
    RUN_AI_SUPER_RES,

    /** 跑风格迁移。 */
    RUN_STYLE_TRANSFER,

    /** 跑图像修复。 */
    RUN_INPAINTING,

    /** scale=1 且只需保真：直接原图（不做任何 AI 计算）。 */
    PASS_THROUGH_NO_UPSCALE,

    /** 模型未安装 → 入口灰化，提示下载；结果回退原图。 */
    INSTALL_MODEL_REQUIRED,

    /** 用户在设置里关闭了该功能。 */
    DISABLED_BY_CONFIG,

    /** 推理运行时不可用（ONNX Runtime 未加载成功 / ABI 不支持）→ 回退原图。 */
    UNAVAILABLE_RUNTIME,

    /** 输入无效（空图 / 尺寸非法）→ 回退原图。 */
    INVALID_INPUT,
}

/**
 * 决策结果。
 *
 * @param modelId 选中的模型 id（仅 RUN_* 动作非空）。
 * @param degradedConfig 因内存/工作分辨率压力而降级后的配置（无需降级时为 null）。
 */
data class SuperResDecision(
    val action: SuperResAction,
    val modelId: String? = null,
    val reason: String = "",
    val degradedConfig: SuperResConfig? = null,
) {
    val runsInference: Boolean
        get() = action == SuperResAction.RUN_AI_SUPER_RES ||
            action == SuperResAction.RUN_STYLE_TRANSFER ||
            action == SuperResAction.RUN_INPAINTING

    /** 该决策是否应把结果回退为原图（能力不足/未下载/关闭/异常）。 */
    val fallsBackToOriginal: Boolean
        get() = !runsInference && action != SuperResAction.PASS_THROUGH_NO_UPSCALE
}

/**
 * 纯决策层：给定「配置 + 已安装模型 + 运行时能力 + 输入尺寸」，判断能否推理、
 * 用哪个模型、是否需要降级，以及**不能推理时如何优雅回退**。
 *
 * 这是「能力不足/模型未下载时入口灰化或回退原图」的唯一判定入口（PATTERN_SPEC 第 1.7 节），
 * 不依赖 Android，可 JVM 单测覆盖全部正常/边界/异常分支。
 */
object SuperResAvailability {

    /** 超分决策。 */
    fun decideSuperRes(
        config: SuperResConfig,
        installedModelIds: Set<String>,
        runtimeAvailable: Boolean,
        inputWidth: Int,
        inputHeight: Int,
        preferredModelId: String? = null,
    ): SuperResDecision = decide(
        kind = SuperResModelKind.SUPER_RESOLUTION,
        config = config,
        enabled = config.enabled,
        installedModelIds = installedModelIds,
        runtimeAvailable = runtimeAvailable,
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        scale = config.scale,
        preferredModelId = preferredModelId,
    )

    /** 风格迁移决策（倍率固定 1，不做放大）。 */
    fun decideStyleTransfer(
        config: SuperResConfig,
        installedModelIds: Set<String>,
        runtimeAvailable: Boolean,
        inputWidth: Int,
        inputHeight: Int,
    ): SuperResDecision = decide(
        kind = SuperResModelKind.STYLE_TRANSFER,
        config = config,
        enabled = config.enabled && config.enableStyleTransfer,
        installedModelIds = installedModelIds,
        runtimeAvailable = runtimeAvailable,
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        scale = 1,
        preferredModelId = config.styleModelId,
    )

    /** 图像修复决策（倍率固定 1）。 */
    fun decideInpainting(
        config: SuperResConfig,
        installedModelIds: Set<String>,
        runtimeAvailable: Boolean,
        inputWidth: Int,
        inputHeight: Int,
    ): SuperResDecision = decide(
        kind = SuperResModelKind.INPAINTING,
        config = config,
        enabled = config.enabled && config.enableInpainting,
        installedModelIds = installedModelIds,
        runtimeAvailable = runtimeAvailable,
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        scale = 1,
        preferredModelId = null,
    )

    private fun decide(
        kind: SuperResModelKind,
        config: SuperResConfig,
        enabled: Boolean,
        installedModelIds: Set<String>,
        runtimeAvailable: Boolean,
        inputWidth: Int,
        inputHeight: Int,
        scale: Int,
        preferredModelId: String?,
    ): SuperResDecision {
        // 1) 顺序很重要：先判「是否被用户关闭」，再判输入有效性，最后才判能力，
        //    这样关闭状态下的异常输入不会误报为「输入非法」。
        if (!enabled) {
            return SuperResDecision(SuperResAction.DISABLED_BY_CONFIG, reason = "config_disabled")
        }
        if (inputWidth <= 0 || inputHeight <= 0) {
            return SuperResDecision(SuperResAction.INVALID_INPUT, reason = "invalid_input_size")
        }
        // 2) 超分 scale=1 = 不放大；此时无需模型，直接原图（不算回退失败）。
        if (kind == SuperResModelKind.SUPER_RESOLUTION && scale <= 1) {
            return SuperResDecision(
                SuperResAction.PASS_THROUGH_NO_UPSCALE,
                reason = "scale_1_no_upscale",
            )
        }
        if (!runtimeAvailable) {
            return SuperResDecision(SuperResAction.UNAVAILABLE_RUNTIME, reason = "onnx_runtime_unavailable")
        }
        // 3) 模型可拆卸：未安装 → 灰化 + 回退原图，绝不失败。
        val model = SuperResModelCatalog.selectInstalled(
            kind = kind,
            scale = scale,
            installedIds = installedModelIds,
            preferredId = preferredModelId,
        ) ?: return SuperResDecision(
            SuperResAction.INSTALL_MODEL_REQUIRED,
            reason = "model_not_installed:" + SuperResModelCatalog.missingHint(kind, scale),
        )

        // 4) 输入超过工作分辨率上限 → 给出降级配置（降采样后再推理），不是拒绝。
        val clamped = SuperResConfig.clamp(config)
        val pixels = inputWidth.toLong() * inputHeight.toLong()
        val limit = clamped.maxWorkingPixels
        val degrade = limit > 0L && pixels > limit
        val action = when (kind) {
            SuperResModelKind.SUPER_RESOLUTION -> SuperResAction.RUN_AI_SUPER_RES
            SuperResModelKind.STYLE_TRANSFER -> SuperResAction.RUN_STYLE_TRANSFER
            SuperResModelKind.INPAINTING -> SuperResAction.RUN_INPAINTING
        }
        return SuperResDecision(
            action = action,
            modelId = model.id,
            reason = if (degrade) "run_with_working_resolution_cap" else "run",
            degradedConfig = if (degrade) clamped else null,
        )
    }

    /** 把「输入降采样到工作分辨率上限」的实际尺寸算出来（保持长宽比，至少 1px）。 */
    fun workingSizeFor(inputWidth: Int, inputHeight: Int, maxWorkingPixels: Long): Pair<Int, Int> {
        if (inputWidth <= 0 || inputHeight <= 0) return 0 to 0
        if (maxWorkingPixels <= 0L) return inputWidth to inputHeight
        val pixels = inputWidth.toLong() * inputHeight.toLong()
        if (pixels <= maxWorkingPixels) return inputWidth to inputHeight
        val ratio = kotlin.math.sqrt(maxWorkingPixels.toDouble() / pixels.toDouble())
        val w = (inputWidth * ratio).toInt().coerceAtLeast(1)
        val h = (inputHeight * ratio).toInt().coerceAtLeast(1)
        return w to h
    }
}
