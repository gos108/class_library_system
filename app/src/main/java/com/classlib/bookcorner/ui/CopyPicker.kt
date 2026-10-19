package com.classlib.bookcorner.ui

import android.app.Activity
import android.app.AlertDialog
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.model.CopyView

/** 一次解析出多册时的选册弹窗。 */
object CopyPicker {

    fun line(c: CopyView): String {
        val st = when (c.status) {
            Copy.STATUS_IN -> "在库"
            Copy.STATUS_OUT -> "借出" + (c.borrowerName?.let { "·$it" } ?: "")
            Copy.STATUS_LOST -> "遗失"
            else -> "出库"
        }
        return "《${c.title}》${c.barcode}\n$st · ${c.ownerName ?: "所有者未登记"}"
    }

    fun pick(
        activity: Activity,
        title: String,
        list: List<CopyView>,
        onCancel: (() -> Unit)? = null,
        onPick: (CopyView) -> Unit
    ) {
        if (list.isEmpty()) {
            onCancel?.invoke()
            return
        }
        if (list.size == 1) {
            onPick(list[0])
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(list.map { line(it) }.toTypedArray()) { _, i -> onPick(list[i]) }
            .setNegativeButton("取消") { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .show()
    }
}
