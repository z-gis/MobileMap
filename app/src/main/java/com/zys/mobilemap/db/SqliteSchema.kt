package com.zys.mobilemap.db

import android.database.sqlite.SQLiteDatabase

/**
 * SQLite 表结构工具：旧库非破坏式迁移——先经 `PRAGMA table_info` 检测缺列，
 * 再 `ALTER TABLE ADD COLUMN` 补列，不重建表、不丢弃既有数据。
 * 地图数据库（map.db，[MapDataStore]）与媒体数据库（media.db，
 * [com.zys.mobilemap.media.MediaStore]）共用同一套迁移逻辑。
 */
object SqliteSchema {

    /** 读取指定表的全部列名；表不存在时返回空集（调用方按“缺列”处理） */
    fun tableColumns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            if (nameIdx >= 0) {
                while (c.moveToNext()) {
                    columns.add(c.getString(nameIdx))
                }
            }
            columns
        }

    /** 表缺少 [column] 列时执行 [alterSql] 补列（每张表一次 PRAGMA 检测） */
    fun ensureColumn(db: SQLiteDatabase, table: String, column: String, alterSql: String) {
        if (column !in tableColumns(db, table)) {
            db.execSQL(alterSql)
        }
    }
}
