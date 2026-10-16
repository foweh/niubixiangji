package com.hinnka.mycamera.aiplatform

import com.hinnka.mycamera.data.OpenAiApiKeyCipher

/**
 * 密钥保护器（对齐审计报告 K2）。
 *
 * 生产实现 [KeystoreAiSecretProtector] 复用工程既有 [OpenAiApiKeyCipher]
 * （AES/GCM + AndroidKeystore，密钥不可导出），与用户态 OpenAI Key 同一套思路；
 * **禁止**明文 `putString` 进 DataStore。
 *
 * JVM 单测自行实现一个假保护器（不做明文透传实现，避免被误用到生产）。
 */
interface AiSecretProtector {

    /** 明文 → 可落盘密文。明文为空时调用方应先判空。 */
    fun seal(plain: String): String

    /** 密文 → 明文。解密失败抛异常，由调用方捕获后回落。 */
    fun open(sealed: String): String

    /** 该值是否为本保护器产出的密文格式。 */
    fun isSealed(value: String): Boolean
}

/**
 * 默认实现：复用 `data/OpenAiApiKeyCipher`（AES/GCM + AndroidKeystore）。
 * 任何加解密失败（Keystore 异常 / 设备策略）都会抛异常，由 [DataStoreAiConfigStore] 捕获后拒写。
 */
class KeystoreAiSecretProtector : AiSecretProtector {

    private val cipher = OpenAiApiKeyCipher()

    override fun seal(plain: String): String = cipher.encrypt(plain)

    override fun open(sealed: String): String = cipher.decrypt(sealed)

    override fun isSealed(value: String): Boolean = cipher.isEncrypted(value)
}
