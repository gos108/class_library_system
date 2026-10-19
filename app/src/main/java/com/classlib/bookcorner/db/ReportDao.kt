package com.classlib.bookcorner.db

import android.database.sqlite.SQLiteDatabase
import com.classlib.bookcorner.model.Copy

/** 报表导出用的只读查询。 */
class ReportDao(private val db: SQLiteDatabase) {

    data class BookRow(
        val barcode: String,
        val title: String,
        val author: String?,
        val isbn: String?,
        val category: String,
        val owner: String?,
        val status: String,
        val inAt: Long,
        val removedReason: String?
    )

    data class LoanRow(
        val title: String,
        val barcode: String,
        val member: String,
        val borrowedAt: Long,
        val dueAt: Long,
        val returnedAt: Long?,
        val status: String
    )

    data class OwnerCount(val name: String, val count: Int)

    data class OverdueRow(val member: String, val title: String, val dueAt: Long, val days: Long)

    fun books(): List<BookRow> {
        val sql = """
            SELECT c.barcode, b.title, b.author, b.isbn, b.category,
                   mo.name AS owner, c.status, c.in_at, c.removed_reason
              FROM copy c
              JOIN book b ON b.id = c.book_id
              LEFT JOIN member mo ON mo.id = c.owner_id
             ORDER BY c.id
        """.trimIndent()
        val out = ArrayList<BookRow>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    BookRow(
                        barcode = c.getString(0),
                        title = c.getString(1),
                        author = if (c.isNull(2)) null else c.getString(2),
                        isbn = if (c.isNull(3)) null else c.getString(3),
                        category = c.getString(4),
                        owner = if (c.isNull(5)) null else c.getString(5),
                        status = c.getString(6),
                        inAt = c.getLong(7),
                        removedReason = if (c.isNull(8)) null else c.getString(8)
                    )
                )
            }
        }
        return out
    }

    fun loans(): List<LoanRow> {
        val sql = """
            SELECT b.title, c.barcode, m.name, l.borrowed_at, l.due_at, l.returned_at, l.status
              FROM loan l
              JOIN copy c ON c.id = l.copy_id
              JOIN book b ON b.id = c.book_id
              JOIN member m ON m.id = l.member_id
             ORDER BY l.borrowed_at DESC
        """.trimIndent()
        val out = ArrayList<LoanRow>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LoanRow(
                        title = c.getString(0),
                        barcode = c.getString(1),
                        member = c.getString(2),
                        borrowedAt = c.getLong(3),
                        dueAt = c.getLong(4),
                        returnedAt = if (c.isNull(5)) null else c.getLong(5),
                        status = c.getString(6)
                    )
                )
            }
        }
        return out
    }

    fun ownerDistribution(): List<OwnerCount> {
        val sql = """
            SELECT mo.name, COUNT(c.id)
              FROM copy c
              JOIN member mo ON mo.id = c.owner_id
             GROUP BY mo.id
             ORDER BY COUNT(c.id) DESC, mo.name
        """.trimIndent()
        val out = ArrayList<OwnerCount>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) out.add(OwnerCount(c.getString(0), c.getInt(1)))
        }
        return out
    }

    fun counts(): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        db.rawQuery("SELECT COUNT(*) FROM copy", null).use { if (it.moveToFirst()) map["total"] = it.getInt(0) }
        Copy.ALL.forEach { st ->
            db.rawQuery("SELECT COUNT(*) FROM copy WHERE status=?", arrayOf(st)).use {
                if (it.moveToFirst()) map[st] = it.getInt(0)
            }
        }
        db.rawQuery("SELECT COUNT(*) FROM member WHERE status='active'", null).use {
            if (it.moveToFirst()) map["members"] = it.getInt(0)
        }
        db.rawQuery("SELECT COUNT(*) FROM book", null).use { if (it.moveToFirst()) map["books"] = it.getInt(0) }
        return map
    }

    fun idlers(): List<String> {
        val sql = """
            SELECT b.title
              FROM copy c JOIN book b ON b.id = c.book_id
             WHERE c.id NOT IN (SELECT copy_id FROM loan)
             ORDER BY b.title
        """.trimIndent()
        val out = ArrayList<String>()
        db.rawQuery(sql, null).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }
}
