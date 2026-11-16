package app.marzha.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.marzha.core.EconSettings
import app.marzha.core.TaxBase
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Шифрование секретов ключом из Android Keystore: сам ключ не покидает защищённое хранилище телефона. */
private object Vault {
    private const val ALIAS = "marzha_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val (iv, data) = stored.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            c.doFinal(Base64.decode(data, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

/** Настройки и ключи доступа. API-ключи лежат только на устройстве и только в зашифрованном виде. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("marzha", Context.MODE_PRIVATE)

    var clientId: String
        get() = p.getString("clientId", "") ?: ""
        set(v) = p.edit().putString("clientId", v.trim()).apply()

    var apiKey: String
        get() = Vault.decrypt(p.getString("apiKey", "") ?: "")
        set(v) = p.edit().putString("apiKey", Vault.encrypt(v.trim())).apply()

    var perfId: String
        get() = p.getString("perfId", "") ?: ""
        set(v) = p.edit().putString("perfId", v.trim()).apply()

    var perfSecret: String
        get() = Vault.decrypt(p.getString("perfSecret", "") ?: "")
        set(v) = p.edit().putString("perfSecret", Vault.encrypt(v.trim())).apply()

    var demo: Boolean
        get() = p.getBoolean("demo", false)
        set(v) = p.edit().putBoolean("demo", v).apply()

    val hasKeys: Boolean get() = clientId.isNotEmpty() && (p.getString("apiKey", "") ?: "").isNotEmpty()
    val hasPerf: Boolean get() = perfId.isNotEmpty() && (p.getString("perfSecret", "") ?: "").isNotEmpty()

    /** Сплошной диапазон дней, за которые начисления уже загружены. */
    var loadedFrom: LocalDate?
        get() = p.getString("loadedFrom", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(v) = p.edit().putString("loadedFrom", v?.toString()).apply()

    var loadedTo: LocalDate?
        get() = p.getString("loadedTo", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(v) = p.edit().putString("loadedTo", v?.toString()).apply()

    var lastSync: Long
        get() = p.getLong("lastSync", 0)
        set(v) = p.edit().putLong("lastSync", v).apply()

    var settings: EconSettings
        get() = EconSettings(
            taxPct = round2(p.getFloat("taxPct", 6f)),
            taxBase = runCatching { TaxBase.valueOf(p.getString("taxBase", "") ?: "") }.getOrDefault(TaxBase.ALL),
            managerPct = round2(p.getFloat("managerPct", 0f)),
            stockTargetDays = p.getInt("stockTargetDays", 30),
        )
        set(v) = p.edit()
            .putFloat("taxPct", v.taxPct.toFloat())
            .putString("taxBase", v.taxBase.name)
            .putFloat("managerPct", v.managerPct.toFloat())
            .putInt("stockTargetDays", v.stockTargetDays)
            .apply()

    /** Ставки хранятся как float; округляем, чтобы 6,3 не превращалось в 6,300000190734863. */
    private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0

    fun clear() = p.edit().clear().apply()
}
