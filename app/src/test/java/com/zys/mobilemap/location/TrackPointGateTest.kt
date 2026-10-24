package com.zys.mobilemap.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹采集门控单测：覆盖五判据与"连续被拒升级为间隙接受"的兜底。
 *
 * 坐标取 30°N/120°E 附近，1 个纬度 ≈ 111 km，便于用小数位控制米级距离。
 */
class TrackPointGateTest {

    private val baseLat = 30.0
    private val baseLon = 120.0

    /** 一条"好"的 GPS fix：来源 gps、精度 8 m、刚产生、离上一点 20 m */
    private fun good(distance: Double = 20.0) = GateInput(
        lat = baseLat + distance / 111_000.0, lon = baseLon, provider = "gps",
        accuracyMeters = 8.0, fixAgeMs = 500, gapMs = 2000, distanceFromLastMeters = distance
    )

    @Test
    fun `无已接受点时质量达标即接受`() {
        val gate = TrackPointGate()
        val v = gate.decide(good().copy(distanceFromLastMeters = -1.0))
        assertEquals(Verdict.Accept, v)
    }

    @Test
    fun `陈旧fix直接拒且不算入被拒簇`() {
        val gate = TrackPointGate()
        repeat(10) {
            assertEquals(Verdict.Reject(RejectReason.STALE), gate.decide(good().copy(fixAgeMs = 60_000)))
        }
    }

    @Test
    fun `network与passive来源不入轨迹`() {
        val gate = TrackPointGate()
        assertEquals(
            Verdict.Reject(RejectReason.UNTRUSTED_PROVIDER),
            gate.decide(good().copy(provider = "network", accuracyMeters = 300.0))
        )
        assertFalse(TrackPointGate.isTrustedProvider("passive"))
        assertFalse(TrackPointGate.isTrustedProvider(null))
        assertTrue(TrackPointGate.isTrustedProvider("gps"))
        assertTrue(TrackPointGate.isTrustedProvider("fused"))
    }

    @Test
    fun `精度半径超限即拒`() {
        val gate = TrackPointGate()
        assertEquals(
            Verdict.Reject(RejectReason.POOR_ACCURACY),
            gate.decide(good().copy(accuracyMeters = TrackPointGate.MAX_ACCURACY_M + 5))
        )
    }

    @Test
    fun `未上报精度时不因精度判死`() {
        val gate = TrackPointGate()
        assertEquals(Verdict.Accept, gate.decide(good().copy(accuracyMeters = -1.0)))
    }

    @Test
    fun `间距不足视为抖动丢弃`() {
        val gate = TrackPointGate()
        assertEquals(
            Verdict.Reject(RejectReason.TOO_CLOSE),
            gate.decide(good(1.0))
        )
    }

    @Test
    fun `速度明显不可能判为跳点`() {
        val gate = TrackPointGate()
        // 2 秒位移 500 m = 250 m/s
        assertEquals(Verdict.Reject(RejectReason.IMPROBABLE_SPEED), gate.decide(good(500.0)))
        // 同一段位移但间隔 60 秒 = 8.3 m/s（坐车），正常接受
        assertEquals(Verdict.Accept, gate.decide(good(500.0).copy(gapMs = 60_000)))
        // 间隔过短不判速度，交给间距判据（此处 20 m ≥ 5 m 故接受）
        assertEquals(Verdict.Accept, gate.decide(good(20.0).copy(gapMs = 100)))
    }

    @Test
    fun `原地乱跳不攒间隙计数`() {
        val gate = TrackPointGate()
        // 连续 20 个被拒点都落在上一接受点附近（<200 m）：只能一直拒，不能升级为间隙接受
        repeat(20) {
            val r = gate.decide(good(120.0).copy(accuracyMeters = 200.0, gapMs = 2000))
            assertEquals(Verdict.Reject(RejectReason.POOR_ACCURACY), r)
        }
    }

    @Test
    fun `连续稳定在新区域则升级为间隙接受`() {
        val gate = TrackPointGate()
        // 隧道出口：每 2 秒一个点，都离上一接受点 800 m（速度不可能），且彼此聚在 30 m 内
        val far = good(800.0)
        for (i in 1 until TrackPointGate.GAP_CONSECUTIVE_REJECTS) {
            assertEquals(
                "第 $i 个点仍应被拒",
                Verdict.Reject(RejectReason.IMPROBABLE_SPEED),
                gate.decide(far.copy(lat = far.lat + i * 0.0001))
            )
        }
        val v = gate.decide(far.copy(lat = far.lat + TrackPointGate.GAP_CONSECUTIVE_REJECTS * 0.0001))
        assertTrue("第 ${TrackPointGate.GAP_CONSECUTIVE_REJECTS} 个点应转间隙接受，实得 $v", v is Verdict.AcceptGap)
        assertEquals(800.0, (v as Verdict.AcceptGap).distanceMeters, 0.5)
        // 接受后计数清零：下一点恢复正常即普通接受
        assertEquals(Verdict.Accept, gate.decide(good(20.0)))
    }

    @Test
    fun `慢速漂移也能攒够间隙计数`() {
        // 每点相对上一点只挪 15 m（相邻聚簇用间距而非簇首锚点做参照，慢走不该把记录冻死），
        // 但整簇都离上一接受点 800 m 且精度超限
        val gate = TrackPointGate()
        val base = good(800.0).copy(accuracyMeters = 120.0)
        for (i in 1 until TrackPointGate.GAP_CONSECUTIVE_REJECTS) {
            assertEquals(
                Verdict.Reject(RejectReason.POOR_ACCURACY),
                gate.decide(base.copy(lat = base.lat + i * 0.00015))
            )
        }
        assertTrue(
            gate.decide(base.copy(lat = base.lat + TrackPointGate.GAP_CONSECUTIVE_REJECTS * 0.00015))
                is Verdict.AcceptGap
        )
    }

    @Test
    fun `乱跳不聚簇不升级间隙`() {
        val gate = TrackPointGate()
        for (i in 0 until 10) {
            // 每个被拒点彼此相距 > 30 m（交替在 800 m 与 3 km 处），不认作稳定新区域
            val d = if (i % 2 == 0) 800.0 else 3000.0
            val v = gate.decide(good(d))
            assertTrue("i=$i 应持续拒，实得 $v", v is Verdict.Reject)
        }
    }
}
