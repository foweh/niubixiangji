package com.hinnka.mycamera.longexposure

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 光绘/烟火实时会话 + 实时叠加预览队列单测（纯 JVM，含真实后台线程）。
 *
 * 覆盖：
 *  - 会话增量叠加（MAX / MEAN）、暗帧扣除、工作分辨率限制；
 *  - 帧数**开放**：目标帧数与实际帧数解耦（达到目标后继续喂帧不报错、结果继续累加）；
 *  - 预览后台队列：抽帧、节流、预览分辨率上限、丢帧计数、关闭后线程退出。
 *
 * 预览相关断言用"等到第 N 张预览"的闩锁 + 3s 超时，避免依赖固定 sleep。
 */
class LightPaintingSessionTest {

    @Before
    fun useKotlinPath() {
        LongExposureNative.forceKotlinForTest()
    }

    private val neutralSpec = LongExposureSessionSpec(
        stackMode = LongExposureStackMode.MAX,
        frameCountTarget = 30,
        darkFrame = DarkFrameConfig.DEFAULT.copy(enabled = false),
        brushColorTemperatureK = ColorTemperatureGains.NEUTRAL_KELVIN,
        brushTint = 0f,
        brushGain = 1f,
    )

    private fun plane(width: Int, height: Int, fill: Int): LinearRgbPlane =
        LinearRgbPlane(width, height, ShortArray(width * height * 3) { fill.toShort() })

    // ------------------------------------------------------------ 会话叠加

    @Test
    fun sessionAccumulatesMaximumWhenColourGainsAreNeutral() {
        val session = LightPaintingSession(neutralSpec)
        try {
            session.addFrame(LinearRgbPlane(1, 1, shortArrayOf(100, 200, 300)))
            session.addFrame(LinearRgbPlane(1, 1, shortArrayOf(150, 100, 250)))
            assertEquals(2, session.accumulatedFrames)
            assertArrayEquals(
                shortArrayOf(150, 200, 300),
                requireNotNull(session.finish()).samples,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun sessionAppliesWarmBrushColourTemperature() {
        val session = LightPaintingSession(
            neutralSpec.copy(brushColorTemperatureK = 3200),
        )
        try {
            session.addFrame(plane(1, 1, 4000))
            val result = requireNotNull(session.finish())
            val expected = ColorTemperatureGains.gainsFor(3200, 0f)
            assertEquals("暖调应压蓝通道", (4000 * expected[2]).toDouble(), result.samples[2].toDouble(), 1.0)
            assertEquals(1f, expected[0], 1e-4f)
            assertEquals(4000, result.samples[0].toInt())
            assertTrue(result.samples[2].toInt() < result.samples[0].toInt())
        } finally {
            session.close()
        }
    }

    @Test
    fun sessionSubtractsDarkFrameWhenProvided() {
        val session = LightPaintingSession(neutralSpec.copy(darkFrame = DarkFrameConfig.DEFAULT))
        try {
            assertTrue(
                session.setDarkFrames(
                    listOf(
                        plane(1, 1, 10),
                        plane(1, 1, 20),
                    ),
                ),
            )
            session.addFrame(LinearRgbPlane(1, 1, shortArrayOf(100, 100, 100)))
            // 平均暗帧 = 15；100 - 15 = 85
            assertArrayEquals(shortArrayOf(85, 85, 85), requireNotNull(session.finish()).samples)
        } finally {
            session.close()
        }
    }

    @Test
    fun emptyDarkFrameListIsRejectedButSessionStillWorks() {
        val session = LightPaintingSession(neutralSpec.copy(darkFrame = DarkFrameConfig.DEFAULT))
        try {
            assertFalse(session.setDarkFrames(emptyList()))
            session.addFrame(plane(1, 1, 100))
            assertArrayEquals(shortArrayOf(100, 100, 100), requireNotNull(session.finish()).samples)
        } finally {
            session.close()
        }
    }

    @Test
    fun meanSessionAveragesFrames() {
        val session = LightPaintingSession(neutralSpec.copy(stackMode = LongExposureStackMode.MEAN))
        try {
            session.addFrame(plane(1, 1, 10))
            session.addFrame(plane(1, 1, 30))
            assertArrayEquals(shortArrayOf(20, 20, 20), requireNotNull(session.finish()).samples)
        } finally {
            session.close()
        }
    }

    @Test
    fun sessionDownscalesToConfiguredWorkLongEdge() {
        val session = LightPaintingSession(neutralSpec.copy(workLongEdge = 8))
        try {
            session.addFrame(plane(16, 16, 1000))
            val result = requireNotNull(session.finish())
            assertEquals(8, result.width)
            assertEquals(8, result.height)
            assertTrue(result.samples.all { it.toInt() == 1000 })
        } finally {
            session.close()
        }
    }

    @Test
    fun frameCountIsOpenAndNotCappedByTarget() {
        val session = LightPaintingSession(neutralSpec.copy(frameCountTarget = 2))
        try {
            session.addFrame(plane(1, 1, 10))
            session.addFrame(plane(1, 1, 20))
            assertTrue("达到目标帧数", session.reachedTargetFrames)
            // 帧数不设总量上限：继续喂帧必须正常工作并继续累加。
            repeat(50) { session.addFrame(plane(1, 1, it)) }
            assertEquals(52, session.accumulatedFrames)
            assertArrayEquals(shortArrayOf(49, 49, 49), requireNotNull(session.finish()).samples)
        } finally {
            session.close()
        }
    }

    @Test
    fun finishBeforeAnyFrameReturnsNullInsteadOfCrashing() {
        val session = LightPaintingSession(neutralSpec)
        try {
            assertNull(session.finish())
            assertNull(session.previewNow())
            assertEquals(0, session.accumulatedFrames)
        } finally {
            session.close()
        }
    }

    @Test
    fun previewNowMatchesAccumulatedMaximum() {
        val session = LightPaintingSession(neutralSpec.copy(previewMaxLongEdge = 4))
        try {
            session.addFrame(plane(4, 4, 500))
            session.addFrame(plane(4, 4, 900))
            val preview = assertNotNullAndGet(session.previewNow())
            assertEquals(4, preview.width)
            assertTrue(preview.samples.all { it.toInt() == 900 })
        } finally {
            session.close()
        }
    }

    // ------------------------------------------------------------ 预览队列

    @Test
    fun previewQueueEmitsPreviewsAtBoundedResolution() {
        val latch = CountDownLatch(1)
        val received = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Int>>())
        val queue = LightPaintingPreviewQueue(
            config = LightPaintingConfig(
                previewIntervalFrames = 1,
                // 用 clamp 下限（PREVIEW_EDGE_MIN = 64）：更小的值会被 clampWithReport 抬到 64，
                // 故以真实下限验证"预览长边受 previewMaxLongEdge 限制"这一契约。
                previewMaxLongEdge = LightPaintingConfig.PREVIEW_EDGE_MIN,
                previewThrottleMs = LightPaintingConfig.PREVIEW_THROTTLE_MIN,
            ),
            onPreview = { preview, _ ->
                received.add(preview.width to preview.height)
                latch.countDown()
            },
        )
        try {
            queue.offer(plane(256, 128, 100))
            assertTrue("预览应在超时前产出", latch.await(3, TimeUnit.SECONDS))
            assertEquals(1, queue.offeredFrames)
            assertTrue(queue.previewCount >= 1)
            assertTrue(
                "预览分辨率必须受 previewMaxLongEdge 限制",
                received.all { it.first <= 64 && it.second <= 32 },
            )
            assertEquals(64 to 32, received.first())
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun previewQueueAlwaysEmitsForFirstFrameEvenWithSamplingInterval() {
        val latch = CountDownLatch(1)
        val queue = LightPaintingPreviewQueue(
            // 默认抽帧间隔 = 4：自 reset 以来第 1 帧必须受理（首帧预览延迟最小）。
            config = LightPaintingConfig(
                previewIntervalFrames = 4,
                previewThrottleMs = LightPaintingConfig.PREVIEW_THROTTLE_MIN,
            ),
            onPreview = { _, _ -> latch.countDown() },
        )
        try {
            queue.offer(plane(64, 64, 120))
            assertTrue("抽帧间隔 > 1 时首帧仍须产出预览", latch.await(3, TimeUnit.SECONDS))
            assertEquals(1, queue.offeredFrames)
            assertEquals(0, queue.droppedFrames)
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun previewQueueThrottlesRapidFramesToASinglePreviewWindow() {
        val latch = CountDownLatch(1)
        val previews = java.util.Collections.synchronizedList(ArrayList<Int>())
        val queue = LightPaintingPreviewQueue(
            config = LightPaintingConfig(
                previewIntervalFrames = 1,
                previewMaxLongEdge = 16,
                previewThrottleMs = 2000L,
            ),
            onPreview = { _, frameCount ->
                previews.add(frameCount)
                latch.countDown()
            },
        )
        try {
            repeat(3) { queue.offer(plane(32, 16, 100)) }
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            Thread.sleep(100L)
            // 节流窗口 2000ms 内只允许一张预览；被消费掉的帧不会各产一张。
            assertEquals(1, previews.size)
            assertEquals(1, queue.previewCount)
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun previewQueueDropsFramesBelowConfiguredInterval() {
        val latch = CountDownLatch(1)
        val queue = LightPaintingPreviewQueue(
            config = LightPaintingConfig(previewIntervalFrames = 4, previewThrottleMs = 16L),
            onPreview = { _, _ -> latch.countDown() },
        )
        try {
            queue.offer(plane(8, 8, 10)) // 自 reset 以来第 1 帧：必受理
            queue.offer(plane(8, 8, 20)) // 未到抽帧点（2 % 4 != 0）
            queue.offer(plane(8, 8, 30)) // 未到抽帧点（3 % 4 != 0）
            assertEquals(3, queue.offeredFrames)
            // 未到抽帧点的 2 帧必然被丢弃；若消费线程尚未取走第 1 帧，它还会被第 4 帧取代而多计一次。
            assertTrue("未到抽帧点的帧应被丢弃", queue.droppedFrames >= 2)
            queue.offer(plane(8, 8, 40)) // 第 4 帧：到抽帧点
            assertTrue("到抽帧点后应产出预览", latch.await(3, TimeUnit.SECONDS))
            assertTrue(queue.previewCount >= 1)
            assertEquals(4, queue.offeredFrames)
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun offersAfterShutdownAreCountedAsDroppedAndDoNotCrash() {
        val latch = CountDownLatch(1)
        val queue = LightPaintingPreviewQueue(
            config = LightPaintingConfig(previewThrottleMs = 16L),
            onPreview = { _, _ -> latch.countDown() },
        )
        queue.offer(plane(4, 4, 10))
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        queue.shutdown()
        queue.shutdown() // 幂等
        queue.offer(plane(4, 4, 20))
        assertEquals(2, queue.offeredFrames)
        assertTrue(queue.droppedFrames >= 1)
    }

    @Test
    fun sessionPreviewCallbackReceivesDownscaledOverlayAndClosesCleanly() {
        val latch = CountDownLatch(1)
        val observed = java.util.Collections.synchronizedList(ArrayList<LinearRgbPlane>())
        val session = LightPaintingSession(
            // previewMaxLongEdge = clamp 下限 64（更小的值会被抬到 64），帧 128×128 → 预览 ≤ 64×64。
            neutralSpec.copy(previewIntervalFrames = 1, previewMaxLongEdge = 64, previewThrottleMs = 16L),
        ) { preview, _ ->
            observed.add(preview)
            latch.countDown()
        }
        try {
            session.addFrame(plane(128, 128, 300))
            assertTrue("会话应产出实时叠加预览", latch.await(3, TimeUnit.SECONDS))
            val first = observed.first()
            assertTrue("预览分辨率应受 previewMaxLongEdge 限制", first.width <= 64 && first.height <= 64)
        } finally {
            session.close()
        }
    }

    private fun assertNotNullAndGet(plane: LinearRgbPlane?): LinearRgbPlane {
        assertNotNull(plane)
        return plane!!
    }
}
