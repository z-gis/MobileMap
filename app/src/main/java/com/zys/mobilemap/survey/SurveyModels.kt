package com.zys.mobilemap.survey

/**
 * 样地调查数据模型。
 */

/** 样地主记录 */
data class SurveyPlot(
    var id: Long = 0L,
    var sourcePath: String = "",
    var sourceFid: Long = 0L,
    var compartmentNo: String? = null,
    var plotNo: Int = 1,
    var averageTreeHeight: String? = null,
    var canopyDensity: Double = Double.NaN,
    var cornerCoordinates: String? = null,
    var centerLatitude: Double = Double.NaN,
    var centerLongitude: Double = Double.NaN,
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    val rows: MutableList<SurveyRow> = mutableListOf()
)

/** 样地调查明细行 */
data class SurveyRow(
    var id: Long = 0L,
    var sortNo: Int = 0,
    var treeSpecies: String? = null,
    var dbh: String? = null,
    var category: String? = null,
    var thinningMethod: String? = null,
    var remark: String? = null
)

/** 样地照片记录 */
data class SurveyPhoto(
    val id: Long = 0L,
    val plotId: Long = 0L,
    val filePath: String = "",
    val capturedAt: Long = 0L
)
