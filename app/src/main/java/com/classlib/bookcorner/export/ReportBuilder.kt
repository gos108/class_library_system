package com.classlib.bookcorner.export

import android.content.Context
import com.classlib.bookcorner.App
import com.classlib.bookcorner.db.ReportDao
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.util.Dates
import com.classlib.bookcorner.util.Prefs
import com.classlib.bookcorner.util.XlsxWriter
import java.io.File

/**
 * 把库里的数据拼成 4 个 sheet 的 xlsx，外加一份可重建的 backup.json。
 *
 * 两个文件分工明确：
 *  - xlsx  给人看（老师要的就是这个）
 *  - json  给程序看（真正的备份，含名册、配置、操作流水）
 */
class ReportBuilder(private val ctx: Context) {

    class Output(val xlsx: File, val json: File, val total: Int)

    fun build(): Output {
        val dao = ReportDao(App.db)
        val now = System.currentTimeMillis()

        val books = dao.books()
        val loans = dao.loans()
        val counts = dao.counts()
        val owners = dao.ownerDistribution()
        val idlers = dao.idlers()
        val members = dao.members()

        // ---- Sheet 1 书籍清单 ----
        val s1 = ArrayList<List<Any?>>()
        s1.add(listOf("序号", "书名", "作者", "ISBN", "分类", "条码", "所有者", "状态", "入库日期", "出库原因"))
        books.forEachIndexed { i, b ->
            s1.add(
                listOf(
                    i + 1, b.title, b.author ?: "", b.isbn ?: "", b.category, b.barcode,
                    b.owner ?: "", Copy.label(b.status), Dates.ymd(b.inAt), b.removedReason ?: ""
                )
            )
        }

        // ---- Sheet 2 借还记录 ----
        val s2 = ArrayList<List<Any?>>()
        s2.add(listOf("序号", "书名", "条码", "借出人", "借出日期", "应还日期", "归还日期", "状态"))
        loans.forEachIndexed { i, l ->
            val st = when {
                l.status == "returned" -> "已还"
                l.status == "lost" -> "遗失"
                l.dueAt < now -> "超期未还"
                else -> "未还"
            }
            s2.add(
                listOf(
                    i + 1, l.title, l.barcode, l.member,
                    Dates.ymd(l.borrowedAt), Dates.ymd(l.dueAt),
                    Dates.ymdOrEmpty(l.returnedAt), st
                )
            )
        }

        // ---- Sheet 3 概览 ----
        val s3 = ArrayList<List<Any?>>()
        s3.add(listOf("导出时间", Dates.ymd(now)))
        s3.add(emptyList())
        s3.add(listOf("总量"))
        s3.add(listOf("总册数", counts["total"] ?: 0))
        s3.add(listOf("在库（可借）", counts[Copy.STATUS_IN] ?: 0))
        s3.add(listOf("借出", counts[Copy.STATUS_OUT] ?: 0))
        s3.add(listOf("遗失", counts[Copy.STATUS_LOST] ?: 0))
        s3.add(listOf("出库", counts[Copy.STATUS_REMOVED] ?: 0))
        s3.add(listOf("书目种类", counts["books"] ?: 0))
        s3.add(listOf("在册成员", counts["members"] ?: 0))
        s3.add(emptyList())

        s3.add(listOf("超期未还"))
        s3.add(listOf("姓名", "书名", "应还日期", "逾期天数"))
        loans.filter { it.status == "active" && it.dueAt < now }.forEach { l ->
            val days = (now - l.dueAt) / (24L * 60 * 60 * 1000)
            s3.add(listOf(l.member, l.title, Dates.ymd(l.dueAt), days.coerceAtLeast(1)))
        }
        if (loans.none { it.status == "active" && it.dueAt < now }) s3.add(listOf("（无）"))
        s3.add(emptyList())

        s3.add(listOf("所有者分布"))
        s3.add(listOf("姓名", "贡献册数"))
        owners.forEach { s3.add(listOf(it.name, it.count)) }
        if (owners.isEmpty()) s3.add(listOf("（尚未登记所有者）"))
        s3.add(emptyList())

        s3.add(listOf("从未被借出的书"))
        s3.add(listOf("书名"))
        idlers.forEach { s3.add(listOf(it)) }
        if (idlers.isEmpty()) s3.add(listOf("（无）"))

        // ---- Sheet 4 名册 ----
        val s4 = ArrayList<List<Any?>>()
        s4.add(listOf("姓名", "拼音", "首字母", "角色", "状态", "带书册数", "借阅次数"))
        members.forEach { m ->
            s4.add(
                listOf(
                    m.name, m.pinyin, m.initials, roleLabel(m.role),
                    if (m.status == "active") "在册" else "已转出",
                    m.bookCount, m.loanCount
                )
            )
        }

        val sheets = listOf(
            XlsxWriter.Sheet("书籍清单", s1),
            XlsxWriter.Sheet("借还记录", s2),
            XlsxWriter.Sheet("概览", s3),
            XlsxWriter.Sheet("名册", s4)
        )

        val dir = File(ctx.cacheDir, "report").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // 只保留最近一次
        val stamp = stamp()

        val xlsx = File(dir, "bookcorner_$stamp.xlsx")
        xlsx.outputStream().use { XlsxWriter.write(it, sheets) }

        val json = File(dir, "bookcorner_backup_$stamp.json")
        json.writeText(dao.dumpJson(meta(now)), Charsets.UTF_8)

        return Output(xlsx, json, counts["total"] ?: 0)
    }

    private fun meta(now: Long): Map<String, Any?> {
        val p = Prefs(ctx)
        return linkedMapOf(
            "app" to "班级图书角",
            "schema" to 1,
            "exported_at" to Dates.ymd(now),
            "exported_at_ms" to now,
            "class_code" to p.classCode,
            "loan_days" to p.loanDays,
            "categories" to Prefs.CATEGORIES.joinToString(",")
        )
    }

    private fun roleLabel(role: String): String = when (role) {
        "librarian" -> "图书管理员"
        "teacher" -> "老师"
        else -> "学生"
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.CHINA)
            .format(java.util.Date())
}
