package com.zys.mobilemap.doc

import org.json.JSONObject

/** 图层分组记录：组可见性不存盘，由组内成员 visible 派生（allVisible），开关即扇出写成员 */
class LayerGroup(val id: Int, var name: String) {

    fun toJson(): JSONObject = JSONObject().also {
        it.put("id", id)
        it.put("name", name)
    }

    companion object {
        fun fromJson(o: JSONObject): LayerGroup = LayerGroup(o.optInt("id", 0), o.optString("name", ""))
    }
}