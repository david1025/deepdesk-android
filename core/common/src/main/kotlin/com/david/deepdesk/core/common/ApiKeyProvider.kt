package com.david.deepdesk.core.common

/**
 * API Key 提供者抽象（实现见 core/datastore 的 ApiKeyStore）。
 * 网络层按需读取；禁止记录密钥本身。
 */
interface ApiKeyProvider {
    fun getApiKey(): String?
}
