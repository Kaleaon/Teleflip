package com.teleflip.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.teleflip.app.model.ProxyItem
import com.teleflip.app.model.ProxyType
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ProxyStorage {
    private const val PREFS_NAME = "teleflip_proxy_secure_prefs"
    private const val PREF_ENCRYPTED_PROXIES = "pref_encrypted_proxy_list"
    private const val AES_KEY_SEED = "TeleFlip_Secure_Proxy_Secret_Salt_v1"

    private fun getSecretKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(AES_KEY_SEED.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val secretKey = getSecretKey()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val ivBytes = secretKey.encoded.copyOf(16)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(ivBytes))
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            plainText
        }
    }

    fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return ""
        return try {
            val secretKey = getSecretKey()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val ivBytes = secretKey.encoded.copyOf(16)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ivBytes))
            val decodedBytes = Base64.decode(encryptedText, Base64.NO_WRAP)
            String(cipher.doFinal(decodedBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            encryptedText
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getProxies(context: Context): List<ProxyItem> {
        val prefs = getPrefs(context)
        val rawEncrypted = prefs.getString(PREF_ENCRYPTED_PROXIES, null) ?: return emptyList()
        val jsonStr = decrypt(rawEncrypted)
        if (jsonStr.isBlank()) return emptyList()

        val list = mutableListOf<ProxyItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val typeStr = obj.optString("type", ProxyType.SOCKS5.name)
                val type =
                    try {
                        ProxyType.valueOf(typeStr)
                    } catch (_: Exception) {
                        ProxyType.SOCKS5
                    }
                list.add(
                    ProxyItem(
                        id = obj.optInt("id", 0),
                        server = obj.optString("server", ""),
                        port = obj.optInt("port", 1080),
                        type = type,
                        username = obj.optString("username", ""),
                        password = obj.optString("password", ""),
                        secret = obj.optString("secret", ""),
                        isEnabled = obj.optBoolean("isEnabled", false),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    @Synchronized
    fun saveProxies(
        context: Context,
        proxies: List<ProxyItem>,
    ) {
        val jsonArray = JSONArray()
        for (proxy in proxies) {
            val obj = JSONObject()
            obj.put("id", proxy.id)
            obj.put("server", proxy.server)
            obj.put("port", proxy.port)
            obj.put("type", proxy.type.name)
            obj.put("username", proxy.username)
            obj.put("password", proxy.password)
            obj.put("secret", proxy.secret)
            obj.put("isEnabled", proxy.isEnabled)
            jsonArray.put(obj)
        }
        val encrypted = encrypt(jsonArray.toString())
        getPrefs(context).edit().putString(PREF_ENCRYPTED_PROXIES, encrypted).apply()
    }

    @Synchronized
    fun saveProxy(
        context: Context,
        proxy: ProxyItem,
    ): ProxyItem {
        val current = getProxies(context).toMutableList()
        val targetId =
            if (proxy.id <= 0) {
                (current.maxOfOrNull { it.id } ?: 0) + 1
            } else {
                proxy.id
            }
        val updatedItem = proxy.copy(id = targetId)

        val existingIndex =
            current.indexOfFirst {
                it.id == targetId || (it.server == proxy.server && it.port == proxy.port)
            }

        var listToSave = current
        if (updatedItem.isEnabled) {
            listToSave = listToSave.map { it.copy(isEnabled = false) }.toMutableList()
        }

        if (existingIndex >= 0) {
            listToSave[existingIndex] = updatedItem
        } else {
            listToSave.add(updatedItem)
        }

        saveProxies(context, listToSave)
        return updatedItem
    }

    @Synchronized
    fun enableProxy(
        context: Context,
        proxyId: Int,
    ) {
        val current = getProxies(context)
        val updated =
            current.map { item ->
                item.copy(isEnabled = (item.id == proxyId))
            }
        saveProxies(context, updated)
    }

    @Synchronized
    fun disableAllProxies(context: Context) {
        val current = getProxies(context)
        val updated = current.map { item -> item.copy(isEnabled = false) }
        saveProxies(context, updated)
    }

    @Synchronized
    fun deleteProxy(
        context: Context,
        proxyId: Int,
    ) {
        val current = getProxies(context)
        val updated = current.filter { it.id != proxyId }
        saveProxies(context, updated)
    }

    @Synchronized
    fun getEnabledProxy(context: Context): ProxyItem? {
        return getProxies(context).find { it.isEnabled }
    }
}
