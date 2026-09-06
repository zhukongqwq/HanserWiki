package com.zhukongqwq.hanser.core

/**
 * SQL 访问抽象：Android 端用 SQLiteDatabase，本地 JVM 单测用 sqlite-jdbc 实现，
 * 使索引/检索核心逻辑可在纯 JVM 上验证（与 Python/Windows 共享相同 SQL 语义）。
 */
interface SqlDb {

    interface Row {
        fun getString(col: String): String
        fun getLong(col: String): Long
        fun getDouble(col: String): Double
        fun get(col: String): Any?
        fun columnNames(): Set<String>
    }

    /** 返回多行结果（列名不区分大小写匹配）。 */
    fun query(sql: String, args: List<Any> = emptyList()): List<Row>

    /** 执行写语句，返回受影响行数。 */
    fun exec(sql: String, args: List<Any> = emptyList()): Int

    /** 执行 DDL / 建表。 */
    fun execDdl(sql: String)

    /** 事务包裹。 */
    fun <T> transaction(block: () -> T): T

    fun close()
}
