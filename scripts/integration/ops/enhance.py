# -*- coding: utf-8 -*-
"""enhance_patch 集成操作：BgJobRunner 编译 MUST-FIX + ENUM(ENHANCE) + H1 拍照后处理管线接线。"""

P = "delivery/patches/enhance_patch.md"
BJR = "app/src/main/java/com/hinnka/mycamera/computational/BgJobRunner.kt"
PP = "app/src/main/java/com/hinnka/mycamera/gallery/PhotoProcessor.kt"

OPS = [
    # MUST-FIX：带接收者函数类型的属性访问不能直接 () 调用（Kotlin 2.3.0 编译错误）
    ("replace", BJR, "            runCatching { job.block() }",
     "            runCatching { job.block.invoke(job) }", 1),

    # ENUM：ENHANCE 条目（插在 GENERIC 之前，GENERIC 保持兜底最后一项）
    ("x_insert_before", P, 29, BJR, "    /** 未分类。 */", 1),

    # H1：拍照后处理管线（PhotoProcessor.processBitmap 汇合点；默认 enabled=false 零回归）
    ("x_insert_after", P, 100, PP, '        result = applyPostEditGeometry(result, metadata, "bitmap")', 1),
    ("x_insert_after", P, 126, PP, "import com.hinnka.mycamera.raw.RawHdrRenderResult", 1),
]
