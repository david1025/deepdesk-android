package com.david.deepdesk.core.model

/**
 * GET /models 的一条。name 和 vision 都来自接口，客户端不改写。
 * vision 为 input_modalities 里是否包含 image。
 */
data class ListedModel(
    val id: String,
    val name: String,
    val vision: Boolean,
)

fun ModelInfo.toListedModel(): ListedModel? {
    val modelId = id?.trim().orEmpty()
    if (modelId.isEmpty()) return null
    val display = name?.trim()?.takeIf { it.isNotEmpty() } ?: modelId
    val vision = inputModalities.orEmpty().any { it.equals("image", ignoreCase = true) }
    return ListedModel(modelId, display, vision)
}

/**
 * 公开 Messages 接口当前可用的模型。
 * 列表未返回时，Flash 视为支持视觉、Pro 不支持。推理强度只在专业模式且深度思考开启时写入 output_config.effort。
 */
object ChatModels {
    const val FLASH = "deepseek-flash"
    const val PRO = "deepseek-v4-pro"

    const val EFFORT_LOW = "low"
    const val EFFORT_HIGH = "high"
    const val EFFORT_MAX = "max"

    fun supportsVision(model: String): Boolean {
        val id = canonicalId(model).lowercase()
        return "flash" in id && "pro" !in id
    }

    /** 把已退役别名收成当前 id；空白回落到 Flash。 */
    fun canonicalId(id: String): String = when (id.trim().lowercase()) {
        "", FLASH -> FLASH
        "deepseek-v4-flash", "deepseek-v4-flash-vision-exp" -> FLASH
        PRO -> PRO
        else -> id.trim()
    }

    fun sanitize(model: String): String = canonicalId(model).ifBlank { FLASH }

    fun fallbackCatalog(): List<ListedModel> = listOf(
        ListedModel(FLASH, FLASH, vision = true),
        ListedModel(PRO, PRO, vision = false),
    )

    /** 接口返回的 name、id、视觉标记原样保留。列表为空时才用本地兜底。 */
    fun mergeCatalog(apiModels: List<ListedModel>, current: String = FLASH): List<ListedModel> {
        val seen = LinkedHashMap<String, ListedModel>()
        apiModels.forEach { model ->
            if (model.id.isNotEmpty()) seen.putIfAbsent(model.id, model)
        }
        if (seen.isEmpty()) fallbackCatalog().forEach { seen.putIfAbsent(it.id, it) }
        val currentId = current.trim()
        if (currentId.isNotEmpty() && seen.keys.none { canonicalId(it) == canonicalId(currentId) }) {
            seen[currentId] = ListedModel(currentId, currentId, supportsVision(currentId))
        }
        return seen.values.toList()
    }

    fun visionOf(catalog: List<ListedModel>, modelId: String): Boolean {
        val match = catalog.firstOrNull { canonicalId(it.id) == canonicalId(modelId) }
        return match?.vision ?: supportsVision(modelId)
    }

    fun sanitizeEffort(effort: String): String = when (effort) {
        EFFORT_LOW, EFFORT_MAX -> effort
        else -> EFFORT_HIGH
    }

    /** 滑块档位：0 关闭，1 低，2 高，3 最高。关闭不写 output_config。 */
    fun thinkingLevel(thinkingEnabled: Boolean, effort: String): Int = when {
        !thinkingEnabled -> 0
        effort == EFFORT_LOW -> 1
        effort == EFFORT_MAX -> 3
        else -> 2
    }

    /** null 表示关闭思考。 */
    fun effortForLevel(level: Int): String? = when (level) {
        0 -> null
        1 -> EFFORT_LOW
        3 -> EFFORT_MAX
        else -> EFFORT_HIGH
    }
}
