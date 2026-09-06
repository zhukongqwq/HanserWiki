package com.zhukongqwq.hanser.core

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * 本地 JVM 单测用 SqlDb 实现（sqlite-jdbc），使索引/检索核心逻辑
 * 无需 Android 运行时即可验证。
 */
class JdbcSqlDb(jdbcUrl: String = "jdbc:sqlite::memory:") : SqlDb {

    private val conn: Connection = DriverManager.getConnection(jdbcUrl).also {
        it.createStatement().use { s -> s.execute("PRAGMA foreign_keys = OFF") }
    }

    override fun query(sql: String, args: List<Any>): List<SqlDb.Row> {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val rows = ArrayList<SqlDb.Row>()
                val md = rs.metaData
                val labels = ArrayList<String>()
                for (i in 1..md.columnCount) labels.add(md.getColumnLabel(i))
                while (rs.next()) {
                    // 物化当前行（rs 关闭后仍可读）
                    val values = LinkedHashMap<String, Any?>()
                    labels.forEachIndexed { idx, label -> values[label] = rs.getObject(idx + 1) }
                    rows.add(MaterializedRow(values))
                }
                return rows
            }
        }
    }

    override fun exec(sql: String, args: List<Any>): Int {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            return ps.executeUpdate()
        }
    }

    override fun execDdl(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    override fun <T> transaction(block: () -> T): T {
        val prev = conn.autoCommit
        conn.autoCommit = false
        return try {
            val r = block()
            conn.commit()
            r
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = prev
        }
    }

    override fun close() {
        conn.close()
    }

    private fun bind(ps: java.sql.PreparedStatement, args: List<Any>) {
        args.forEachIndexed { i, v ->
            when (v) {
                is Long -> ps.setLong(i + 1, v)
                is Int -> ps.setLong(i + 1, v.toLong())
                is Double -> ps.setDouble(i + 1, v)
                is Boolean -> ps.setInt(i + 1, if (v) 1 else 0)
                null -> ps.setNull(i + 1, java.sql.Types.NULL)
                else -> ps.setString(i + 1, v.toString())
            }
        }
    }

    /** 已物化的静态行。 */
    private class MaterializedRow(private val values: Map<String, Any?>) : SqlDb.Row {
        private val lower: Map<String, Any?> =
            values.entries.associate { (k, v) -> k.lowercase() to v }

        private fun v(col: String): Any? = lower[col.lowercase()]

        override fun getString(col: String): String = v(col)?.toString() ?: ""
        override fun getLong(col: String): Long = (v(col) as? Number)?.toLong() ?: 0L
        override fun getDouble(col: String): Double = (v(col) as? Number)?.toDouble() ?: 0.0
        override fun get(col: String): Any? = v(col)
        override fun columnNames(): Set<String> = lower.keys
    }
}
