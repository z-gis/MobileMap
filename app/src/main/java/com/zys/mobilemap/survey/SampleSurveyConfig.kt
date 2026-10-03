package com.zys.mobilemap.survey

/**
 * 样地调查配置常量：树种列表、抚育方式等下拉选项。
 */
object SampleSurveyConfig {

    /** 可选树种列表 */
    val TREE_SPECIES = listOf("油松", "樟子松", "落叶松", "杨树", "白桦", "蒙古栎", "其他")

    /** 抚育方式列表（首项为空表示未选择） */
    val THINNING_METHODS = listOf("", "采伐", "枯死", "修枝", "间伐", "卫生伐")

    /** 分类列表 */
    val CATEGORIES = listOf("", "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ")
}
