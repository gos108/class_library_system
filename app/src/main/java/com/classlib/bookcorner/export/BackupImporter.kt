package com.classlib.bookcorner.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.classlib.bookcorner.App
import com.classlib.bookcorner.db.ReportDao
import com.classlib.bookcorner.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 从 backup.json 恢复整库。
 *
 * 语义是「恢复到那一刻」，不是合并。合并要回答「同 id 不同内容怎么办」，
 * 而这是一个一次换届才用一次的动作，不值得为此背一套冲突规则。
 *
 * 三条硬约束：
 *  1. 先校验再落库。宁可拒绝，也不要导进一个半坏的库。
 *  2. 全程一个事务，任何一步失败都整体回滚，不会留下删了一半的数据。
 *  3. 动手前把当前库存一份快照，选错文件还能回头。
 */
class BackupImporter(private val ctx: Context) {

    class Preview(
        val exportedAt: String,
        val classCode: Int,
        val loanDays: Int,
        val members: Int,
        val books: Int,
        val copies: Int,
        val loans: Int,
        val opLogs: Int,
        internal val root: JSONObject
    )

    class Result(val summary: String, val safetyFile: File)

    /** 只解析与校验，不碰数据库。让用户先看清要恢复到什么时候。 */
    fun inspect(json: String): Preview {
        val root = try {
            JSONObject(json)
        } catch (e: Throwable) {
            throw IllegalArgumentException("不是合法的 JSON 文件")
        }

        val schema = root.optInt("schema", 0)
        if (schema != SCHEMA) {
            throw IllegalArgumentException("备份格式版本不支持（schema=$schema，本程序支持 $SCHEMA）")
        }

        val tables = root.optJSONObject("tables")
            ?: throw IllegalArgumentException("文件里没有 tables，这可能不是本程序导出的备份")

        val member = tables.optJSONArray("member") ?: throw IllegalArgumentException("备份缺少 member（名册）")
        val book = tables.optJSONArray("book") ?: throw IllegalArgumentException("备份缺少 book（书目）")
        val copy = tables.optJSONArray("copy") ?: throw IllegalArgumentException("备份缺少 copy（册）")
        val loan = tables.optJSONArray("loan") ?: throw IllegalArgumentException("备份缺少 loan（借阅）")
        val opLog = tables.optJSONArray("op_log") ?: JSONArray()

        val bookIds = idsOf(book)
        val memberIds = idsOf(member)
        val copyIds = idsOf(copy)

        // 空备份一旦恢复就等于清库，没有任何正当用途。
        // 这条是实测踩出来的：一个 {"tables":{...都是空数组}} 的文件原本会被放行。
        if (copy.length() == 0 && member.length() == 0) {
            throw IllegalArgumentException("这个备份是空的（0 册、0 人）。恢复它会清空手表上的全部数据，已拒绝。")
        }

        for (i in 0 until copy.length()) {
            val c = copy.getJSONObject(i)
            val bookId = c.optLong("book_id", -1)
            if (bookId !in bookIds) {
                throw IllegalArgumentException("第 ${i + 1} 册指向不存在的书目 id=$bookId")
            }
            if (!c.isNull("owner_id") && c.optLong("owner_id", 0) !in memberIds) {
                throw IllegalArgumentException("第 ${i + 1} 册的所有者 id=${c.optLong("owner_id")} 不在名册里")
            }
        }

        val activeCopies = HashSet<Long>()
        for (i in 0 until loan.length()) {
            val l = loan.getJSONObject(i)
            val copyId = l.optLong("copy_id", -1)
            if (copyId !in copyIds) {
                throw IllegalArgumentException("第 ${i + 1} 条借阅指向不存在的册 id=$copyId")
            }
            if (l.optLong("member_id", -1) !in memberIds) {
                throw IllegalArgumentException("第 ${i + 1} 条借阅的借阅人 id=${l.optLong("member_id")} 不在名册里")
            }
            // 一册同时两条在借：库里的部分唯一索引也会拦，但说清楚更好排查
            if (l.optString("status") == "active" && !activeCopies.add(copyId)) {
                throw IllegalArgumentException("同一册有两条在借记录（copy_id=$copyId），文件已损坏")
            }
        }

        return Preview(
            exportedAt = root.optString("exported_at", "未知"),
            classCode = root.optInt("class_code", 3),
            loanDays = root.optInt("loan_days", 14),
            members = member.length(),
            books = book.length(),
            copies = copy.length(),
            loans = loan.length(),
            opLogs = opLog.length(),
            root = root
        )
    }

    /** 真正写库。调用前必须先 inspect() 通过。 */
    fun apply(preview: Preview): Result {
        val db = App.db
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(java.util.Date())

        // 1) 先给当前库留一份，免得选错文件后无可挽回。只留最近 2 份。
        val dir = File(ctx.filesDir, "restore_safety").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP_SAFETY)?.forEach { it.delete() }
        val safety = File(dir, "before_restore_$stamp.json")
        safety.writeText(
            ReportDao(db).dumpJson(
                linkedMapOf(
                    "app" to "班级图书角",
                    "schema" to SCHEMA,
                    "reason" to "恢复备份前的自动快照",
                    "exported_at_ms" to System.currentTimeMillis()
                )
            ),
            Charsets.UTF_8
        )

        // 2) 一个事务换掉全部数据
        val tables = preview.root.getJSONObject("tables")
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM loan")
            db.execSQL("DELETE FROM copy")
            db.execSQL("DELETE FROM book")
            db.execSQL("DELETE FROM member")
            db.execSQL("DELETE FROM op_log")

            insertAll(db, "member", tables.optJSONArray("member"))
            insertAll(db, "book", tables.optJSONArray("book"))
            insertAll(db, "copy", tables.optJSONArray("copy"))
            insertAll(db, "loan", tables.optJSONArray("loan"))
            tables.optJSONArray("op_log")?.let { insertAll(db, "op_log", it) }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // 3) 配置跟着回来，否则班级码变了以后新入库的书条码会和旧的对不上
        Prefs(ctx).apply {
            classCode = preview.classCode
            loanDays = preview.loanDays
        }

        return Result(
            "已恢复 ${preview.copies} 册 / ${preview.members} 人 / ${preview.loans} 条借阅",
            safety
        )
    }

    private fun insertAll(db: SQLiteDatabase, table: String, rows: JSONArray?) {
        if (rows == null) return
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val cv = android.content.ContentValues()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = o.get(k)) {
                    is Int -> cv.put(k, v)
                    is Long -> cv.put(k, v)
                    is Double -> cv.put(k, v)
                    is Boolean -> cv.put(k, if (v) 1 else 0)
                    JSONObject.NULL -> cv.putNull(k)
                    else -> cv.put(k, v.toString())
                }
            }
            db.insertOrThrow(table, null, cv)
        }
    }

    private fun idsOf(rows: JSONArray): Set<Long> {
        val set = HashSet<Long>(rows.length() * 2)
        for (i in 0 until rows.length()) set.add(rows.getJSONObject(i).optLong("id", -1))
        return set
    }

    companion object {
        const val SCHEMA = 1
        private const val KEEP_SAFETY = 2
    }
}
