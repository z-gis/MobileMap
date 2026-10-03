package com.zys.mobilemap.vector

/**
 * 矢量图层重载优化全局开关（单文件集中控制）。
 *
 * 各阶段独立开关，修改常量后重新编译即可生效：
 * - Phase 1 [SWAP_ON_READY]：新旧图层原子替换，消除平移时视觉空窗
 * - Phase 2 [MAX_FEATURES_PER_LAYER]：单图层要素上限截断，防缩小时卡顿
 * - Phase 3 [EXTENT_BUFFER_FACTOR]：预加载缓冲系数，减少小幅平移重载频率
 * - Phase 4 [USE_STREAMING_JSON]：流式 JSON 解析，压缩 Kotlin 端解析耗时
 * - Phase 5 [SIMPLIFY_GEOMETRY] / [SIMPLIFY_TOLERANCE_DEGREES]：几何简化，减少顶点数量
 *
 * 默认策略：Phase 1 启用（核心体验改善），其余保守（待验证后逐步开启）。
 */
object VectorReloadConfig {

    // ═══════════════════════ Phase 1：Swap-on-ready ═══════════════════════

    /**
     * 新图层建好后再原子替换旧图层（短暂共存，内存翻倍但视觉无缝）。
     * - true：平移时旧图层保持可见，直到新图层就绪后一帧内完成替换（无空窗）
     * - false：旧行为——先删旧再建新，有 3-5s 视觉空窗
     */
    const val SWAP_ON_READY = true

    // ═══════════════════════ Phase 2：要素上限截断 ═══════════════════════

    /**
     * 单图层最大可见要素数。超过时按 fid 顺序截断（保留前 N 个）。
     * - 0：不启用（沿用 C++ 渲染通路 VectorReader MAX_VECTOR_FEATURES = 200000 兜底）
     * - 5000：推荐值，超过则截断显示前 5000 个要素
     *
     * 注：C++ 层上限仍作为硬上限兜底（防 OOM），
     * 此开关在 Kotlin 层做更细粒度的截断（可小于 C++ 上限）。
     */
    const val MAX_FEATURES_PER_LAYER = 0

    // ═══════════════════════ Phase 3：预加载缓冲 ═══════════════════════

    /**
     * computeVisibleExtent 的缓冲系数：dLat/dLon 乘以此值。
     * - 1.0：无额外缓冲（当前行为，屏幕 box = 相机高度等值换算）
     * - 1.5：缓冲 50%（减少小幅平移重载频率，代价是每次多加载 ~2.25x 要素）
     * - 2.0：缓冲 100%（大幅减少重载，但要素数可能触发截断）
     *
     * 建议：Phase 1 启用后先保持 1.0（swap-on-ready 已消除空窗），
     * 若仍频繁重载影响性能再提到 1.5。
     */
    const val EXTENT_BUFFER_FACTOR = 1.0

    // ═══════════════════════ Phase 4：流式 JSON 解析 ═══════════════════════

    /**
     * Kotlin 端用 android.util.JsonReader 流式解析替代 JSONObject 树形解析。
     * - true：流式解析（预期 2.38s → ~0.5s，内存峰值更低）
     * - false：沿用 JSONObject（当前行为）
     *
     * 注：流式解析代码路径独立，不影响 C++ 端 JSON 输出格式。
     */
    const val USE_STREAMING_JSON = false

    // ═══════════════════════ Phase 5：几何简化（Douglas-Peucker）═══════════════════════

    /**
     * 图层几何简化开关（C++ 端 OGRGeometry::Simplify）。
     * - true：重投影到 WGS84 后按 [SIMPLIFY_TOLERANCE_DEGREES] 简化顶点，
     *        减少 JSON 体积与 Kotlin 解析/原底层库渲染开销（视觉细节轻微损失）
     * - false：不简化，保留原始几何精度（默认）
     *
     * 注：与 C++ 已有的亚像素预简化（g_vertexTolerance 同点去重）正交：
     * 预简化仅删重复点，本开关调用 Douglas-Peucker 删除形状贡献小的中间顶点。
     */
    const val SIMPLIFY_GEOMETRY = false

    /**
     * 几何简化容差（WGS84 度）。仅在 [SIMPLIFY_GEOMETRY] = true 时生效。
     * - 1e-5（~1m）：几乎无损，仅去除极密集顶点
     * - 5e-5（~5m）：轻度简化，视野内视觉上难分辨
     * - 1e-4（~10m）：中度简化，适合小比例尺浏览
     */
    const val SIMPLIFY_TOLERANCE_DEGREES = 1e-5
}
