package com.classlib.bookcorner.util

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 极简 xlsx 生成器。不引 Apache POI（10MB+），只写纯文本/数字单元格。
 *
 * xlsx 本质是一个 zip，里面几个固定结构的 XML。
 * 对 800 行级别的表格，生成耗时 < 200ms。
 */
object XlsxWriter {

    data class Sheet(val name: String, val rows: List<List<Any?>>)

    fun write(out: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(out).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes(sheets.size))
            put(zip, "_rels/.rels", rootRels())
            put(zip, "xl/workbook.xml", workbook(sheets))
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            sheets.forEachIndexed { i, s ->
                put(zip, "xl/worksheets/sheet${i + 1}.xml", sheetXml(s))
            }
        }
    }

    // ---------- 各个 XML 部件 ----------

    private fun contentTypes(n: Int): String {
        val sheetOverrides = (1..n).joinToString("") {
            "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
$sheetOverrides
</Types>"""
    }

    private fun rootRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbook(sheets: List<Sheet>): String {
        val items = sheets.mapIndexed { i, s ->
            "<sheet name=\"${esc(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>$items</sheets>
</workbook>"""
    }

    private fun workbookRels(n: Int): String {
        val items = (1..n).joinToString("") {
            "<Relationship Id=\"rId$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$it.xml\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$items
</Relationships>"""
    }

    private fun sheetXml(sheet: Sheet): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        sheet.rows.forEachIndexed { r, row ->
            val rowNo = r + 1
            sb.append("<row r=\"$rowNo\">")
            row.forEachIndexed { c, value ->
                val ref = colName(c) + rowNo
                when (value) {
                    null -> Unit
                    is Int -> sb.append("<c r=\"$ref\"><v>$value</v></c>")
                    is Long -> sb.append("<c r=\"$ref\"><v>$value</v></c>")
                    is Double -> sb.append("<c r=\"$ref\"><v>$value</v></c>")
                    else -> {
                        val text = esc(value.toString())
                        sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">$text</t></is></c>")
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    // ---------- 工具 ----------

    private fun put(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** 0 -> A, 25 -> Z, 26 -> AA */
    private fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\u0000", "")
}
