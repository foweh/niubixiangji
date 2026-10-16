package com.hinnka.mycamera.multiframesr

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 帧缓存 —— MFSR 能做到「上百/上千帧不 OOM」的关键。
 *
 * 职责：把每帧（工作分辨率亮度平面）按序存起来，供「迭代反投影」阶段多轮复用，
 * 同时把常驻内存控制在 [residentBudgetBytes] 以内：
 *  - 未超预算 → 留在内存（最快）；
 *  - 超预算且给了 [spillDir] → 写入磁盘文件，内存只保留在途帧（流式）；
 *  - 超预算但**没有**可写目录 → 仍留在内存并标记 [overBudgetResident]（降级而非失败）。
 *
 * 纯 JVM 实现（只用 java.io），可在单测里用临时目录验证 1000 帧落盘路径。
 */
class MfsrFrameStore(
    private val spillDir: File? = null,
    private val residentBudgetBytes: Long = 0L,
    private val token: String = "s" + System.nanoTime().toString(36),
) {
    private class Entry(val index: Int, var plane: MfsrPlane?, val file: File?, val bytes: Long)

    private val entries = ArrayList<Entry>()
    private var resident = 0L

    /** 已写入的帧数（不做任何截断，等于调用方 put 的次数）。 */
    val count: Int get() = entries.size

    /** 是否有帧落到磁盘。 */
    var spilled: Boolean = false
        private set

    /** 是否出现过「超预算但仍驻留内存」的降级。 */
    var overBudgetResident: Boolean = false
        private set

    /** 当前常驻内存字节数。 */
    val residentBytes: Long get() = resident

    /** 常驻内存中的帧数。 */
    val residentFrames: Int get() = entries.count { it.plane != null }

    val kind: MfsrFrameStoreKind
        get() = if (spilled) MfsrFrameStoreKind.SPILLED else MfsrFrameStoreKind.MEMORY

    fun put(index: Int, plane: MfsrPlane) {
        val bytes = plane.pixels.toLong() * 4L
        // 预算 <= 0 视为"没有常驻预算"：只要有落盘目录，帧就全部走磁盘（流式）。
        val needSpill = residentBudgetBytes <= 0L || (resident + bytes > residentBudgetBytes)
        if (needSpill && spillDir != null) {
            val file = File(spillDir, "mfsr_${token}_$index.f32")
            if (writePlane(file, plane)) {
                entries += Entry(index, null, file, bytes)
                spilled = true
                return
            }
            // 落盘失败 → 退化为内存驻留（不中断任务）。
        }
        if (needSpill) overBudgetResident = true
        entries += Entry(index, plane, null, bytes)
        resident += bytes
    }

    /** 按序号取帧（顺序敏感：IBP 会多轮顺序读取）。取不到返回 null（上层跳过该帧）。 */
    fun load(index: Int): MfsrPlane? {
        val e = entries.getOrNull(index) ?: return null
        if (e.index != index) return null
        e.plane?.let { return it }
        val f = e.file ?: return null
        return readPlane(f)
    }

    /** 清理：删除落盘文件，释放内存引用。 */
    fun clear() {
        for (e in entries) {
            e.file?.let { runCatching { it.delete() } }
            e.plane = null
        }
        entries.clear()
        resident = 0L
    }

    private fun writePlane(file: File, plane: MfsrPlane): Boolean = runCatching {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(file.outputStream(), 1 shl 16)).use { out ->
            out.writeInt(plane.width)
            out.writeInt(plane.height)
            for (v in plane.data) out.writeFloat(v)
        }
        true
    }.getOrDefault(false)

    private fun readPlane(file: File): MfsrPlane? = runCatching {
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            val w = input.readInt()
            val h = input.readInt()
            val data = FloatArray(w * h)
            for (i in data.indices) data[i] = input.readFloat()
            MfsrPlane(w, h, data)
        }
    }.getOrNull()
}
