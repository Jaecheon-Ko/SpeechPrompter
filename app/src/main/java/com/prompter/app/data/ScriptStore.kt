package com.prompter.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class Script(val id: String, val title: String, val text: String, val updatedAt: Long)

/** SharedPreferences + JSON 기반 대본 저장 목록 */
object ScriptStore {
    private const val KEY = "prompter_scripts"

    fun load(c: Context): MutableList<Script> {
        val raw = c.getSharedPreferences("prompter", 0).getString(KEY, "[]")!!
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Script(
                o.getString("id"), o.getString("title"),
                o.getString("text"), o.getLong("updatedAt")
            )
        }.sortedByDescending { it.updatedAt }.toMutableList()
    }

    fun save(c: Context, list: List<Script>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("title", it.title)
                    .put("text", it.text).put("updatedAt", it.updatedAt)
            )
        }
        c.getSharedPreferences("prompter", 0).edit().putString(KEY, arr.toString()).apply()
    }

    /** 같은 제목이면 덮어쓰기. 저장된 Script 반환 */
    fun upsert(c: Context, title: String, text: String): Script {
        val list = load(c)
        val existing = list.firstOrNull { it.title == title }
        val item = Script(
            existing?.id ?: UUID.randomUUID().toString(),
            title, text, System.currentTimeMillis()
        )
        if (existing != null) list.remove(existing)
        list.add(0, item)
        save(c, list)
        return item
    }

    fun delete(c: Context, id: String) {
        save(c, load(c).filterNot { it.id == id })
    }
}

/** txt / docx 파일 읽기 (POI 없이 표준 라이브러리만 사용) */
object ScriptFileReader {

    /** @return (파일명(확장자 제외), 본문) — 실패 시 예외 */
    fun read(c: Context, uri: Uri): Pair<String, String> {
        val name = displayName(c, uri) ?: "불러온 대본"
        val title = name.substringBeforeLast('.')
        val input = c.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("파일을 열 수 없습니다")
        val text = input.use {
            if (name.lowercase().endsWith(".docx")) readDocx(it)
            else it.readBytes().toString(Charsets.UTF_8)
        }
        return title to text
    }

    private fun displayName(c: Context, uri: Uri): String? =
        c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && cur.moveToFirst()) cur.getString(i) else null
        }

    /** .docx는 ZIP — word/document.xml에서 텍스트만 추출 */
    private fun readDocx(input: InputStream): String {
        val sb = StringBuilder()
        ZipInputStream(input).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    val parser = Xml.newPullParser()
                    parser.setInput(zis, "UTF-8")
                    var ev = parser.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        when (ev) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "t" -> sb.append(parser.nextText())
                                "br", "cr" -> sb.append('\n')
                            }
                            XmlPullParser.END_TAG ->
                                if (parser.name == "p") sb.append('\n')
                        }
                        ev = parser.next()
                    }
                    break
                }
                e = zis.nextEntry
            }
        }
        return sb.toString()
    }
}
