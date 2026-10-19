package com.classlib.bookcorner.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.classlib.bookcorner.model.Book
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.model.CopyView
import com.classlib.bookcorner.model.Loan
import com.classlib.bookcorner.model.LoanView
import com.classlib.bookcorner.model.Member
import com.classlib.bookcorner.util.BarcodeUtil

/* ------------------------------------------------------------------ */
/* 名册                                                                */
/* ------------------------------------------------------------------ */
class MemberDao(private val db: SQLiteDatabase) {

    fun insert(m: Member): Long {
        val cv = ContentValues().apply {
            put("name", m.name)
            put("pinyin", m.pinyin)
            put("initials", m.initials)
            put("role", m.role)
            put("status", m.status)
            put("sort_no", m.sortNo)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("member", null, cv)
    }

    fun insertAll(list: List<Member>) {
        db.beginTransaction()
        try {
            list.forEach { insert(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listActive(): List<Member> = query("status='active' ORDER BY sort_no, id")

    fun listAll(): List<Member> = query("1=1 ORDER BY sort_no, id")

    fun findById(id: Long): Member? = query("id=$id").firstOrNull()

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM member", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun deleteAll() = db.delete("member", null, null)

    private fun query(where: String): List<Member> {
        val out = ArrayList<Member>()
        db.rawQuery("SELECT id,name,pinyin,initials,role,status,sort_no FROM member WHERE $where", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Member(
                        id = c.getLong(0),
                        name = c.getString(1),
                        pinyin = c.getString(2),
                        initials = c.getString(3),
                        role = c.getString(4),
                        status = c.getString(5),
                        sortNo = c.getInt(6)
                    )
                )
            }
        }
        return out
    }
}

/* ------------------------------------------------------------------ */
/* 书目                                                                */
/* ------------------------------------------------------------------ */
class BookDao(private val db: SQLiteDatabase) {

    fun insert(b: Book): Long {
        val cv = ContentValues().apply {
            put("isbn", b.isbn)
            put("title", b.title)
            put("author", b.author)
            put("publisher", b.publisher)
            put("category", b.category)
            put("source", b.source)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("book", null, cv)
    }

    fun findById(id: Long): Book? {
        db.rawQuery("SELECT id,isbn,title,author,publisher,category,source FROM book WHERE id=$id", null).use { c ->
            return if (c.moveToFirst()) c.toBook() else null
        }
    }

    fun findByIsbn(isbn: String): Book? {
        db.rawQuery(
            "SELECT id,isbn,title,author,publisher,category,source FROM book WHERE isbn=? LIMIT 1",
            arrayOf(isbn)
        ).use { c ->
            return if (c.moveToFirst()) c.toBook() else null
        }
    }

    private fun Cursor.toBook() = Book(
        id = getLong(0),
        isbn = if (isNull(1)) null else getString(1),
        title = getString(2),
        author = if (isNull(3)) null else getString(3),
        publisher = if (isNull(4)) null else getString(4),
        category = getString(5),
        source = getString(6)
    )
}

/* ------------------------------------------------------------------ */
/* 册                                                                  */
/* ------------------------------------------------------------------ */
class CopyDao(private val db: SQLiteDatabase) {

    /**
     * 建一册。条码需要用到自增 id，所以先插临时条码，拿到 id 后回填。
     * 全程一个事务。
     */
    fun insert(bookId: Long, ownerId: Long?, categoryCode: Int, classCode: Int, shelf: String? = null): Copy {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("book_id", bookId)
                put("barcode", "TMP" + System.nanoTime())
                put("status", Copy.STATUS_IN)
                put("owner_id", ownerId)
                put("shelf", shelf)
                put("in_at", now)
                put("version", 0)
            }
            val id = db.insert("copy", null, cv)
            if (id <= 0) throw IllegalStateException("插入副本失败")

            val barcode = BarcodeUtil.make(classCode, categoryCode, id)
            db.execSQL("UPDATE copy SET barcode=? WHERE id=?", arrayOf(barcode, id))
            log("copy.create", "copy", id, null, Copy.STATUS_IN, null)

            db.setTransactionSuccessful()
            return Copy(
                id = id, bookId = bookId, barcode = barcode,
                status = Copy.STATUS_IN, ownerId = ownerId,
                shelf = shelf, inAt = now
            )
        } finally {
            db.endTransaction()
        }
    }

    fun findByBarcode(barcode: String): Copy? {
        db.rawQuery(
            "SELECT id,book_id,barcode,status,owner_id,shelf,in_at,removed_reason,version FROM copy WHERE barcode=?",
            arrayOf(barcode)
        ).use { c ->
            return if (c.moveToFirst()) Copy(
                id = c.getLong(0),
                bookId = c.getLong(1),
                barcode = c.getString(2),
                status = c.getString(3),
                ownerId = if (c.isNull(4)) null else c.getLong(4),
                shelf = if (c.isNull(5)) null else c.getString(5),
                inAt = c.getLong(6),
                removedReason = if (c.isNull(7)) null else c.getString(7),
                version = c.getInt(8)
            ) else null
        }
    }

    /** 查书：联表拿书名、所有者、当前借阅人 */
    fun viewByBarcode(barcode: String): CopyView? {
        val sql = """
            SELECT c.id, c.barcode, b.title, b.author, b.category, c.status,
                   mo.name AS owner_name, c.shelf,
                   ml.name AS borrower_name, l.due_at
              FROM copy c
              JOIN book b ON b.id = c.book_id
              LEFT JOIN member mo ON mo.id = c.owner_id
              LEFT JOIN loan l ON l.copy_id = c.id AND l.status = 'active'
              LEFT JOIN member ml ON ml.id = l.member_id
             WHERE c.barcode = ?
        """.trimIndent()
        db.rawQuery(sql, arrayOf(barcode)).use { c ->
            return if (c.moveToFirst()) CopyView(
                copyId = c.getLong(0),
                barcode = c.getString(1),
                title = c.getString(2),
                author = if (c.isNull(3)) null else c.getString(3),
                category = c.getString(4),
                status = c.getString(5),
                ownerName = if (c.isNull(6)) null else c.getString(6),
                shelf = if (c.isNull(7)) null else c.getString(7),
                borrowerName = if (c.isNull(8)) null else c.getString(8),
                dueAt = if (c.isNull(9)) null else c.getLong(9)
            ) else null
        }
    }

    /** 改状态（带乐观锁），返回是否成功 */
    fun changeStatus(copyId: Long, from: String, to: String, reason: String? = null): Boolean {
        val cv = ContentValues().apply {
            put("status", to)
            put("version", versionOf(copyId) + 1)
            if (reason != null) put("removed_reason", reason)
        }
        val rows = db.update("copy", cv, "id=? AND status=?", arrayOf(copyId.toString(), from))
        return rows == 1
    }

    fun countByStatus(status: String): Int {
        db.rawQuery("SELECT COUNT(*) FROM copy WHERE status=?", arrayOf(status)).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun countAll(): Int {
        db.rawQuery("SELECT COUNT(*) FROM copy", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun versionOf(copyId: Long): Int {
        db.rawQuery("SELECT version FROM copy WHERE id=?", arrayOf(copyId.toString())).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun log(action: String, type: String, id: Long, from: String?, to: String?, note: String?) {
        db.execSQL(
            "INSERT INTO op_log(action,target_type,target_id,from_status,to_status,note,created_at) VALUES(?,?,?,?,?,?,?)",
            arrayOf(action, type, id, from, to, note, System.currentTimeMillis())
        )
    }
}

/* ------------------------------------------------------------------ */
/* 借还                                                                */
/* ------------------------------------------------------------------ */
class LoanDao(private val db: SQLiteDatabase) {

    /**
     * 借出。校验 + 建单 + 改状态，一个事务。
     * @throws IllegalStateException 带可展示的中文原因
     */
    fun borrow(copyId: Long, memberId: Long, operatorId: Long?, loanDays: Int): Loan {
        val now = System.currentTimeMillis()
        val due = now + loanDays * 24L * 60 * 60 * 1000
        db.beginTransaction()
        try {
            var status = ""
            var version = 0
            db.rawQuery("SELECT status,version FROM copy WHERE id=?", arrayOf(copyId.toString())).use {
                if (it.moveToFirst()) {
                    status = it.getString(0)
                    version = it.getInt(1)
                }
            }
            if (status != Copy.STATUS_IN) {
                throw IllegalStateException(
                    if (status == Copy.STATUS_OUT) "这本书已经借出去了" else "这本书当前状态是「${Copy.label(status)}」，不能借"
                )
            }

            val cv = ContentValues().apply {
                put("copy_id", copyId)
                put("member_id", memberId)
                put("borrowed_at", now)
                put("due_at", due)
                put("status", Loan.STATUS_ACTIVE)
                put("operator_id", operatorId)
            }
            val loanId = db.insert("loan", null, cv)
            if (loanId <= 0) throw IllegalStateException("建借阅单失败")

            val rows = db.update(
                "copy",
                ContentValues().apply {
                    put("status", Copy.STATUS_OUT)
                    put("version", version + 1)
                },
                "id=? AND version=?",
                arrayOf(copyId.toString(), version.toString())
            )
            if (rows != 1) throw IllegalStateException("这本书刚被别人借走了")

            db.execSQL(
                "INSERT INTO op_log(action,target_type,target_id,from_status,to_status,operator_id,created_at) VALUES(?,?,?,?,?,?,?)",
                arrayOf("loan.borrow", "copy", copyId, Copy.STATUS_IN, Copy.STATUS_OUT, operatorId, now)
            )
            db.setTransactionSuccessful()
            return Loan(loanId, copyId, memberId, now, due, null, Loan.STATUS_ACTIVE, operatorId)
        } finally {
            db.endTransaction()
        }
    }

    /** 归还 */
    fun giveBack(copyId: Long, operatorId: Long?): Boolean {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val rows = db.update(
                "loan",
                ContentValues().apply {
                    put("status", Loan.STATUS_RETURNED)
                    put("returned_at", now)
                },
                "copy_id=? AND status=?",
                arrayOf(copyId.toString(), Loan.STATUS_ACTIVE)
            )
            if (rows != 1) return false

            db.update(
                "copy",
                ContentValues().apply { put("status", Copy.STATUS_IN) },
                "id=?",
                arrayOf(copyId.toString())
            )
            db.execSQL(
                "INSERT INTO op_log(action,target_type,target_id,from_status,to_status,operator_id,created_at) VALUES(?,?,?,?,?,?,?)",
                arrayOf("loan.return", "copy", copyId, Copy.STATUS_OUT, Copy.STATUS_IN, operatorId, now)
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun activeByCopy(copyId: Long): Loan? {
        db.rawQuery(
            "SELECT id,copy_id,member_id,borrowed_at,due_at,status FROM loan WHERE copy_id=? AND status='active'",
            arrayOf(copyId.toString())
        ).use { c ->
            return if (c.moveToFirst()) Loan(
                id = c.getLong(0),
                copyId = c.getLong(1),
                memberId = c.getLong(2),
                borrowedAt = c.getLong(3),
                dueAt = c.getLong(4),
                status = c.getString(5)
            ) else null
        }
    }

    fun listActive(): List<LoanView> {
        val sql = """
            SELECT l.id, c.id, c.barcode, b.title, m.id, m.name, l.borrowed_at, l.due_at
              FROM loan l
              JOIN copy c ON c.id = l.copy_id
              JOIN book b ON b.id = c.book_id
              JOIN member m ON m.id = l.member_id
             WHERE l.status = 'active'
             ORDER BY l.due_at
        """.trimIndent()
        val out = ArrayList<LoanView>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LoanView(
                        loanId = c.getLong(0),
                        copyId = c.getLong(1),
                        barcode = c.getString(2),
                        title = c.getString(3),
                        memberId = c.getLong(4),
                        memberName = c.getString(5),
                        borrowedAt = c.getLong(6),
                        dueAt = c.getLong(7)
                    )
                )
            }
        }
        return out
    }
}
