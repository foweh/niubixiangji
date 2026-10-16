package com.hinnka.mycamera.galleryedit

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 日期戳格式化（纯 Kotlin + java.time，可直接 JVM 单测）。
 *
 * 参考 zhensherlock/watermark-js-plus（585★, MIT）的日期戳能力范围（可配置格式串 + 位置），
 * **排版与绘制为自研**：该库运行在浏览器，Android 侧无法复用其 DOM/Canvas 实现；
 * 本类只做"格式串归一化 + 安全格式化"，与 [GeWatermarkLayout] 组合即完成日期戳。
 *
 * 安全约定（越界不抛异常）：
 * - 空白/超长格式串 → 回落 [GeEditRanges.DATE_PATTERN_DEFAULT]；
 * - 非法格式串（如单个未转义的 'y'/'M' 组合在部分 locale 下无意义）→ 捕获异常并回落默认格式；
 * - 时间戳非法（负值并非 0、溢出）→ 回落当前时间。
 */
object GeDateStampFormat {

    /** 允许出现在格式串中的字母白名单（DateTimeFormatter 的 pattern letters）。 */
    private const val ALLOWED_LETTERS = "yYuUMLdDEHhKkmsSazZXxVvGOQnNpFwWrte"

    /** 预设格式（UI 下拉直接给这几项，也允许用户自定义）。 */
    val PRESETS: List<String> = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm",
        "MM-dd HH:mm",
        "yyyy年MM月dd日 HH:mm",
        "EEE, dd MMM yyyy HH:mm:ss Z",
    )

    /**
     * 归一化格式串：空白/超长/含非法字符 → 默认格式。
     *
     * @param pattern 用户输入格式串。
     * @param fallback 回落值，默认 [GeEditRanges.DATE_PATTERN_DEFAULT]。
     */
    fun normalizePattern(
        pattern: String?,
        fallback: String = GeEditRanges.DATE_PATTERN_DEFAULT,
    ): String {
        val value = pattern?.trim().orEmpty()
        if (value.isEmpty()) return fallback
        if (value.length > GeEditRanges.DATE_PATTERN_MAX_LENGTH) return fallback
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) return fallback
        if (!isValidPattern(value)) return fallback
        return value
    }

    /** 是否是可被 [DateTimeFormatter] 接受的格式串（会实际编译一次校验）。 */
    fun isValidPattern(pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        if (pattern.any { it.isLetter() && it !in ALLOWED_LETTERS }) return false
        return try {
            DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: DateTimeParseException) {
            false
        }
    }

    /**
     * 格式化时间戳。
     *
     * @param epochMillis 毫秒时间戳；非法（不是有限范围）时回落当前时间。
     * @param pattern 格式串（内部会先归一化）。
     * @param zoneId 时区。
     * @param locale 语言（影响 MMM/EEE 文案）。
     */
    fun format(
        epochMillis: Long,
        pattern: String = GeEditRanges.DATE_PATTERN_DEFAULT,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val safePattern = normalizePattern(pattern)
        val safeMillis = sanitizeMillis(epochMillis)
        return try {
            DateTimeFormatter.ofPattern(safePattern, locale)
                .withZone(zoneId)
                .format(Instant.ofEpochMilli(safeMillis))
        } catch (_: IllegalArgumentException) {
            // 极端 locale 下仍失败：用 ISO 兜底，绝不抛给调用方
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
                .withZone(zoneId)
                .format(Instant.ofEpochMilli(safeMillis))
        } catch (_: RuntimeException) {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
                .withZone(zoneId)
                .format(Instant.ofEpochMilli(safeMillis))
        }
    }

    /**
     * 解析拍摄时间戳：优先 EXIF 拍摄时间，缺失/非法时回落当前时间。
     *
     * @param exifTakenMillis EXIF 拍摄时间（毫秒），null 或 <=0 视为缺失。
     * @param nowMillis 当前时间（默认取系统时间，便于单测注入）。
     */
    fun resolveTimestamp(
        exifTakenMillis: Long?,
        useExifTakenTime: Boolean = true,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        if (!useExifTakenTime) return sanitizeMillis(nowMillis)
        val exif = exifTakenMillis ?: return sanitizeMillis(nowMillis)
        if (exif <= 0L) return sanitizeMillis(nowMillis)
        return sanitizeMillis(exif)
    }

    /** 时间戳合法化：非正数或超出 java.time 可表示范围 → 当前时间。 */
    fun sanitizeMillis(millis: Long): Long {
        if (millis <= 0L) return System.currentTimeMillis()
        // Instant 的安全边界（约公元 0001..9999）
        if (millis > MAX_SAFE_MILLIS || millis < MIN_SAFE_MILLIS) return System.currentTimeMillis()
        return millis
    }

    /** 约 9999-12-31T23:59:59Z。 */
    const val MAX_SAFE_MILLIS = 253_402_300_799_000L

    /** 约 0001-01-01T00:00:00Z。 */
    const val MIN_SAFE_MILLIS = -62_135_596_800_000L
}
