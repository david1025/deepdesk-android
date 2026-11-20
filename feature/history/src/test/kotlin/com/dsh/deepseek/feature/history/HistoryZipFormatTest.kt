package com.dsh.deepseek.feature.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HistoryZipFormatTest {

    @Test
    fun importsOfficialFragmentsZipWhenManifestIsNotFirst() {
        val zip = tempZip(
            "user.json" to """{"id":1,"email":"hidden"}""",
            "chat_sessions/100.json" to officialSessionJson(),
            "chat_session_index.json" to """[{"id":100,"title":"测试会话","updated_at":"2026-01-01T00:01:00.000Z"}]""",
            "manifest.json" to """{"export_format_version":3}""",
        )
        val archive = HistoryZipFormat.parseZip(zip)
        assertEquals(1, archive.sessions.size)
        val session = archive.sessions.single()
        assertEquals("100", session.id)
        assertEquals("测试会话", session.title)
        assertEquals("102", session.currentMessageId)
        assertEquals(2, session.messages.size)

        val user = session.messages[0]
        assertEquals("user", user.role)
        assertEquals("你好", user.blocks.single { it.type == "text" }.text)

        val assistant = session.messages[1]
        assertEquals("assistant", assistant.role)
        assertEquals("FINISHED", assistant.status)
        val thinking = assistant.blocks.single { it.type == "thinking" }
        assertEquals("思考中", thinking.text)
        assertEquals(3000L, thinking.durationMs)
        assertEquals("你好，世界", assistant.blocks.single { it.type == "text" }.text)
    }

    @Test
    fun importsZipNestedInFolder() {
        val zip = tempZip(
            "export/user.json" to "{}",
            "export/manifest.json" to """{"export_format_version":3}""",
            "export/chat_session_index.json" to """[{"id":"s1","title":"nested"}]""",
            "export/chat_sessions/s1.json" to """
                {
                  "id":"s1",
                  "title":"nested",
                  "inserted_at":"2026-01-01T00:00:00.000Z",
                  "updated_at":"2026-01-01T00:00:00.000Z",
                  "messages":[
                    {"id":"1","parent_id":null,"role":"USER","inserted_at":"2026-01-01T00:00:00.000Z",
                     "fragments":[{"type":"REQUEST","content":"hi"}]}
                  ]
                }
            """.trimIndent(),
        )
        val archive = HistoryZipFormat.parseZip(zip)
        assertEquals("s1", archive.sessions.single().id)
        assertEquals("hi", archive.sessions.single().messages.single().blocks.single().text)
    }

    @Test
    fun importsLegacyContentThinkingZip() {
        val zip = tempZip(
            "manifest.json" to """{"export_format_version":3}""",
            "chat_session_index.json" to """[{"id":"abc","title":"旧备份"}]""",
            "chat_sessions/abc.json" to """
                {
                  "id":"abc",
                  "title":"旧备份",
                  "created_at":"2026-01-01T00:00:00.000Z",
                  "updated_at":"2026-01-01T00:01:00.000Z",
                  "current_message_id":"m2",
                  "messages":[
                    {"id":"m1","parent_id":null,"role":"user","content":"问题","created_at":"2026-01-01T00:00:00.000Z"},
                    {"id":"m2","parent_id":"m1","role":"assistant","content":"回答","thinking":"想了想","status":"FINISHED","created_at":"2026-01-01T00:00:30.000Z"}
                  ]
                }
            """.trimIndent(),
        )
        val archive = HistoryZipFormat.parseZip(zip)
        val assistant = archive.sessions.single().messages[1]
        assertEquals("回答", assistant.blocks.single { it.type == "text" }.text)
        assertEquals("想了想", assistant.blocks.single { it.type == "thinking" }.text)
    }

    @Test
    fun roundtripFragmentsKeepThinkAndSearch() {
        val original = HistoryZipFormat.parseSession(officialSessionJson(withSearch = true))
        val encoded = HistoryZipFormat.encodeSessionJson(original)
        val again = HistoryZipFormat.parseSession(encoded)
        assertEquals("USER", HistoryZipFormat.exportRole(again.messages[0].role))
        assertEquals("ASSISTANT", HistoryZipFormat.exportRole(again.messages[1].role))
        assertEquals(3000L, again.messages[1].blocks.single { it.type == "thinking" }.durationMs)
        assertTrue(again.messages[1].blocks.any { it.type == "web_search_tool_result" })
        assertTrue(again.messages[1].blocks.any { it.name == "deepseek_web_search" })
        assertTrue(encoded.contains("\"type\":\"REQUEST\""))
        assertTrue(encoded.contains("\"type\":\"THINK\""))
        assertTrue(encoded.contains("\"type\":\"TOOL_SEARCH\""))
        assertTrue(encoded.contains("https://example.com/icon.png"))
    }

    @Test
    fun rejectsMissingManifest() {
        val zip = tempZip("chat_session_index.json" to "[]")
        try {
            HistoryZipFormat.parseZip(zip)
            fail("expected missing manifest")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("manifest.json"))
        }
    }

    @Test
    fun rejectsWrongVersion() {
        val zip = tempZip("manifest.json" to """{"export_format_version":2}""")
        try {
            HistoryZipFormat.parseZip(zip)
            fail("expected version error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("版本"))
        }
    }

    @Test
    fun scopesNumericMessageIdsSoSessionsDoNotCollide() {
        val first = HistoryZipFormat.scopeMessageIds(HistoryZipFormat.parseSession(numberedSession("sess-a", "甲")))
        val second = HistoryZipFormat.scopeMessageIds(HistoryZipFormat.parseSession(numberedSession("sess-b", "乙")))
        assertEquals("sess-a:1", first.messages[0].id)
        assertEquals("sess-a:2", first.messages[1].id)
        assertEquals("sess-a:1", first.messages[1].parentId)
        assertEquals("sess-a:4", first.currentMessageId)
        assertEquals("sess-b:1", second.messages[0].id)
        assertTrue(first.messages.map { it.id }.intersect(second.messages.map { it.id }.toSet()).isEmpty())

        val selected = HistoryZipFormat.selectedChildIds(first)
        assertEquals("sess-a:2", selected["sess-a:1"])
        assertEquals("sess-a:4", selected["sess-a:2"])
        assertEquals(null, selected["sess-a:3"])
        assertEquals("1", HistoryZipFormat.unscopeStoredId("sess-a", "sess-a:1"))
        assertEquals("plain-uuid", HistoryZipFormat.unscopeStoredId("sess-a", "plain-uuid"))
    }

    @Test
    fun rejectsParentIdGreaterThanChild() {
        try {
            HistoryZipFormat.parseSession(
                """
                {
                  "id":"s",
                  "title":"bad",
                  "inserted_at":"2026-01-01T00:00:00.000Z",
                  "messages":[
                    {"id":102,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"a"}]},
                    {"id":101,"parent_id":102,"role":"ASSISTANT","fragments":[{"type":"RESPONSE","content":"b"}]}
                  ]
                }
                """.trimIndent(),
            ).also { HistoryZipFormat.validateTree(it) }
            fail("expected tree error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("父 ID"))
        }
    }

    private fun numberedSession(id: String, title: String): String = """
        {
          "id":"$id",
          "title":"$title",
          "inserted_at":"2026-01-01T00:00:00.000Z",
          "updated_at":"2026-01-01T00:01:00.000Z",
          "current_message_id":4,
          "messages":[
            {"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"q1"}]},
            {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"a1"}]},
            {"id":3,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"a1-alt"}]},
            {"id":4,"parent_id":2,"role":"USER","fragments":[{"type":"REQUEST","content":"q2"}]}
          ]
        }
    """.trimIndent()

    private fun officialSessionJson(withSearch: Boolean = false): String {
        val search = if (withSearch) {
            """
            ,{"type":"TOOL_SEARCH","queries":["天气"],"results":[{"url":"https://example.com","title":"天气","snippet":"晴","site_name":"示例","site_icon":"https://example.com/icon.png"}]}
            """.trimIndent()
        } else {
            ""
        }
        return """
            {
              "id": 100,
              "title": "测试会话",
              "inserted_at": "2026-01-01T00:00:00.000Z",
              "updated_at": "2026-01-01T00:01:00.000Z",
              "current_message_id": 102,
              "messages": [
                {
                  "id": 101,
                  "parent_id": null,
                  "role": "USER",
                  "inserted_at": "2026-01-01T00:00:00.000Z",
                  "fragments": [{"type":"REQUEST","content":"你好"}]
                },
                {
                  "id": 102,
                  "parent_id": 101,
                  "role": "ASSISTANT",
                  "status": "FINISHED",
                  "inserted_at": "2026-01-01T00:00:30.000Z",
                  "thinking_enabled": true,
                  "search_enabled": ${if (withSearch) "true" else "false"},
                  "fragments": [
                    {"type":"THINK","content":"思考中","elapsed_secs":3},
                    {"type":"RESPONSE","content":"你好，世界"}
                    $search
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun keepsPdfNameAndSizeWithoutBytes() {
        val session = """
            {
              "id": 7,
              "title": "Qwen",
              "inserted_at": "2026-01-01T00:00:00.000Z",
              "updated_at": "2026-01-01T00:00:00.000Z",
              "current_message_id": 1,
              "messages": [
                {
                  "id": 1,
                  "parent_id": null,
                  "role": "USER",
                  "fragments": [
                    {"type":"REQUEST","content":"对比下这两个模型"},
                    {"type":"FILE","id":11,"file_name":"Qwen4-Max：新一代.pdf","size":5767168},
                    {"type":"FILE","file_id":"kimi","fileName":"Kimi K3.1 Pro.pdf","file_size":16252928,"file_type":"application/pdf"},
                    {"type":"FILE","file":{"file_name":"notes.docx","size":1200}}
                  ]
                }
              ]
            }
        """.trimIndent()
        val zip = tempZip(
            "manifest.json" to """{"export_format_version":3}""",
            "chat_sessions/7.json" to session,
            "chat_session_index.json" to """[{"id":7,"title":"Qwen","updated_at":"2026-01-01T00:00:00.000Z"}]""",
        )
        val files = HistoryZipFormat.parseZip(zip).sessions.single().messages.single().files
        assertEquals(3, files.size)
        assertEquals("Qwen4-Max：新一代.pdf", files[0].fileName)
        assertEquals(5767168L, files[0].size)
        assertTrue(!files[0].isImage)
        assertEquals("Kimi K3.1 Pro.pdf", files[1].fileName)
        assertEquals(16252928L, files[1].size)
        assertEquals("application/pdf", files[1].mimeType)
        assertEquals("notes.docx", files[2].fileName)
        assertEquals(1200L, files[2].size)
        assertTrue(!files[2].isImage)
    }

    private fun tempZip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("deepseek-history-", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }
}
