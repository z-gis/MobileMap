package com.zys.mobilemap.location

import com.zys.mobilemap.util.GeoCalc
import java.util.Locale

/**
 * 轨迹采集点门控：把一次系统定位回调判为「接受 / 拒绝（原因）/ 间隙接受」。
 *
 * 存在的理由（见 doc/模块/轨迹跳点成因与修复方案.md）：轨迹记录此前唯一的过滤是"与上一个已录点
 * 距离 ≥ 5 m"，而 5 m 门限挡的是 GNSS 噪声、挡不住几十到几百米的粗差——`network`/`passive` 的粗
 * 差点、静止随机游走点、失锁重定位的首点都会成为轨迹顶点，两侧连线即现场看到的"单点跑偏 + 尖角"。
 * 本类把这些判据集中到一处：来源、新鲜度、精度、间距、速度。
 *
 * 口径约定：
 *  - 只作用于**轨迹入库链路**。蓝点显示、底部坐标文本、相机跟随仍吃全部来源，否则室内会退化成
 *    "无定位"，用户看到的是没点而不是差点；
 *  - 输入是 [GateInput] 纯数据（由调用方从 `android.location.Location` 抽取），本类不引用任何
 *    Android API，可在 JVM 单测里直接判（[TrackPointGateTest]）；
 *  - 时间一律用 `SystemClock.elapsedRealtime()` 口径的单调毫秒：`Location.time` 是墙钟时间，会被
 *    网络校时来回拉扯，不能用来算 fix 年龄与点间隔；
 *  - 有状态（连续被拒簇计数），**一个记录会话一个实例**，开始/恢复记录时新建或 [reset]。
 */
class TrackPointGate {

    /** 上一个被拒点（相邻间距参照）；NaN 表示无进行中的被拒簇 */
    private var runRefLat = Double.NaN
    private var runRefLon = Double.NaN

    /** 当前连续被拒且相邻彼此聚簇的点数：达到 [GAP_CONSECUTIVE_REJECTS] 且离上一接受点足够远即转间隙接受 */
    private var rejectRun = 0

    /** 清掉被拒簇计数（接受一个点、或判定为"回到原处/太近"时调用） */
    fun reset() {
        runRefLat = Double.NaN
        runRefLon = Double.NaN
        rejectRun = 0
    }

    /**
     * 判定一次定位。参数语义见 [GateInput]；本方法不修改 [trackActivePoints] 之类的宿主状态，
     * 宿主据返回值决定入库/计数/提示，并在接受后自行 [reset]。
     */
    fun decide(input: GateInput): Verdict {
        // 1) 陈旧 fix（息屏/Doze 期间批量补发的旧点）：直接拒，且不参与被拒簇计数——它说明不了"人 moved 到哪"
        if (input.fixAgeMs > MAX_FIX_AGE_MS) return Verdict.Reject(RejectReason.STALE)
        // 2) 粗差来源：network（WiFi/基站，城镇偏差 50–300 m、山区公里级）与 passive（别的应用触发的转发定位）
        if (!isTrustedProvider(input.provider)) return Verdict.Reject(RejectReason.UNTRUSTED_PROVIDER)

        val hasLast = input.distanceFromLastMeters >= 0
        val distance = if (hasLast) input.distanceFromLastMeters else Double.NaN
        // 设备未上报精度半径（accuracy<0 / hasAccuracy=false）时不设精度上限，靠来源+新鲜度+速度兜底
        val poorAccuracy = input.accuracyMeters >= 0 && input.accuracyMeters > MAX_ACCURACY_M
        val tooClose = hasLast && distance < MIN_DISTANCE_METERS
        // 速度：位移/单调时间差；间隔过短（多源同秒回调）不判，避免把 5 m 抖动放大成"超音速"
        val speedMps = if (hasLast && input.gapMs >= MIN_GAP_MS) distance * 1000.0 / input.gapMs else Double.NaN
        val improbable = speedMps.isFinite() && speedMps > MAX_SPEED_MPS

        if (!poorAccuracy && !improbable) {
            // 尚无已接受点（起录/恢复后的首点）：质量达标即接受
            if (!hasLast) { reset(); return Verdict.Accept }
            if (tooClose) { reset(); return Verdict.Reject(RejectReason.TOO_CLOSE) }
            reset()
            return Verdict.Accept
        }

        // 3) 被拒的点要攒成"簇"才升级为间隙接受：连续 [GAP_CONSECUTIVE_REJECTS] 个点相邻彼此相距
        //    ≤ [RUN_CLUSTER_RADIUS_M]（信号稳定在一片新区域，而不是到处乱跳）且离上一接受点
        //    > [GAP_DISTANCE_M]（真的移动了一段，典型场景是长隧道/林下失锁后重定位）——此时接受并
        //    标间隙，否则记录会被永久卡死在失锁前的位置。
        val reason = if (poorAccuracy) RejectReason.POOR_ACCURACY else RejectReason.IMPROBABLE_SPEED
        noteReject(input, distance, hasLast)
        if (rejectRun >= GAP_CONSECUTIVE_REJECTS && hasLast && distance > GAP_DISTANCE_M) {
            reset()
            return Verdict.AcceptGap(distance, GAP_CONSECUTIVE_REJECTS)
        }
        return Verdict.Reject(reason)
    }

    /** 记入被拒簇：与**上一个被拒点**聚在一起的累加计数，否则以本点重开一簇（乱跳的点不该攒够间隙阈值）。
     *  用相邻间距而非簇首锚点做参照，慢速漂移（每点挪十几米）才能正常攒够计数，不至于把记录永久冻住。 */
    private fun noteReject(input: GateInput, distanceFromLast: Double, hasLast: Boolean) {
        if (!runRefLat.isNaN()) {
            val d = GeoCalc.distanceMeters(runRefLat, runRefLon, input.lat, input.lon)
            if (d <= RUN_CLUSTER_RADIUS_M) rejectRun++ else rejectRun = 1
        } else {
            rejectRun = 1
        }
        runRefLat = input.lat; runRefLon = input.lon
        // 被拒点若其实就在上一接受点附近（只是精度差/抖动），不构成"到新区域"的证据：不攒计数
        if (hasLast && distanceFromLast < GAP_DISTANCE_M) {
            runRefLat = Double.NaN; runRefLon = Double.NaN; rejectRun = 0
        }
    }

    companion object {
        /** fix 年龄上限（毫秒）：超过即视为陈旧点（Doze 批量补发、恢复草稿时的历史点） */
        const val MAX_FIX_AGE_MS = 8_000L

        /** 精度半径上限（米）：现场按机型校准，15 m 会在林下大量丢点、50 m 仍会漏进尖角，故取 30 m 起测 */
        const val MAX_ACCURACY_M = 30.0

        /** 相邻轨迹点最小间距（米）：沿用既有口径，挡 GPS 抖动（原 MapStyleColors.MIN_TRACK_POINT_DISTANCE_METERS） */
        const val MIN_DISTANCE_METERS = 5.0

        /** 速度判定所需最小时间间隔（毫秒）：低于此值不判速度，多源同秒回调交给间距判据 */
        const val MIN_GAP_MS = 500L

        /** 速度上限（米/秒）：覆盖机动车巡检，步行 1.5 m/s 余量极大；2 s 间隔里跑出 200 m 即 100 m/s，明显是跳点 */
        const val MAX_SPEED_MPS = 25.0

        /** 间隙接受所需的连续被拒点数 */
        const val GAP_CONSECUTIVE_REJECTS = 5

        /** 间隙接受所需的最小位移（米）：小于此距离的持续被拒只是噪声，不值得断线 */
        const val GAP_DISTANCE_M = 200.0

        /** 被拒点聚为同一簇的半径（米）：簇内彼此靠近才算"稳定在新区域" */
        const val RUN_CLUSTER_RADIUS_M = 30.0

        /**
         * 是否为可入轨迹的定位来源。放行 gps/gnss/融合定位；拦掉 network/passive；
         * 未知来源（厂商自定义 provider 名）放行——它们没有粗差来源那么明确的画像，交给精度与速度判据兜底。
         */
        fun isTrustedProvider(provider: String?): Boolean {
            if (provider.isNullOrEmpty()) return false
            val n = provider.lowercase(Locale.US)
            if (n == "network" || n == "passive") return false
            return true
        }
    }
}

/**
 * 门控输入：从 `android.location.Location` 抽取的判据，全部为纯数据类型。
 *
 * [accuracyMeters] 传 -1 表示设备未提供精度（`hasAccuracy()` 为 false）；
 * [fixAgeMs] 是"判定时刻 - fix 单调时间戳"；[gapMs] 传 -1 表示与上一个已接受点没有可靠的时间基准
 * （如恢复历史草稿后首次判定），此时跳过速度判定；[distanceFromLastMeters] 传 -1 表示尚无已接受点。
 */
data class GateInput(
    val lat: Double,
    val lon: Double,
    val provider: String?,
    val accuracyMeters: Double,
    val fixAgeMs: Long,
    val gapMs: Long,
    val distanceFromLastMeters: Double
)

/** 拒绝原因：日志与剔除计数按此分类，现场反馈可据此判断是混入粗差还是失锁间隙 */
enum class RejectReason {
    STALE, UNTRUSTED_PROVIDER, POOR_ACCURACY, TOO_CLOSE, IMPROBABLE_SPEED
}

/** 门控结论 */
sealed interface Verdict {
    /** 接受入轨迹 */
    data object Accept : Verdict

    /** 拒掉，并给出原因 */
    data class Reject(val reason: RejectReason) : Verdict

    /**
     * 间隙接受：连续被拒后认定确实到了新位置。[distanceMeters] 是到上一个已接受点的距离，
     * 阶段 2 据此在此处断段（当前仍按单段折线连，日志与提示会标明）。
     */
    data class AcceptGap(val distanceMeters: Double, val consecutiveRejects: Int) : Verdict
}
