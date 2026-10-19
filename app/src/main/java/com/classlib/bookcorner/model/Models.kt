package com.classlib.bookcorner.model

/** 名册成员 */
data class Member(
    val id: Long = 0,
    val name: String,
    val pinyin: String,
    val initials: String,
    val role: String = ROLE_STUDENT,
    val status: String = STATUS_ACTIVE,
    val sortNo: Int = 0
) {
    companion object {
        const val ROLE_STUDENT = "student"
        const val ROLE_LIBRARIAN = "librarian"
        const val ROLE_TEACHER = "teacher"
        const val STATUS_ACTIVE = "active"
        const val STATUS_LEFT = "left"
    }
}

/** 书目（一种书） */
data class Book(
    val id: Long = 0,
    val isbn: String? = null,
    val title: String,
    val author: String? = null,
    val publisher: String? = null,
    val category: String,
    val source: String = "scan"
)

/** 册（一本实物书） */
data class Copy(
    val id: Long = 0,
    val bookId: Long,
    val barcode: String,
    val status: String = STATUS_IN,
    val ownerId: Long? = null,
    val shelf: String? = null,
    val inAt: Long = System.currentTimeMillis(),
    val removedReason: String? = null,
    val version: Int = 0
) {
    companion object {
        /** 入库：在图书角，可借出 */
        const val STATUS_IN = "in"

        /** 借出 */
        const val STATUS_OUT = "out"

        /** 遗失 */
        const val STATUS_LOST = "lost"

        /** 出库：所有者取回等 */
        const val STATUS_REMOVED = "removed"

        val ALL = listOf(STATUS_IN, STATUS_OUT, STATUS_LOST, STATUS_REMOVED)

        fun label(status: String): String = when (status) {
            STATUS_IN -> "入库"
            STATUS_OUT -> "借出"
            STATUS_LOST -> "遗失"
            STATUS_REMOVED -> "出库"
            else -> status
        }
    }
}

/** 借还记录 */
data class Loan(
    val id: Long = 0,
    val copyId: Long,
    val memberId: Long,
    val borrowedAt: Long,
    val dueAt: Long,
    val returnedAt: Long? = null,
    val status: String = STATUS_ACTIVE,
    val operatorId: Long? = null
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_RETURNED = "returned"
        const val STATUS_LOST = "lost"
    }
}

/** 在借视图（join 结果） */
data class LoanView(
    val loanId: Long,
    val copyId: Long,
    val barcode: String,
    val title: String,
    val memberId: Long,
    val memberName: String,
    val borrowedAt: Long,
    val dueAt: Long
)

/** 查书结果 */
data class CopyView(
    val copyId: Long,
    val barcode: String,
    val title: String,
    val author: String?,
    val category: String,
    val status: String,
    val ownerName: String?,
    val shelf: String?,
    val borrowerName: String?,
    val dueAt: Long?
)
