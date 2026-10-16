package com.hinnka.mycamera.viewfinder

/**
 * 贴纸绘制形态。
 *
 * 内置贴纸全部为**纯矢量程序化绘制**（Compose Canvas Path，零二进制素材、零许可风险、
 * 任意分辨率不模糊）；[StickerPackResolver] 命中外部素材时改用位图形态。
 */
enum class FaceStickerVariant {
    CAT_EARS,
    BUNNY_EARS,
    HEART_CROWN,
    ROUND_GLASSES,
    FLOWER_HALO,
    /** 外部下载素材的位图形态。 */
    EXTERNAL_BITMAP,
}

/**
 * 一枚贴纸的元数据。
 *
 * @param id 稳定 id（配置键/持久化用）
 * @param displayName 面板文案
 * @param defaultAnchor 默认锚定部位
 * @param defaultScale 形态相关的基础缩放（与用户 [StickerConfig.scale] 相乘）
 * @param defaultOffsetX / defaultOffsetY 相对**人脸宽度**的归一化微调
 * @param widthToFaceRatio 绘制宽度 / 人脸宽度
 * @param variant 绘制形态
 */
data class FaceStickerDefinition(
    val id: String,
    val displayName: String,
    val defaultAnchor: StickerAnchorMode,
    val defaultScale: Float,
    val defaultOffsetX: Float,
    val defaultOffsetY: Float,
    val widthToFaceRatio: Float,
    val variant: FaceStickerVariant,
)

/**
 * 内置贴纸目录（5 枚）。
 *
 * 许可证说明：全部为本模块自绘矢量图形，无第三方素材依赖，不涉及素材授权问题。
 * 需要更多素材时可走 [StickerPackConfig] 的可配置外部素材包（默认关闭）。
 */
object FaceStickerCatalog {

    const val DEFAULT_STICKER_ID = "cat_ears"

    val BUILT_IN: List<FaceStickerDefinition> = listOf(
        FaceStickerDefinition(
            id = DEFAULT_STICKER_ID,
            displayName = "猫耳",
            defaultAnchor = StickerAnchorMode.FOREHEAD,
            defaultScale = 1f,
            defaultOffsetX = 0f,
            defaultOffsetY = -0.22f,
            widthToFaceRatio = 1.35f,
            variant = FaceStickerVariant.CAT_EARS,
        ),
        FaceStickerDefinition(
            id = "bunny_ears",
            displayName = "兔耳",
            defaultAnchor = StickerAnchorMode.FOREHEAD,
            defaultScale = 1f,
            defaultOffsetX = 0f,
            defaultOffsetY = -0.44f,
            widthToFaceRatio = 1.05f,
            variant = FaceStickerVariant.BUNNY_EARS,
        ),
        FaceStickerDefinition(
            id = "heart_crown",
            displayName = "爱心皇冠",
            defaultAnchor = StickerAnchorMode.FOREHEAD,
            defaultScale = 1f,
            defaultOffsetX = 0f,
            defaultOffsetY = -0.26f,
            widthToFaceRatio = 1.25f,
            variant = FaceStickerVariant.HEART_CROWN,
        ),
        FaceStickerDefinition(
            id = "round_glasses",
            displayName = "圆框墨镜",
            defaultAnchor = StickerAnchorMode.NOSE,
            defaultScale = 1f,
            defaultOffsetX = 0f,
            defaultOffsetY = -0.04f,
            widthToFaceRatio = 1.15f,
            variant = FaceStickerVariant.ROUND_GLASSES,
        ),
        FaceStickerDefinition(
            id = "flower_halo",
            displayName = "花环",
            defaultAnchor = StickerAnchorMode.FOREHEAD,
            defaultScale = 1f,
            defaultOffsetX = 0f,
            defaultOffsetY = -0.18f,
            widthToFaceRatio = 1.5f,
            variant = FaceStickerVariant.FLOWER_HALO,
        ),
    )

    /** 按 id 查找；未知 id 回落内置默认贴纸（绝不返回 null / 不抛异常）。 */
    fun find(id: String?): FaceStickerDefinition {
        if (id.isNullOrBlank()) return BUILT_IN.first()
        return BUILT_IN.firstOrNull { it.id == id } ?: BUILT_IN.first()
    }

    /** 是否为本模块注册的内置 id。 */
    fun isBuiltIn(id: String?): Boolean = id != null && BUILT_IN.any { it.id == id }
}
