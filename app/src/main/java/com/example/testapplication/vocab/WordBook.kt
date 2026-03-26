package com.example.testapplication.vocab

/**
 * 词书定义。id 对应 roadmap/road_map_{id}.baicizhan 的文件名数字。
 * 如需确认四级/六级的真实 ID，查看手机：
 *   /sdcard/Android/data/com.jiongji.andriod.card/files/baicizhan/roadmap/
 */
enum class WordBook(val id: Int, val displayName: String) {
    GAOKAO(409, "高考"),
    CET4(408, "四级"),   // 如不对请改成实际 ID
    CET6(410, "六级");   // 如不对请改成实际 ID

    val roadmapFileName get() = "road_map_$id.baicizhan"
    val statusTableName  get() = "ts_learn_offline_dotopic_sync_ids_$id"
}
