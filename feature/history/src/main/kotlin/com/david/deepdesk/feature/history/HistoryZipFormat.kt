package com.david.deepdesk.feature.history

import com.david.deepdesk.core.model.SearchHit
import com.david.deepdesk.feature.chat.engine.BlockJsonCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

/**
 * 官方 DeepSeek / chat-demo ZIP v3 编解码。
 * 官网导出使用 fragments（REQUEST/RESPONSE/THINK/FILE/TOOL_*）；
 * 本客户端旧备份使用 content/thinking，导入时两种都认。
 */
object HistoryZipFormat {

    const val FORMAT_VERSION = 3

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    data class Archive(
        val sessions: List<Session>,
        val imageBytes: Map<String, ByteArray>,
    )

    data class Session(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val currentMessageId: String?,
        val messages: List<Message>,
    )

    data class Message(
        val id: String,
        val parentId: String?,
        val role: String,
        val status: String?,
        val createdAt: Long,
        val edited: Boolean,
        val blocks: List<Block>,
        val files: List<FileRef>,
    )

    data class Block(
        val type: String,
        val text: String? = null,
        val signature: String? = null,
        val durationMs: Long? = null,
        val toolUseId: String? = null,
        val name: String? = null,
        val inputJson: String? = null,
        val rawJson: String? = null,
        val citationsJson: String? = null,
    )

    data class FileRef(
        val id: String,
        val fileName: String,
        val size: Long,
        val isImage: Boolean,
        val exportPath: String?,
        /** 官方非图片附件没有原件，靠扩展名或 file_type 还原图标。 */
        val mimeType: String? = null,
    )

    data class IndexEntry(
        val id: String,
        val title: String,
        val updatedAt: String,
    )

    fun parseZip(zipFile: File): Archive {
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries().toList()
            val items = entries.map { it to normalizeEntryName(it.name) }
            val manifest = items.firstOrNull { (_, n) ->
                n == "manifest.json" || n.endsWith("/manifest.json")
            } ?: throw IllegalArgumentException("导入失败：缺少 manifest.json（需要 DeepSeek 官方导出的 ZIP）")
            val rootPrefix = manifest.second.removeSuffix("manifest.json")
            val manifestText = zf.getInputStream(manifest.first).bufferedReader().readText()
            val manifestObj = json.parseToJsonElement(manifestText).jsonObject
            val version = (manifestObj["export_format_version"] as? JsonPrimitive)?.longOrNull?.toInt()
            if (version != FORMAT_VERSION) {
                throw IllegalArgumentException("导入失败：归档格式版本为 $version，当前仅支持 $FORMAT_VERSION")
            }

            fun find(relative: String) =
                items.firstOrNull { it.second == rootPrefix + relative }?.first

            val names = items.map { it.second }.toSet()
            val indexIds = readIndexIds(zf, find("chat_session_index.json"))
            val sessionIds = indexIds.ifEmpty { discoverSessionIds(names, rootPrefix) }
            if (sessionIds.isEmpty()) {
                throw IllegalArgumentException("导入失败：压缩包里没有会话")
            }

            val sessions = sessionIds.map { id ->
                val path = "chat_sessions/$id.json"
                val entry = find(path)
                    ?: throw IllegalArgumentException("导入失败：缺少 $path")
                parseSession(zf.getInputStream(entry).bufferedReader().readText())
            }
            sessions.forEach { validateTree(it) }

            val imageBytes = mutableMapOf<String, ByteArray>()
            items.forEach { (entry, normalized) ->
                if (normalized.startsWith("${rootPrefix}chat_sessions/") &&
                    normalized.contains("_files/") &&
                    !normalized.endsWith("/")
                ) {
                    val bytes = zf.getInputStream(entry).readBytes()
                    val relative = normalized.removePrefix(rootPrefix)
                    imageBytes[relative] = bytes
                    imageBytes[normalized] = bytes
                    imageBytes[entry.name] = bytes
                }
            }
            return Archive(sessions, imageBytes)
        }
    }

    fun parseSession(raw: String): Session {
        val obj = json.parseToJsonElement(raw).jsonObject
        val id = obj.id("id") ?: throw IllegalArgumentException("导入失败：会话缺少 id")
        val messages = (obj["messages"] as? JsonArray)?.map { parseMessage(it.jsonObject) }.orEmpty()
        return Session(
            id = id,
            title = obj.str("title").orEmpty(),
            createdAt = parseIso(obj.str("inserted_at") ?: obj.str("created_at")),
            updatedAt = parseIso(obj.str("updated_at") ?: obj.str("inserted_at") ?: obj.str("created_at")),
            currentMessageId = obj.id("current_message_id"),
            messages = messages,
        )
    }

    fun parseMessage(obj: JsonObject): Message {
        val id = obj.id("id") ?: throw IllegalArgumentException("导入失败：消息缺少 id")
        val fragments = (obj["fragments"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val fromFragments = if (fragments.isNotEmpty()) fragmentsToBlocks(id, fragments) else emptyList<Block>() to emptyList()
        val legacyFiles = (obj["files"] as? JsonArray)
            ?.mapIndexedNotNull { i, el -> parseFile(el, "${id}_legacy_$i") }
            .orEmpty()
        val files = (fromFragments.second + legacyFiles).distinctBy { it.id }
        val blocks = fromFragments.first.ifEmpty {
            buildList {
                obj.str("content")?.takeIf { it.isNotBlank() }?.let {
                    add(Block(type = "text", text = it))
                }
                obj.str("thinking")?.takeIf { it.isNotBlank() }?.let {
                    add(Block(type = "thinking", text = it))
                }
            }
        }
        return Message(
            id = id,
            parentId = obj.id("parent_id"),
            role = normalizeRole(obj.str("role") ?: "user"),
            status = obj.str("status"),
            createdAt = parseIso(obj.str("inserted_at") ?: obj.str("created_at")),
            edited = obj.bool("edited"),
            blocks = blocks,
            files = files,
        )
    }

    fun fragmentsToBlocks(messageId: String, fragments: List<JsonObject>): Pair<List<Block>, List<FileRef>> {
        val blocks = mutableListOf<Block>()
        val files = mutableListOf<FileRef>()
        fragments.forEachIndexed { i, frag ->
            when (frag.str("type")) {
                "REQUEST", "RESPONSE", "TEMPLATE_RESPONSE" -> {
                    val text = frag.str("content").orEmpty()
                    if (text.isNotBlank()) blocks += Block(type = "text", text = text)
                }
                "THINK" -> {
                    val text = frag.str("content").orEmpty()
                    val secs = frag.number("elapsed_secs")
                    val duration = secs?.let { (it * 1000.0).toLong().coerceAtLeast(0L) }
                    if (text.isNotBlank() || duration != null) {
                        blocks += Block(type = "thinking", text = text, durationMs = duration)
                    }
                }
                "FILE" -> {
                    val many = frag["files"] as? JsonArray
                    if (many != null && many.isNotEmpty()) {
                        many.forEachIndexed { j, el ->
                            parseFile(el, "${messageId}_file_${i}_$j")?.let { files += it }
                        }
                    } else {
                        parseFile(frag, "${messageId}_file_$i")?.let { files += it }
                    }
                }
                "TOOL_SEARCH" -> {
                    val queries = (frag["queries"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                    val hits = (frag["results"] as? JsonArray)?.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        val url = o.str("url") ?: o.str("cite_url") ?: return@mapNotNull null
                        SearchHit(
                            title = o.str("title") ?: o.str("cite_title"),
                            url = url,
                            snippet = o.str("snippet") ?: o.str("snippet_text") ?: o.str("cite_text"),
                            siteName = o.str("site_name") ?: o.str("cite_name"),
                            siteIcon = o.str("site_icon") ?: o.str("icon") ?: o.str("favicon"),
                            imageUrl = o.str("image_url"),
                        )
                    }.orEmpty()
                    val toolId = "import_search_${messageId}_$i"
                    val query = queries.firstOrNull().orEmpty()
                    val input = buildJsonObject {
                        put("action", JsonPrimitive("search"))
                        put("query", JsonPrimitive(query))
                    }
                    blocks += Block(
                        type = "server_tool_use",
                        toolUseId = toolId,
                        name = "deepseek_web_search",
                        inputJson = input.toString(),
                    )
                    val error = frag.str("error_code") ?: frag.str("status")?.takeIf { it == "FAILED" }?.let { "failed" }
                    blocks += Block(
                        type = "web_search_tool_result",
                        toolUseId = toolId,
                        rawJson = BlockJsonCodec.encodeSearchResult(hits, error, json),
                    )
                }
                "READ_LINK", "TOOL_OPEN" -> {
                    val url = frag.str("url") ?: frag.str("link")
                    val title = frag.str("title")
                    val toolId = "import_fetch_${messageId}_$i"
                    val input = buildJsonObject {
                        put("action", JsonPrimitive("read_link"))
                        if (url != null) put("url", JsonPrimitive(url))
                    }
                    blocks += Block(
                        type = "server_tool_use",
                        toolUseId = toolId,
                        name = "deepseek_read_url",
                        inputJson = input.toString(),
                    )
                    blocks += Block(
                        type = "web_fetch_tool_result",
                        toolUseId = toolId,
                        rawJson = BlockJsonCodec.encodeFetchResult(url, title, null, json),
                    )
                }
                else -> Unit
            }
        }
        return blocks to files
    }

    fun blocksToFragments(blocks: List<Block>, files: List<FileRef>, userRequest: Boolean = false): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val toolById = blocks.filter { it.type == "server_tool_use" }.associateBy { it.toolUseId }
        var emittedUserText = false
        blocks.forEach { b ->
            when (b.type) {
                "text" -> {
                    val text = b.text.orEmpty()
                    if (userRequest && !emittedUserText) {
                        out += requestFragment(text)
                        emittedUserText = true
                    } else if (text.isNotBlank() || !userRequest) {
                        out += buildJsonObject {
                            put("type", JsonPrimitive(if (userRequest) "REQUEST" else "RESPONSE"))
                            put("content", JsonPrimitive(text))
                        }
                    }
                }
                "thinking" -> out += buildJsonObject {
                    put("type", JsonPrimitive("THINK"))
                    put("content", JsonPrimitive(b.text.orEmpty()))
                    b.durationMs?.let { put("elapsed_secs", JsonPrimitive(((it + 500) / 1000).coerceAtLeast(1))) }
                }
                "web_search_tool_result" -> {
                    val (hits, error) = BlockJsonCodec.decodeSearchResult(b.rawJson, json)
                    val query = toolById[b.toolUseId]?.let {
                        BlockJsonCodec.toolInputField(it.inputJson, "query", json)
                    }
                    out += buildJsonObject {
                        put("type", JsonPrimitive("TOOL_SEARCH"))
                        put(
                            "queries",
                            buildJsonArray {
                                if (!query.isNullOrBlank()) add(JsonPrimitive(query))
                            },
                        )
                        put(
                            "results",
                            buildJsonArray {
                                hits.forEach { hit ->
                                    add(
                                        buildJsonObject {
                                            put("url", JsonPrimitive(hit.url))
                                            hit.title?.let { put("title", JsonPrimitive(it)) }
                                            hit.snippet?.let { put("snippet", JsonPrimitive(it)) }
                                            hit.siteName?.let { put("site_name", JsonPrimitive(it)) }
                                            hit.siteIcon?.let { put("site_icon", JsonPrimitive(it)) }
                                            hit.imageUrl?.let { put("image_url", JsonPrimitive(it)) }
                                        },
                                    )
                                }
                            },
                        )
                        error?.let { put("error_code", JsonPrimitive(it)) }
                    }
                }
                "web_fetch_tool_result" -> {
                    val (url, title, _) = BlockJsonCodec.decodeFetchResult(b.rawJson, json)
                    out += buildJsonObject {
                        put("type", JsonPrimitive("READ_LINK"))
                        url?.let { put("url", JsonPrimitive(it)) }
                        title?.let { put("title", JsonPrimitive(it)) }
                    }
                }
                else -> Unit
            }
        }
        if (userRequest && !emittedUserText) {
            out.add(0, requestFragment(""))
        }
        files.forEach { f ->
            out += fileFragment(f)
        }
        return out
    }

    fun requestFragment(text: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("REQUEST"))
        put("content", JsonPrimitive(text))
    }

    fun fileFragment(f: FileRef): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("FILE"))
        put("id", jsonId(f.id))
        put("file_name", JsonPrimitive(f.fileName))
        put("size", JsonPrimitive(f.size))
        put("is_image", JsonPrimitive(f.isImage))
        f.mimeType?.let { put("file_type", JsonPrimitive(it)) }
        f.exportPath?.let { put("export_path", JsonPrimitive(it)) }
    }

    fun encodeManifestJson(): String = buildJsonObject {
        put("export_format_version", JsonPrimitive(FORMAT_VERSION))
    }.toString()

    fun encodeIndexJson(entries: List<IndexEntry>): String = buildJsonArray {
        entries.forEach { e ->
            add(
                buildJsonObject {
                    put("id", jsonId(e.id))
                    put("title", JsonPrimitive(e.title))
                    put("updated_at", JsonPrimitive(e.updatedAt))
                },
            )
        }
    }.toString()

    fun encodeSessionJson(session: Session, isoUtc: (Long) -> String = ::isoUtc): String {
        val obj = buildJsonObject {
            put("id", jsonId(session.id))
            put("title", JsonPrimitive(session.title))
            put("inserted_at", JsonPrimitive(isoUtc(session.createdAt)))
            put("updated_at", JsonPrimitive(isoUtc(session.updatedAt)))
            if (session.currentMessageId != null) {
                put("current_message_id", jsonId(session.currentMessageId))
            } else {
                put("current_message_id", JsonNull)
            }
            put(
                "messages",
                buildJsonArray {
                    session.messages.forEach { m -> add(encodeMessage(m, isoUtc)) }
                },
            )
        }
        return obj.toString()
    }

    fun isoUtc(millis: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(millis))

    /**
     * 官网消息 ID 在每个会话内从 1 递增，不是全局唯一。
     * 本地 messages.id 是主键，导入时必须带上会话前缀，否则后导入的会话会覆盖先导入的消息。
     */
    fun scopeMessageIds(session: Session): Session {
        fun scope(raw: String) = "${session.id}:$raw"
        return session.copy(
            currentMessageId = session.currentMessageId?.let(::scope),
            messages = session.messages.map { message ->
                message.copy(
                    id = scope(message.id),
                    parentId = message.parentId?.let(::scope),
                )
            },
        )
    }

    /** 沿 current_message_id 走到根，记下每个父节点应选中的子节点。键和值都是已加会话前缀的 ID。 */
    fun selectedChildIds(session: Session): Map<String, String> {
        val byId = session.messages.associateBy { it.id }
        val selected = mutableMapOf<String, String>()
        var cursor = session.currentMessageId
        val seen = HashSet<String>()
        while (cursor != null && seen.add(cursor)) {
            val parent = byId[cursor]?.parentId ?: break
            selected[parent] = cursor
            cursor = parent
        }
        return selected
    }

    /** 导出时去掉导入加上的 `sessionId:` 前缀，让 ZIP 仍是官网那种会话内数字 ID。 */
    fun unscopeStoredId(sessionId: String, storedId: String): String {
        val prefix = "$sessionId:"
        return if (storedId.startsWith(prefix)) storedId.removePrefix(prefix) else storedId
    }

    fun validateTree(session: Session) {
        val byId = session.messages.associateBy { it.id }
        session.messages.forEach { m ->
            val parent = m.parentId?.let { byId[it] }
            if (m.parentId != null && parent == null) {
                throw IllegalArgumentException("导入失败：会话 ${session.id} 找不到父消息 ${m.parentId}")
            }
            if (parent != null && !idLess(parent.id, m.id)) {
                throw IllegalArgumentException("导入失败：会话 ${session.id} 消息树校验未通过（父 ID 应小于子 ID）")
            }
        }
        val roots = session.messages.filter { it.parentId == null }
        if (session.messages.isNotEmpty() && (roots.isEmpty() || roots.any { it.role != "user" })) {
            throw IllegalArgumentException("导入失败：会话 ${session.id} 根消息必须是提问")
        }
        session.messages.forEach { m ->
            val parent = m.parentId?.let { byId[it] }
            if (parent != null && parent.role == m.role) {
                throw IllegalArgumentException("导入失败：会话 ${session.id} 角色必须交替")
            }
        }
    }

    fun parseIso(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        runCatching { return Instant.parse(iso).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        return 0L
    }

    fun normalizeRole(role: String): String = when (role.uppercase()) {
        "USER" -> "user"
        "ASSISTANT" -> "assistant"
        else -> role.lowercase()
    }

    fun exportRole(role: String): String = when (role.lowercase()) {
        "user" -> "USER"
        "assistant" -> "ASSISTANT"
        else -> role.uppercase()
    }

    private fun encodeMessage(m: Message, isoUtc: (Long) -> String): JsonObject {
        val user = m.role == "user"
        val thinkingEnabled = m.blocks.any { it.type == "thinking" }
        val searchEnabled = m.blocks.any {
            it.type == "web_search_tool_result" || it.name == "deepseek_web_search"
        }
        val fragments = blocksToFragments(m.blocks, m.files, userRequest = user)
        return buildJsonObject {
            put("id", jsonId(m.id))
            if (m.parentId != null) put("parent_id", jsonId(m.parentId)) else put("parent_id", JsonNull)
            put("role", JsonPrimitive(exportRole(m.role)))
            m.status?.let { put("status", JsonPrimitive(it)) }
            put("inserted_at", JsonPrimitive(isoUtc(m.createdAt)))
            put("edited", JsonPrimitive(m.edited))
            if (!user) {
                put("thinking_enabled", JsonPrimitive(thinkingEnabled))
                put("search_enabled", JsonPrimitive(searchEnabled))
            }
            put("fragments", JsonArray(fragments))
        }
    }

    private fun readIndexIds(zf: ZipFile, entry: java.util.zip.ZipEntry?): List<String> {
        if (entry == null) return emptyList()
        val text = zf.getInputStream(entry).bufferedReader().readText()
        val arr = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.id("id") }
    }

    private fun discoverSessionIds(names: Set<String>, rootPrefix: String): List<String> {
        val prefix = "${rootPrefix}chat_sessions/"
        return names.mapNotNull { name ->
            if (!name.startsWith(prefix) || !name.endsWith(".json")) return@mapNotNull null
            val rest = name.removePrefix(prefix)
            if (rest.contains("/") || rest.startsWith(".")) null else rest.removeSuffix(".json")
        }
    }

    private fun normalizeEntryName(name: String): String =
        name.replace('\\', '/').trimStart('/').removePrefix("./")

    private fun jsonId(id: String): JsonPrimitive =
        id.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(id)

    private fun parseFile(el: JsonElement, fallbackId: String): FileRef? {
        val o = el as? JsonObject ?: return null
        val nested = (o["file"] as? JsonObject) ?: (o["content"] as? JsonObject)
        val sources = listOfNotNull(nested, o)
        fun pickStr(vararg keys: String): String? {
            for (src in sources) {
                for (key in keys) src.str(key)?.let { return it }
            }
            return null
        }
        fun pickNum(vararg keys: String): Double? {
            for (src in sources) {
                for (key in keys) src.number(key)?.let { return it }
            }
            return null
        }
        val id = pickStr("id", "file_id") ?: fallbackId
        val contentName = o.str("content")?.takeIf { it.length <= 180 && '\n' !in it }
        val rawName = pickStr("file_name", "filename", "fileName", "name") ?: contentName ?: id
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
        val mime = pickStr("file_type", "mime_type", "mime", "content_type", "fileType")
        val image = sources.any { it.bool("is_image") || it.bool("isImage") } ||
            guessImage(name) ||
            mime?.startsWith("image/", ignoreCase = true) == true
        return FileRef(
            id = id,
            fileName = name,
            size = pickNum("size", "file_size", "fileSize", "bytes")?.toLong()?.coerceAtLeast(0L) ?: 0L,
            isImage = image,
            exportPath = pickStr("export_path", "exportPath"),
            mimeType = mime,
        )
    }

    private fun guessImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "heic")

    private fun idLess(parent: String, child: String): Boolean {
        val p = parent.toLongOrNull()
        val c = child.toLongOrNull()
        return if (p != null && c != null) p < c else parent < child
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    private fun JsonObject.number(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.longOrNull?.toDouble() ?: p.content.toDoubleOrNull()
    }

    private fun JsonObject.id(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        val p = el as? JsonPrimitive ?: return null
        return p.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    }
}
