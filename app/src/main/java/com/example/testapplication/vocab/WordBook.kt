package com.example.testapplication.vocab

/**
 * 词书定义。id 对应 roadmap/road_map_{id}.baicizhan 的文件名数字。
 * 如需确认四级/六级的真实 ID，查看手机：
 *   /sdcard/Android/data/com.jiongji.andriod.card/files/baicizhan/roadmap/
 */
enum class WordBook(val id: Int, val displayName: String) {
    // 顺序即优先级（前者优先），用于跨词书去重/过滤
    ZHONGKAO(410, "中考"),
    GAOKAO(409, "高考"),
    CET4(575, "四级"),
    CET6(565, "六级"),
    YASI(621, "雅思");

    val roadmapFileName get() = "road_map_$id.baicizhan"
    val statusTableName  get() = "ts_learn_offline_dotopic_sync_ids_$id"
}
