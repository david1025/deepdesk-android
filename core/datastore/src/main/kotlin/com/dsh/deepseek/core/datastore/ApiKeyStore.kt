package com.dsh.deepseek.core.datastore

import android.content.Context
import com.dsh.deepseek.core.common.ApiKeyProvider
import com.dsh.deepseek.core.common.AppLog
import java.io.File

/**
 * API Key 安全存储（BYOK，F01）：
 * - 明文密钥只存在于内存瞬时变量
 * - 持久化形态：Keystore 主密钥加密后的密文文件（files/secure/api_key.bin）
 * - 不写入 Room/DataStore/日志；删除时抹除文件
 */
class ApiKeyStore(context: Context) : ApiKeyProvider {

    private val crypto = CryptoManager()
    private val storeFile: File =
        File(context.applicationContext.filesDir, "secure/api_key.bin")

    @Volatile
    private var cachedKey: String? = null

    fun save(key: String) {
        require(key.isNotBlank()) { "API Key 不能为空" }
        synchronized(this) {
            val payload = crypto.encrypt(key.toByteArray(Charsets.UTF_8))
            storeFile.parentFile?.mkdirs()
            storeFile.writeBytes(payload.cipherText)
            File(storeFile.parentFile, "api_key_iv.bin").writeBytes(payload.iv)
            cachedKey = key
        }
    }

    /** 未配置返回 null；解密失败按未配置处理（提示用户重新填写） */
    override fun getApiKey(): String? {
        cachedKey?.let { return it }
        synchronized(this) {
            if (!storeFile.exists()) return null
            return try {
                val iv = File(storeFile.parentFile, "api_key_iv.bin").readBytes()
                val plain = crypto.decrypt(CryptoManager.EncryptedPayload(storeFile.readBytes(), iv))
                String(plain, Charsets.UTF_8).also { cachedKey = it }
            } catch (e: Exception) {
                // Keystore 密钥失效（如清除凭据）——视为未配置并抹掉密文
                AppLog.w(TAG, "API Key 解密失败，已重置（不记录内容）")
                delete()
                null
            }
        }
    }

    fun isConfigured(): Boolean = getApiKey() != null

    fun delete() {
        synchronized(this) {
            cachedKey = null
            storeFile.delete()
            File(storeFile.parentFile, "api_key_iv.bin").delete()
        }
    }

    private companion object { const val TAG = "ApiKeyStore" }
}
