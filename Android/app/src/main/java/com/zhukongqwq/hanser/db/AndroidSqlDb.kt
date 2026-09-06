package com.zhukongqwq.hanser.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.zhukongqwq.hanser.core.SqlDb
import java.io.File

/**
 * Android 端 SqlDb 实现（基于 android.database.sqlite）。
 * 数据库文件由调用方指定（应用私有目录下的 documents.db）。
 */
class AndroidSqlDb(file: File) : SqlDb {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(file, null)

    override fun query(sql: String, args: List<Any>): List<SqlDb.Row> {
        val cursor: Cursor = db.rawQuery(sql, args.map { it.toString() }.toTypedArray())
        return try {
            val rows = ArrayList<SqlDb.Row>()
            while (cursor.moveToNext()) rows.add(AndroidRow(cursor))
            rows
        } finally {
            cursor.close()
        }
    }

    override fun exec(sql: String, args: List<Any>): Int {
        db.execSQL(sql, args.toTypedArray())
        return 0
    }

    override fun execDdl(sql: String) {
        db.execSQL(sql)
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        return try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        db.close()
    }

    /**
     * 物化行：构造时（cursor 位于当前行）把整行列值读入 Map。
     * 彻底避免「延迟读 cursor」引发的行位置错乱（CursorIndexOutOfBoundsException）。
     */
    private class AndroidRow(cursor: Cursor) : SqlDb.Row {
        private val values: Map<String, Any?> = run {
            val m = HashMap<String, Any?>()
            val names = cursor.columnNames
            for (i in names.indices) {
                m[names[i].lowercase()] = when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                    else -> cursor.getString(i)
                }
            }
            m
        }

        private fun v(col: String): Any? = values[col.lowercase()]

        override fun getString(col: String): String = v(col)?.toString() ?: ""
        override fun getLong(col: String): Long = (v(col) as? Number)?.toLong() ?: 0L
        override fun getDouble(col: String): Double = (v(col) as? Number)?.toDouble() ?: 0.0
        override fun get(col: String): Any? = v(col)
        override fun columnNames(): Set<String> = values.keys
    }
}
