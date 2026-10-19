package com.classlib.bookcorner.util

import android.content.Context
import com.classlib.bookcorner.model.Member
import net.sourceforge.pinyin4j.PinyinHelper

/** 条码：班级码(2) + 分类码(2) + 流水号(5)，共 9 位 */
object BarcodeUtil {
    fun make(classCode: Int, categoryCode: Int, seq: Long): String =
        String.format("%02d%02d%05d", classCode % 100, categoryCode % 100, seq % 100000)

    fun isInternal(code: String): Boolean =
        code.length == 9 && code.all { it.isDigit() }
}

/** 姓名 → 全拼 / 首字母。语音匹配的基础。 */
object PinyinUtil {

    fun fullPinyin(name: String): String {
        val sb = StringBuilder()
        for (ch in name) {
            val arr = try {
                PinyinHelper.toHanyuPinyinStringArray(ch)
            } catch (e: Exception) {
                null
            }
            if (arr != null && arr.isNotEmpty()) {
                sb.append(arr[0].filter { !it.isDigit() }.lowercase())
            } else if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    fun initials(name: String): String {
        val sb = StringBuilder()
        for (ch in name) {
            val arr = try {
                PinyinHelper.toHanyuPinyinStringArray(ch)
            } catch (e: Exception) {
                null
            }
            if (arr != null && arr.isNotEmpty()) {
                sb.append(arr[0][0].lowercaseChar())
            } else if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /** 由姓名生成 Member（拼音字段自动填） */
    fun toMember(name: String, sortNo: Int): Member =
        Member(name = name, pinyin = fullPinyin(name), initials = initials(name), sortNo = sortNo)
}

/**
 * 语音结果 → 名册成员。
 *
 * 不追求 ASR 准，因为候选只有几十个：完全相等 → 全拼 → 首字母 → 编辑距离。
 */
object NameMatcher {

    data class Hit(val member: Member, val score: Int)

    fun match(rawInput: String, members: List<Member>, limit: Int = 3): List<Hit> {
        val input = normalize(rawInput)
        if (input.isEmpty()) return emptyList()

        val hits = ArrayList<Hit>()
        for (m in members) {
            val score = score(input, m)
            if (score > 0) hits.add(Hit(m, score))
        }
        hits.sortByDescending { it.score }
        return hits.take(limit)
    }

    /** 唯一高置信命中直接返回，否则 null（让用户点选） */
    fun best(rawInput: String, members: List<Member>): Member? {
        val hits = match(rawInput, members)
        if (hits.isEmpty()) return null
        val top = hits[0]
        return if (top.score >= 85 && (hits.size == 1 || hits[0].score - hits[1].score >= 10)) top.member else null
    }

    private fun score(input: String, m: Member): Int {
        val name = normalize(m.name)
        if (input == name) return 100

        val py = m.pinyin
        if (input == py) return 95

        val ini = m.initials
        if (input == ini) return 92

        // 输入可能是全拼（用户/ASR 给了拼音）
        val dPy = levenshtein(input, py)
        if (dPy <= 2) return 90 - dPy * 5

        val dName = levenshtein(input, name)
        if (dName <= 1) return 88 - dName * 5

        // 包含关系（例如 ASR 把"王小明"识别成"王小明同学"）
        if (input.contains(name) || name.contains(input)) return 75

        return 0
    }

    private fun normalize(s: String): String =
        s.trim()
            .replace(" ", "")
            .replace("，", "")
            .replace(",", "")
            .replace("。", "")
            .replace("同学", "")
            .lowercase()

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}

/** 日期展示与天数计算 */
object Dates {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun md(millis: Long): String =
        java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(millis))

    fun ymd(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(millis))

    fun ymdOrEmpty(millis: Long?): String = if (millis == null || millis <= 0) "" else ymd(millis)

    /** 应还日过没过期、过期几天 */
    fun overdueDays(dueAt: Long, now: Long = System.currentTimeMillis()): Long {
        if (dueAt >= now) return 0
        return ((now - dueAt) / DAY_MS).coerceAtLeast(1)
    }

    fun plusDays(from: Long, days: Int): Long = from + days * DAY_MS
}

/** 轻量配置 */
class Prefs(context: Context) {    private val sp = context.getSharedPreferences("bookcorner", Context.MODE_PRIVATE)

    var classCode: Int
        get() = sp.getInt("class_code", 3)
        set(v) = sp.edit().putInt("class_code", v).apply()

    var loanDays: Int
        get() = sp.getInt("loan_days", 14)
        set(v) = sp.edit().putInt("loan_days", v).apply()

    /** "back" / "front" */
    var cameraFacing: String
        get() = sp.getString("camera_facing", "back") ?: "back"
        set(v) = sp.edit().putString("camera_facing", v).apply()

    /** 分类名 → 两位分类码 */
    fun categoryCode(category: String): Int {
        val arr = CATEGORIES
        val idx = arr.indexOf(category)
        return if (idx >= 0) idx + 1 else arr.size
    }

    companion object {
        val CATEGORIES = listOf("童话", "科普", "绘本", "文学", "历史", "其他")
    }
}
