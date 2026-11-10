package br.com.motonav.dash

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher

/**
 * Identidade do celular perante o painel. Igual ao Carbit (RSAUtil): par de chaves RSA 1024
 * gerado NO PRÓPRIO celular; manda a chave pública e o HUID do painel cifrado com a chave privada.
 * Nada é copiado do app da Carbit.
 */
object RsaIdentity {
    private const val PREFS = "dash_identity"

    private fun keys(ctx: Context): Pair<String, String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val priv = p.getString("priv", null)
        val pub = p.getString("pub", null)
        if (!priv.isNullOrEmpty() && !pub.isNullOrEmpty()) return priv to pub
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val newPriv = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        val newPub = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        p.edit().putString("priv", newPriv).putString("pub", newPub).apply()
        return newPriv to newPub
    }

    fun publicKey(ctx: Context): String = keys(ctx).second

    fun encryptHuid(ctx: Context, huid: String): String {
        if (huid.isEmpty()) return ""
        return try {
            val pk = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(keys(ctx).first, Base64.NO_WRAP)))
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, pk) }
            Base64.encodeToString(c.doFinal(huid.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (e: Exception) {
            DashLog.d("RSA erro: ${e.message}")
            ""
        }
    }

    /** O painel cifra o HUID com a NOSSA chave pública; decifra com a nossa privada para conferir. */
    fun decrypt(ctx: Context, base64: String): String? {
        return try {
            val pk = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(keys(ctx).first, Base64.NO_WRAP)))
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, pk) }
            String(c.doFinal(Base64.decode(base64, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (e: Exception) {
            DashLog.d("RSA decifrar falhou: ${e.message}")
            null
        }
    }

    fun phoneUuid(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("uuid", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("uuid", it).apply()
        }
    }
}
