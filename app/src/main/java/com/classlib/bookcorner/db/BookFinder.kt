package com.classlib.bookcorner.db

import android.database.sqlite.SQLiteDatabase
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.model.CopyView

/**
 * 把用户输入解析成「候选册」。
 *
 * 前提：书上的出版商条码就是主入口，内部 9 位码只是系统内的短码
 * （不假设书上贴了标签）。三类输入：
 *
 *  - 9 位数字        → 精确一册（系统内条码，手输时用）
 *  - 13 位 978 / 979 → 该书全部册（出版商 ISBN，扫书就是这条）
 *  - 其他文本        → 书名模糊匹配（套装单册、码磨掉、老书没 ISBN）
 */
class BookFinder(private val db: SQLiteDatabase) {

    enum class Kind { COPY, ISBN, TITLE, NONE }

    class Result(val kind: Kind, val input: String, val copies: List<CopyView>) {

        /** 看着像个 ISBN，但库里没有——多半是还没入库 */
        val looksLikeIsbn: Boolean
            get() = input.length == 13 && (input.startsWith("978") || input.startsWith("979"))

        fun inStock(): List<CopyView> = copies.filter { it.status == Copy.STATUS_IN }

        fun onLoan(): List<CopyView> = copies.filter { it.status == Copy.STATUS_OUT }
    }

    fun resolve(raw: String): Result {
        val q = raw.trim()
        if (q.isEmpty()) return Result(Kind.NONE, q, emptyList())

        val copyDao = CopyDao(db)

        // 1) 内部条码优先：9 位数字不会和 13 位 ISBN 撞
        copyDao.viewByBarcode(q)?.let { return Result(Kind.COPY, q, listOf(it)) }

        // 2) ISBN
        if (q.length == 13 && (q.startsWith("978") || q.startsWith("979"))) {
            val book = BookDao(db).findByIsbn(q) ?: return Result(Kind.NONE, q, emptyList())
            return Result(Kind.ISBN, q, copyDao.viewsByBook(book.id))
        }

        // 3) 书名关键词
        val hits = copyDao.viewsByTitleLike(q, 60)
        return if (hits.isEmpty()) Result(Kind.NONE, q, emptyList()) else Result(Kind.TITLE, q, hits)
    }
}
