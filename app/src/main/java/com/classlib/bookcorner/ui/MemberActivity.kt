package com.classlib.bookcorner.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.MemberDao
import com.classlib.bookcorner.model.Member
import com.classlib.bookcorner.util.PinyinUtil

class MemberActivity : AppCompatActivity() {

    private lateinit var etPaste: EditText
    private lateinit var tvCount: TextView
    private lateinit var lvMembers: ListView
    private var members: List<Member> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_member)

        etPaste = findViewById(R.id.etPaste)
        tvCount = findViewById(R.id.tvCount)
        lvMembers = findViewById(R.id.lvMembers)

        findViewById<TextView>(R.id.btnImport).setOnClickListener { doImport() }
        reload()
    }

    private fun doImport() {
        val raw = etPaste.text.toString()
        val names = raw.split("\n", "\r\n", "，", ",", "、", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (names.isEmpty()) {
            toast("没有可导入的姓名")
            return
        }

        App.io.execute {
            val dao = MemberDao(App.db)
            val existing = dao.listAll().map { it.name }.toSet()
            val toAdd = names.filter { it !in existing }
                .mapIndexed { i, n -> PinyinUtil.toMember(n, existing.size + i) }

            if (toAdd.isNotEmpty()) dao.insertAll(toAdd)

            App.ui {
                etPaste.setText("")
                toast("新增 ${toAdd.size} 人，跳过重复 ${names.size - toAdd.size} 人")
                reload()
            }
        }
    }

    private fun reload() {
        App.io.execute {
            val list = MemberDao(App.db).listActive()
            App.ui {
                members = list
                tvCount.text = "共 ${list.size} 人"
                lvMembers.adapter = MemberAdapter(list)
            }
        }
    }

    private inner class MemberAdapter(private val data: List<Member>) : ArrayAdapter<Member>(
        this, R.layout.item_member, data
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_member, parent, false)
            val m = data[position]
            v.findViewById<TextView>(R.id.tvName).text = m.name
            v.findViewById<TextView>(R.id.tvMeta).text = "${m.pinyin} · ${m.initials}"
            return v
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
