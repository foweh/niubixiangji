package com.hinnka.mycamera.multiframesr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内存降级路径：1.0x → 0.75x → 0.5x → 流式落盘。
 *
 * 核心不变量：**任何内存压力下都不拒绝任务**，只降级；降级后仍必须产出可用结果。
 */
class MfsrMemoryPolicyTest {

    private val tempDirs = ArrayList<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { MfsrTestFixtures.deleteRecursively(it) }
        tempDirs.clear()
    }

    private fun newTempDir(): File {
        val d = MfsrTestFixtures.tempDir("mfsr")
        tempDirs += d
        return d
    }

    // ---------------- 决策 ----------------

    @Test
    fun ampleBudgetKeepsTheRequestedTierWithoutDegrading() {
        val d = MfsrMemoryPolicy.decide(
            lrWidth = 512, lrHeight = 384, scale = 2f,
            requested = MfsrWorkingResolution.FULL,
            budgetBytes = MfsrConfig.DEFAULT_MEMORY_BUDGET_BYTES,
            guard = { true },
            spillAvailable = true,
        )
        assertEquals(MfsrWorkingResolution.FULL, d.resolution)
        assertEquals(0, d.degradedSteps)
        assertFalse(d.degraded)
        assertFalse(d.streamingFallback)
        assertTrue(d.guardSatisfied)
        assertEquals(1024, d.hrWidth)
        assertEquals(768, d.hrHeight)
    }

    @Test
    fun budgetPressureWalksDownTheLadderOneStepAtATime() {
        // 4000x3000 @ scale2：FULL≈1008MB、0.75x≈567MB 都超 512MB 预算；0.5x≈252MB 达标
        val d = MfsrMemoryPolicy.decide(
            lrWidth = 4000, lrHeight = 3000, scale = 2f,
            requested = MfsrWorkingResolution.FULL,
            budgetBytes = 512L * 1024 * 1024,
            guard = { true },
            spillAvailable = true,
        )
        assertEquals(MfsrWorkingResolution.HALF, d.resolution)
        assertEquals(2, d.degradedSteps)
        assertTrue(d.degraded)
        assertTrue(d.guardSatisfied)
        assertTrue(d.peakBytes <= 512L * 1024 * 1024)
        assertEquals(2000, d.workWidth)
        assertEquals(1500, d.workHeight)
        assertEquals(4000, d.hrWidth)
        assertEquals(3000, d.hrHeight)
    }

    @Test
    fun guardRejectionAloneAlsoDrivesDegradation() {
        val threshold = 300L * 1024 * 1024
        val d = MfsrMemoryPolicy.decide(
            lrWidth = 4000, lrHeight = 3000, scale = 2f,
            requested = MfsrWorkingResolution.FULL,
            budgetBytes = Long.MAX_VALUE,
            guard = { it < threshold },
            spillAvailable = true,
        )
        assertEquals(MfsrWorkingResolution.HALF, d.resolution)
        assertTrue(d.peakBytes < threshold)
    }

    @Test
    fun hostileGuardDegradesToStreamingInsteadOfRejecting() {
        val d = MfsrMemoryPolicy.decide(
            lrWidth = 4000, lrHeight = 3000, scale = 3f,
            requested = MfsrWorkingResolution.FULL,
            budgetBytes = MfsrConfig.MEMORY_BUDGET_MIN_BYTES,
            guard = { false },
            spillAvailable = true,
        )
        assertEquals("必须停在最低档位而不是拒绝", MfsrWorkingResolution.HALF, d.resolution)
        assertTrue("应启用流式回退", d.streamingFallback)
        assertFalse(d.guardSatisfied)
        assertTrue(d.degraded)
        assertEquals(0L, d.residentFrameBudgetBytes)
    }

    @Test
    fun withoutSpillDirectoryTheTaskIsStillAccepted() {
        val d = MfsrMemoryPolicy.decide(
            lrWidth = 2000, lrHeight = 1500, scale = 3f,
            requested = MfsrWorkingResolution.FULL,
            budgetBytes = MfsrConfig.MEMORY_BUDGET_MIN_BYTES,
            guard = { false },
            spillAvailable = false,
        )
        assertNotNull(d)
        assertEquals(MfsrWorkingResolution.HALF, d.resolution)
        assertTrue(d.degraded)
    }

    @Test
    fun peakEstimateIsIndependentOfFrameCountAndMonotonicInTier() {
        val full = MfsrMemoryPolicy.estimatePeakBytes(1000, 800, 2f)
        val threeQuarter = MfsrMemoryPolicy.estimatePeakBytes(
            MfsrMemoryPolicy.workSize(1000, MfsrWorkingResolution.THREE_QUARTER),
            MfsrMemoryPolicy.workSize(800, MfsrWorkingResolution.THREE_QUARTER),
            2f,
        )
        val half = MfsrMemoryPolicy.estimatePeakBytes(
            MfsrMemoryPolicy.workSize(1000, MfsrWorkingResolution.HALF),
            MfsrMemoryPolicy.workSize(800, MfsrWorkingResolution.HALF),
            2f,
        )
        assertTrue("档位越低峰值越小", full > threeQuarter && threeQuarter > half)
        // 同一输入重复估算必须一致（帧数完全不参与）
        assertEquals(full, MfsrMemoryPolicy.estimatePeakBytes(1000, 800, 2f))
        assertTrue(full > 0L)
    }

    @Test
    fun sizeHelpersHandleDegenerateInput() {
        assertEquals(8, MfsrMemoryPolicy.workSize(1, MfsrWorkingResolution.HALF))
        assertEquals(1000, MfsrMemoryPolicy.workSize(1000, MfsrWorkingResolution.FULL))
        assertEquals(750, MfsrMemoryPolicy.workSize(1000, MfsrWorkingResolution.THREE_QUARTER))
        assertEquals(500, MfsrMemoryPolicy.workSize(1000, MfsrWorkingResolution.HALF))
        assertEquals(1, MfsrMemoryPolicy.hrSize(1, 1.25f))
        assertEquals(2500, MfsrMemoryPolicy.hrSize(1000, 2.5f))
        // 倍率越界也安全
        assertEquals(3000, MfsrMemoryPolicy.hrSize(1000, 99f))
        assertEquals(2000, MfsrMemoryPolicy.hrSize(1000, 2f))
    }

    @Test
    fun resolutionLadderAndCoercion() {
        assertEquals(
            listOf(MfsrWorkingResolution.FULL, MfsrWorkingResolution.THREE_QUARTER, MfsrWorkingResolution.HALF),
            MfsrWorkingResolution.LADDER,
        )
        assertNull(MfsrWorkingResolution.HALF.nextLower)
        assertEquals(MfsrWorkingResolution.THREE_QUARTER, MfsrWorkingResolution.FULL.nextLower)
        assertEquals(MfsrWorkingResolution.FULL, MfsrWorkingResolution.fromFactor(1.0f))
        assertEquals(MfsrWorkingResolution.THREE_QUARTER, MfsrWorkingResolution.fromFactor(0.7f))
        assertEquals(MfsrWorkingResolution.HALF, MfsrWorkingResolution.fromFactor(0.4f))
        assertEquals(MfsrWorkingResolution.HALF, MfsrWorkingResolution.fromFactor(Float.NaN))
    }

    // ---------------- 帧缓存落盘 ----------------

    @Test
    fun thousandFramesSpillToDiskWithBoundedMemory() {
        val dir = newTempDir()
        val store = MfsrFrameStore(spillDir = dir, residentBudgetBytes = 0L, token = "spilltest")
        val n = 1000
        for (i in 0 until n) {
            val p = MfsrPlane(8, 6)
            p.data.fill((i % 251).toFloat())
            store.put(i, p)
        }
        assertEquals("不得截断帧数", n, store.count)
        assertTrue(store.spilled)
        assertEquals(MfsrFrameStoreKind.SPILLED, store.kind)
        assertEquals("零常驻预算下内存里不应留帧", 0, store.residentFrames)
        assertEquals(0L, store.residentBytes)

        for (i in listOf(0, 499, 999)) {
            val loaded = store.load(i)
            assertNotNull("第 $i 帧应可从磁盘取回", loaded)
            assertEquals((i % 251).toFloat(), loaded!!.data[0], 0f)
            assertEquals(8, loaded.width)
            assertEquals(6, loaded.height)
        }
        assertNull(store.load(n))
        assertNull(store.load(-1))
        assertFalse("落盘时不应标记超预算驻留", store.overBudgetResident)

        store.clear()
        assertEquals(0, store.count)
        assertEquals("清理后不应残留缓存文件", 0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun withoutSpillDirectoryFramesStayResidentAndAreMarkedAsDegraded() {
        val store = MfsrFrameStore(spillDir = null, residentBudgetBytes = 64L, token = "memtest")
        repeat(5) { i ->
            val p = MfsrPlane(4, 4)
            p.data.fill(i.toFloat())
            store.put(i, p)
        }
        assertEquals(5, store.count)
        assertFalse(store.spilled)
        assertEquals(MfsrFrameStoreKind.MEMORY, store.kind)
        assertTrue("超预算但无处落盘 → 标记降级而非失败", store.overBudgetResident)
        assertEquals(5, store.residentFrames)
        assertEquals(2f, store.load(2)!!.data[0], 0f)
    }

    @Test
    fun withinBudgetFramesNeverTouchTheDisk() {
        val dir = newTempDir()
        val store = MfsrFrameStore(spillDir = dir, residentBudgetBytes = 1024L * 1024L, token = "budgetok")
        repeat(10) { i -> store.put(i, MfsrPlane(16, 16)) }
        assertEquals(MfsrFrameStoreKind.MEMORY, store.kind)
        assertFalse(store.spilled)
        assertEquals(10, store.residentFrames)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    // ---------------- 会话级降级 ----------------

    @Test
    fun sessionWithGenerousGuardDoesNotDegrade() {
        val session = MfsrSession(
            rawConfig = MfsrConfig(scale = 2f, alignMethod = MfsrAlignMethod.OFF, ibpIterations = 3),
            guard = { true },
        )
        repeat(4) { session.ingest(MfsrTestFixtures.pattern(32, 24)) }
        val result = session.finish()
        assertFalse(result.degraded)
        assertFalse(result.ibpSkippedForMemory)
        assertEquals(MfsrFrameStoreKind.MEMORY, result.storeKind)
        assertEquals(MfsrWorkingResolution.FULL, result.workingResolution)
        assertEquals(result.width * result.height, result.luma.size)
        assertEquals(result.framesUsed, result.shifts.size)
    }

    @Test
    fun sessionWithHostileGuardStillProducesAUsableResultFromTheLowestTier() {
        val session = MfsrSession(
            rawConfig = MfsrConfig(scale = 2f, alignMethod = MfsrAlignMethod.OFF, ibpIterations = 4),
            guard = { false },
        )
        repeat(4) { session.ingest(MfsrTestFixtures.pattern(32, 24)) }
        val result = session.finish()
        assertEquals(MfsrWorkingResolution.HALF, result.workingResolution)
        assertEquals(4, result.framesUsed)
        assertTrue(result.degraded)
        assertTrue(result.ibpSkippedForMemory)
        val expected = MfsrMemoryPolicy.hrSize(MfsrMemoryPolicy.workSize(32, MfsrWorkingResolution.HALF), 2f)
        assertEquals(expected, result.width)
        assertEquals(result.width * result.height, result.luma.size)
    }
}
