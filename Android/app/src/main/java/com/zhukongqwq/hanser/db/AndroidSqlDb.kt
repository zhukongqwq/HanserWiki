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

    private class AndroidRow(private val c: Cursor) : SqlDb.Row {
        private val names: Map<String, Int> =
            c.columnNames.mapIndexed { i, n -> n.lowercase() to i }.toMap()

        private fun idx(col: String): Int = names[col.lowercase()] ?: -1

        override fun getString(col: String): String {
            val i = idx(col)
            return if (i < 0) "" else c.getString(i) ?: ""
        }

        override fun getLong(col: String): Long {
            val i = idx(col)
            return if (i < 0) 0 else c.getLong(i)
        }

        override fun getDouble(col: String): Double {
            val i = idx(col)
            return if (i < 0) 0.0 else c.getDouble(i)
        }

        override fun get(col: String): Any? {
            val i = idx(col)
            return if (i < 0) null else when (c.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                Cursor.FIELD_TYPE_NULL -> null
                else -> c.getString(i)
            }
        }

        override fun columnNames(): Set<String> = names.keys
    }
}
