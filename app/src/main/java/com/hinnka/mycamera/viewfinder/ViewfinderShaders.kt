package com.hinnka.mycamera.viewfinder

/**
 * 取景器叠加层的 GLSL 着色器源码。
 *
 * 放在本模块自有文件里（而非改 `lut/Shaders.kt`），使补丁只需在 `LutRenderer` 里加
 * 「一个字段 + 一次调用」两处最小改动（PATTERN_SPEC §9 最小改动原则）。
 *
 * 顶点着色器刻意与工程既有 `Shaders.SIMPLE_VERTEX_SHADER` 保持**同样的属性名与布局**
 * （`aPosition` vec4 / `aTexCoord` vec2，两个独立 buffer，各 2 个 float），
 * 因此可以直接复用 `LutRenderer` 既有的 `vertexBufferId / texCoordBufferId / indexBufferId`。
 */
object ViewfinderShaders {

    /** 与 `Shaders.SIMPLE_VERTEX_SHADER` 属性布局一致的顶点着色器。 */
    val OVERLAY_VERTEX_SHADER = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    /**
     * 斑马纹过曝提示：亮度（或 RGB 最大值）超过阈值处叠加斜条纹。
     *
     * uniforms：
     * - `uInputTexture`  输入纹理
     * - `uLumaThreshold` 归一化阈值 0..1（由 `ZebraConfig.normalizedThreshold` 给出）
     * - `uResolution`    预览分辨率（像素），用于把条纹宽度换算到像素域
     * - `uStripeWidthPx` 条纹宽度（像素）
     * - `uStripeDuty`    占空比 0..1
     * - `uStripeAngleDeg`条纹角度（度）
     * - `uStripeColor`   条纹色
     * - `uStripeAlpha`   条纹不透明度
     * - `uUseMaxRgb`     1 = 按 RGB 最大值判定，0 = 按 Rec.709 亮度判定
     */
    val ZEBRA_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform sampler2D uInputTexture;
        uniform float uLumaThreshold;
        uniform vec2 uResolution;
        uniform float uStripeWidthPx;
        uniform float uStripeDuty;
        uniform float uStripeAngleDeg;
        uniform vec3 uStripeColor;
        uniform float uStripeAlpha;
        uniform float uUseMaxRgb;

        const float PI = 3.14159265358979;

        void main() {
            vec4 color = texture(uInputTexture, vTexCoord);
            float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            float maxRgb = max(color.r, max(color.g, color.b));
            float metric = mix(luma, maxRgb, clamp(uUseMaxRgb, 0.0, 1.0));

            if (metric < uLumaThreshold) {
                fragColor = color;
                return;
            }

            // 条纹相位在「物理像素」域计算，分辨率变化时观感一致。
            vec2 pixel = vTexCoord * max(uResolution, vec2(1.0));
            float angleRad = uStripeAngleDeg * PI / 180.0;
            vec2 direction = vec2(cos(angleRad), sin(angleRad));
            float coordinate = dot(pixel, direction);
            float period = max(uStripeWidthPx, 1.0) * 2.0;
            float phase = fract(coordinate / period);
            float stripe = 1.0 - step(clamp(uStripeDuty, 0.0, 1.0), phase);

            float alpha = stripe * clamp(uStripeAlpha, 0.0, 1.0);
            fragColor = vec4(mix(color.rgb, uStripeColor, alpha), color.a);
        }
    """.trimIndent()

    /**
     * 峰值对焦**增强**着色器（可选升级路径）。
     *
     * 与工程既有 `Shaders.FRAGMENT_SHADER_FOCUS_PEAKING` 的差异仅三点，保持同一视觉语言：
     * 1. 亮度权重沿用既有的 Rec.601（0.299/0.587/0.114），保证默认观感不回归；
     * 2. `uStepScale` 放大采样步长，可把断续的细边缘连成更醒目的"厚"峰值线；
     * 3. `uMixStrength` 开放混合强度（既有实现硬编码 0.9）。
     *
     * uniforms：`uInputTexture` / `uTexelSize` / `uThreshold` / `uPeakColor`
     *          / `uStepScale` / `uMixStrength`
     */
    val PEAKING_ENHANCED_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform sampler2D uInputTexture;
        uniform vec2 uTexelSize;
        uniform float uThreshold;
        uniform vec3 uPeakColor;
        uniform float uStepScale;
        uniform float uMixStrength;

        float lumaAt(vec2 uv) {
            return dot(texture(uInputTexture, uv).rgb, vec3(0.299, 0.587, 0.114));
        }

        void main() {
            vec4 color = texture(uInputTexture, vTexCoord);
            vec2 step = uTexelSize * max(uStepScale, 1.0);

            float l00 = lumaAt(vTexCoord + vec2(-step.x, -step.y));
            float l10 = lumaAt(vTexCoord + vec2(0.0, -step.y));
            float l20 = lumaAt(vTexCoord + vec2(step.x, -step.y));
            float l01 = lumaAt(vTexCoord + vec2(-step.x, 0.0));
            float l21 = lumaAt(vTexCoord + vec2(step.x, 0.0));
            float l02 = lumaAt(vTexCoord + vec2(-step.x, step.y));
            float l12 = lumaAt(vTexCoord + vec2(0.0, step.y));
            float l22 = lumaAt(vTexCoord + vec2(step.x, step.y));

            float gx = l00 + 2.0 * l01 + l02 - l20 - 2.0 * l21 - l22;
            float gy = l00 + 2.0 * l10 + l20 - l02 - 2.0 * l12 - l22;
            float edge = sqrt(gx * gx + gy * gy);

            float high = min(uThreshold * 1.5, 1.0);
            float peakFactor = smoothstep(uThreshold, max(high, uThreshold + 0.001), edge);
            fragColor = vec4(
                mix(color.rgb, uPeakColor, peakFactor * clamp(uMixStrength, 0.0, 1.0)),
                color.a
            );
        }
    """.trimIndent()
}
