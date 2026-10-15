package com.hinnka.mycamera.lut

/**
 * 可叠加胶片质感层的 GLSL（display-referred sRGB 域后处理单 pass）。
 *
 * 原则 A「好看 + 清晰」落地：
 *  - 颗粒：程序化高清纹元（每像素 hash），按亮度 bell-curve 调制——
 *    luma=0.5 最强、纯黑(0)/纯白(1) 为零；不是密度域模糊噪点，不糊细节。
 *  - 暗角 / 漏光 / 柔光：默认参数薄，仅轻量叠色，不做低通软化。
 *  - 锐化：末端 3x3 unsharp，可选开启，抵消叠加层的轻微柔化。
 *
 * 单 pass 全屏片元着色，避免额外 FBO/模糊链，保持轻量（原则3）。
 */
internal object FilmTextureShaders {

    const val UNIFORMS = """
        uniform sampler2D uInputTexture;
        uniform vec2  uResolution;
        uniform float uFrameSeed;
        uniform float uGrainAmount;
        uniform float uGrainSize;
        uniform float uVignette;
        uniform float uLightLeak;
        uniform float uLeakWarm;     // 0=暖橙, 1=青蓝
        uniform float uSoftGlow;
        uniform float uSharpen;
    """

    val MAIN = """
        $UNIFORMS

        // 高分辨纹元 hash：基于像素坐标的确定性随机，细而不糊。
        float hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float luma(vec3 c) {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }

        void main() {
            vec2 uv = vTexCoord;
            vec3 base = texture(uInputTexture, uv).rgb;

            // ---- 末端轻量锐化（unsharp 3x3）----
            if (uSharpen > 0.001) {
                vec2 t = 1.0 / uResolution;
                vec3 blur =
                    texture(uInputTexture, uv + vec2( t.x, 0.0)).rgb +
                    texture(uInputTexture, uv + vec2(-t.x, 0.0)).rgb +
                    texture(uInputTexture, uv + vec2(0.0,  t.y)).rgb +
                    texture(uInputTexture, uv + vec2(0.0, -t.y)).rgb;
                blur *= 0.25;
                base = clamp(base + (base - blur) * uSharpen, 0.0, 1.0);
            }

            // ---- 暗角 vignette：以画面中心径向压暗，薄 ----
            if (uVignette > 0.001) {
                vec2 d = uv - 0.5;
                d.x *= uResolution.x / max(uResolution.y, 1.0); // 按纵横比校正
                float v = smoothstep(0.35, 0.85, length(d));
                base *= 1.0 - uVignette * v;
            }

            // ---- 漏光 light leak：左上暖色/右下可选冷色辉光，薄 ----
            if (uLightLeak > 0.001) {
                float glowTL = exp(-distance(uv, vec2(0.0, 1.0)) * 3.2);
                float glowBR = exp(-distance(uv, vec2(1.0, 0.0)) * 4.0);
                vec3 warm = vec3(1.0, 0.55, 0.25);   // 暖橙
                vec3 cool = vec3(0.25, 0.6, 1.0);     // 青蓝
                vec3 leakColor = mix(warm, cool, clamp(uLeakWarm, 0.0, 1.0));
                base += leakColor * uLightLeak * (glowTL * 0.8 + glowBR * 0.3);
            }

            // ---- 柔光 halation：高光处轻量染色溢光，薄（不模糊整张）----
            if (uSoftGlow > 0.001) {
                float hi = smoothstep(0.7, 1.0, luma(base));
                base += base * hi * uSoftGlow * 0.35;
            }

            // ---- 亮度调制颗粒：中灰最强，纯黑/纯白为零 ----
            if (uGrainAmount > 0.001) {
                float L = luma(base);
                // bell：L=0.5 时 1，L=0 或 L=1 时 0
                float bell = 1.0 - (2.0 * L - 1.0) * (2.0 * L - 1.0);
                vec2 seed = vec2(
                    fract(uFrameSeed * 0.6180339),
                    fract(uFrameSeed * 0.7548776)
                ) * 4096.0;
                vec2 px = gl_FragCoord.xy / max(uGrainSize, 0.25) + seed;
                float n = hash12(px) - 0.5;             // [-0.5, 0.5]
                float amount = uGrainAmount * bell;
                // 颗粒按亮度方向微加色，保持纹理清晰而非整块加灰
                base += vec3(n) * amount * 0.14;
            }

            fragColor = vec4(clamp(base, 0.0, 1.0), texture(uInputTexture, uv).a);
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision highp float;
        precision highp int;
        in vec2 vTexCoord;
        out vec4 fragColor;
        $MAIN
    """.trimIndent()
}
