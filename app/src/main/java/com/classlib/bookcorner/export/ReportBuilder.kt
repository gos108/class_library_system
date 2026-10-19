package com.classlib.bookcorner.export

import android.content.Context
import com.classlib.bookcorner.App
import com.classlib.bookcorner.db.ReportDao
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.util.Dates
import com.classlib.bookcorner.util.XlsxWriter
import java.io.File

/** 把库里的数据拼成 3 个 sheet 的 xlsx。 */
class ReportBuilder(private val ctx: Context) {

    fun build(): File {
        val dao = ReportDao(App.db)
        val now = System.currentTimeMillis()

        val books = dao.books()
        val loans = dao.loans()
        val counts = dao.counts()
        val owners = dao.ownerDistribution()
        val idlers = dao.idlers()

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

        val sheets = listOf(
            XlsxWriter.Sheet("书籍清单", s1),
            XlsxWriter.Sheet("借还记录", s2),
            XlsxWriter.Sheet("概览", s3)
        )

        val dir = File(ctx.cacheDir, "report").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // 只保留最近一次
        val file = File(dir, "bookcorner_${stamp()}.xlsx")
        file.outputStream().use { XlsxWriter.write(it, sheets) }
        return file
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.CHINA)
            .format(java.util.Date())
}
