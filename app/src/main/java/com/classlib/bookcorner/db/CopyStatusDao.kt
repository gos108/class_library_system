package com.classlib.bookcorner.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.model.Loan

/**
 * 手工状态变更：遗失 / 出库 / 撤销。
 *
 * 与 LoanDao 分开，是因为这两类动作的语义不同：
 * 借还是「有人把书拿走了/送回来了」，这里是「书不在了或又回来了」。
 *
 * 设计红线：只留痕，不追责。不设删除，任何书都不从库里消失，只有状态变化。
 */
class CopyStatusDao(private val db: SQLiteDatabase) {

    /**
     * 当前状态下允许手工改到哪些状态。正常归还不在此列（走 LoanDao.giveBack）。
     *
     *  in      → lost / removed   书在图书角也可能找不到，或所有者当场取回
     *  out     → lost / removed   借出去之后丢了；或借书人直接交还给所有者
     *  lost    → in               后来又找到了
     *  removed → in               所有者又带回来了
     */
    fun targets(current: String): List<String> = targetsOf(current)

    companion object {
        /** 纯函数，不碰数据库，UI 线程可安全调用 */
        fun targetsOf(current: String): List<String> = when (current) {
            Copy.STATUS_IN -> listOf(Copy.STATUS_LOST, Copy.STATUS_REMOVED)
            Copy.STATUS_OUT -> listOf(Copy.STATUS_LOST, Copy.STATUS_REMOVED)
            Copy.STATUS_LOST -> listOf(Copy.STATUS_IN)
            Copy.STATUS_REMOVED -> listOf(Copy.STATUS_IN)
            else -> emptyList()
        }
    }

    /**
     * 改状态。全程一个事务。
     *
     * @param reason 遗失/出库的原因；撤销时可为 null
     * @throws IllegalStateException 带可展示的中文原因
     */
    fun change(copyId: Long, to: String, reason: String?, operatorId: Long? = null): Boolean {
        db.beginTransaction()
        try {
            var from = ""
            var version = 0
            var borrowerId: Long? = null
            db.rawQuery("SELECT status,version FROM copy WHERE id=?", arrayOf(copyId.toString())).use {
                if (it.moveToFirst()) {
                    from = it.getString(0)
                    version = it.getInt(1)
                }
            }
            if (from.isEmpty()) throw IllegalStateException("这册书不存在")
            if (!targets(from).contains(to)) {
                throw IllegalStateException("「${Copy.label(from)}」不能直接改成「${Copy.label(to)}」")
            }

            // 借出中改成遗失/出库：必须同时关掉 active 借阅。
            // 否则 ux_loan_one_active 这条部分唯一索引会挡住以后重新入库再借出。
            if (from == Copy.STATUS_OUT) {
                db.rawQuery(
                    "SELECT member_id FROM loan WHERE copy_id=? AND status=?",
                    arrayOf(copyId.toString(), Loan.STATUS_ACTIVE)
                ).use { if (it.moveToFirst()) borrowerId = it.getLong(0) }

                db.update(
                    "loan",
                    ContentValues().apply { put("status", Loan.STATUS_LOST) },
                    "copy_id=? AND status=?",
                    arrayOf(copyId.toString(), Loan.STATUS_ACTIVE)
                )
            }

            val cv = ContentValues().apply {
                put("status", to)
                put("version", version + 1)
                if (to == Copy.STATUS_LOST || to == Copy.STATUS_REMOVED) {
                    put("removed_reason", reason)
                } else {
                    putNull("removed_reason")
                }
            }
            val rows = db.update(
                "copy", cv, "id=? AND version=?", arrayOf(copyId.toString(), version.toString())
            )
            if (rows != 1) throw IllegalStateException("这册书刚被改过，请重试")

            val note: String? = buildString {
                if (!reason.isNullOrBlank()) append(reason)
                if (borrowerId != null) {
                    if (isNotEmpty()) append("；")
                    append("原借阅人 id=$borrowerId 的借阅记为遗失")
                }
            }.takeIf { it.isNotBlank() }

            db.execSQL(
                "INSERT INTO op_log(action,target_type,target_id,from_status,to_status,operator_id,note,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                arrayOf("copy.status", "copy", copyId, from, to, operatorId, note, System.currentTimeMillis())
            )

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }
}
