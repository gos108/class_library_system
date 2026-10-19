package com.classlib.bookcorner.export

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * 临时服务。只在「报表 / 备份」页存活期间运行。
 *
 * 四个路径，全部要求带一次性 token（同一热点下别人猜不到）：
 *   /i  索引页：两个下载 + 一个上传（GET）
 *   /r  Excel 报表（GET）
 *   /b  备份 json（GET）
 *   /u  上传备份，校验后给出对照页（POST multipart，字段名 f）
 *   /c  确认恢复，真正写库（POST）
 *
 * 上传做在这里而不是另开一个入口，因为备份文件本来就是从这一页拿走的：
 * 走的时候从这里下，回来的时候也从这里回。
 */
class ReportServer(
    port: Int,
    private val token: String,
    private val xlsx: File,
    private val json: File,
    private val summary: String,
    private val context: Context,
    private val onRestored: (String) -> Unit
) : NanoHTTPD(port) {

    val boundPort: Int get() = listeningPort

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        if (session.parameters["t"]?.firstOrNull() != token) return notFound()

        if (session.method == Method.POST) {
            return when (uri) {
                "/u" -> handleUpload(session)
                "/c" -> handleConfirm()
                else -> notFound()
            }
        }
        return when (uri) {
            "/", "/i" -> index()
            "/r" -> send(xlsx, MIME_XLSX)
            "/b" -> send(json, "application/json; charset=utf-8")
            else -> notFound()
        }
    }

    // ---------------- 恢复 ----------------

    /** 待确认的备份。校验通过后先落在这里，等用户点「确认恢复」 */
    private val pending: File get() = File(context.cacheDir, "upload_pending.json")

    /**
     * 第一步：收文件、校验、给出将要覆盖什么的对照，不落库。
     *
     * 恢复是不可逆动作，所以不做「上传即恢复」——多一次点击换来「看清了再动手」。
     */
    private fun handleUpload(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val path = files["f"]
                ?: return page(false, "没有收到文件", "请重新选择备份文件再提交。")

            val text = File(path).readText()
            val preview = BackupImporter(context).inspect(text)   // 校验不过会抛，此时还没动数据库
            pending.writeText(text)

            confirmPage(preview, currentCopies())
        } catch (e: IllegalArgumentException) {
            page(false, "这个文件不能用", esc(e.message ?: "内容不符合备份格式"))
        } catch (e: Throwable) {
            page(false, "读取失败", esc(e.message ?: "未知错误"))
        }
    }

    /** 第二步：真正写库 */
    private fun handleConfirm(): Response {
        if (!pending.exists()) {
            return page(false, "没有待恢复的文件", "请重新上传备份文件。")
        }
        return try {
            val text = pending.readText()
            val importer = BackupImporter(context)
            val preview = importer.inspect(text)
            val result = importer.apply(preview)
            pending.delete()

            onRestored(result.summary)
            page(
                true,
                "恢复完成",
                "${result.summary}<br>备份导出时间：${esc(preview.exportedAt)}" +
                    "<br><br>报表文件还是恢复前的，要新的请回手表重新进入本页。" +
                    "<br>恢复前的数据已自动存为 <code>${esc(result.safetyFile.name)}</code>（应用内）。"
            )
        } catch (e: IllegalArgumentException) {
            pending.delete()
            page(false, "这个文件不能用", esc(e.message ?: "内容不符合备份格式"))
        } catch (e: Throwable) {
            page(false, "恢复失败", esc(e.message ?: "未知错误") + "<br>数据没有被改动。")
        }
    }

    private fun currentCopies(): Int = try {
        com.classlib.bookcorner.App.db.rawQuery("SELECT COUNT(*) FROM copy", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    } catch (e: Throwable) {
        -1
    }

    /** 覆盖前的对照页。数字摆出来，比一句「确认？」有效。 */
    private fun confirmPage(p: BackupImporter.Preview, current: Int): Response {
        val now = if (current < 0) "未知" else "$current 册"
        val html = """
            <!doctype html>
            <html lang="zh-CN"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>确认恢复</title>
            <style>
              body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;padding:28px 24px;background:#f5f5f3;color:#1a1a1a}
              h1{font-size:19px;margin:0 0 18px;color:#a15c00}
              table{width:100%;border-collapse:collapse;font-size:14px;margin-bottom:20px}
              td{padding:9px 0;border-bottom:1px solid #e6e6e2}
              td:last-child{text-align:right;font-weight:600}
              button{width:100%;padding:15px;border:0;border-radius:10px;background:#E24B4A;color:#fff;font-size:16px}
              a{display:block;text-align:center;margin-top:12px;padding:13px;border-radius:10px;background:#fff;border:1px solid #dcdcdc;color:#1a1a1a;text-decoration:none;font-size:14px}
              .warn{font-size:12px;color:#a15c00;line-height:1.7;margin:0 0 18px}
            </style></head><body>
            <h1>将用手表上的数据换成这个备份</h1>
            <table>
              <tr><td>手表现在有</td><td>$now</td></tr>
              <tr><td>备份导出时间</td><td>${esc(p.exportedAt)}</td></tr>
              <tr><td>备份里的册数</td><td>${p.copies} 册</td></tr>
              <tr><td>备份里的名册</td><td>${p.members} 人</td></tr>
              <tr><td>备份里的借阅</td><td>${p.loans} 条</td></tr>
              <tr><td>备份里的流水</td><td>${p.opLogs} 条</td></tr>
            </table>
            <p class="warn">当前数据会被清掉并替换。动手前会自动存一份快照，但请确认上面这些数字对得上。</p>
            <form method="post" action="/c?t=$token">
              <button type="submit">确认恢复</button>
            </form>
            <a href="/i?t=$token">取消</a>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    // ---------------- 页面 ----------------

    private fun index(): Response {
        val html = """
            <!doctype html>
            <html lang="zh-CN"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>班级图书角</title>
            <style>
              body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;padding:24px;background:#f5f5f3;color:#1a1a1a}
              h1{font-size:19px;margin:0 0 6px}
              .sub{color:#777;font-size:13px;margin:0 0 22px}
              a{display:block;text-align:center;padding:16px;border-radius:10px;text-decoration:none;font-size:17px;margin-bottom:12px}
              .main{background:#1D9E75;color:#fff}
              .bak{background:#fff;color:#1a1a1a;border:1px solid #dcdcdc}
              .tip{font-size:12px;color:#8a8a86;line-height:1.7;margin-top:22px}
              details{margin-top:28px;border-top:1px solid #e2e2de;padding-top:16px}
              summary{font-size:14px;color:#8a8a86;cursor:pointer}
              form{margin-top:14px}
              input[type=file]{display:block;width:100%;font-size:13px;margin-bottom:12px}
              button{width:100%;padding:14px;border:0;border-radius:10px;background:#E24B4A;color:#fff;font-size:16px}
              .warn{font-size:12px;color:#a15c00;line-height:1.6;margin-top:12px}
            </style></head><body>
            <h1>班级图书角</h1>
            <p class="sub">$summary</p>
            <a class="main" href="/r?t=$token">下载 Excel 报表</a>
            <a class="bak" href="/b?t=$token">下载备份文件（.json）</a>
            <p class="tip">Excel 用来查看，备份文件用来恢复。建议两个都存进微信收藏。</p>

            <details>
              <summary>从备份文件恢复（会覆盖手表上的数据）</summary>
              <form method="post" action="/u?t=$token" enctype="multipart/form-data">
                <input type="file" name="f" accept="*/*" required>
                <button type="submit">下一步</button>
              </form>
              <p class="warn">选完文件会先让你核对册数再确认，不会直接覆盖。
              换新手表、重装应用、或误操作后需要回滚时用这里。</p>
            </details>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun page(ok: Boolean, title: String, body: String): Response {
        val color = if (ok) "#1D9E75" else "#E24B4A"
        val html = """
            <!doctype html>
            <html lang="zh-CN"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${esc(title)}</title>
            <style>
              body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;padding:32px 24px;background:#f5f5f3;color:#1a1a1a}
              h1{font-size:20px;color:$color;margin:0 0 16px}
              p{font-size:15px;line-height:1.8;color:#333;margin:0}
              code{background:#eaeae6;padding:2px 6px;border-radius:4px;font-size:13px}
              a{display:inline-block;margin-top:28px;padding:12px 20px;border-radius:10px;background:#fff;border:1px solid #dcdcdc;color:#1a1a1a;text-decoration:none;font-size:14px}
            </style></head><body>
            <h1>${esc(title)}</h1>
            <p>$body</p>
            <a href="/i?t=$token">返回</a>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun send(file: File, mime: String): Response = try {
        newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length()).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            addHeader("Cache-Control", "no-store")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "read failed")
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")

    companion object {
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
