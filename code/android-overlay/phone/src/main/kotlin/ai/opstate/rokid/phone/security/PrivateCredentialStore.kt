package ai.opstate.rokid.phone.security

import ai.opstate.rokid.phone.rokid.CxrCredentialsProvider
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import ai.opstate.protocol.StudyWire
import kotlinx.serialization.json.*

/** Credentials stay in app-private files encrypted by a non-exportable Android Keystore key. */
class PrivateCredentialStore(context: Context) : CxrCredentialsProvider {
    private val directory=File(context.filesDir,"credentials").apply { mkdirs() }
    private val keyStore=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val key: SecretKey get() = (keyStore.getKey(ALIAS,null) as? SecretKey) ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    private fun file(name: String): File {
        require(name in setOf("client-secret","sn-license","harness-token","model-api-key"))
        return File(directory,"$name.enc")
    }
    @Synchronized fun put(name: String, value: ByteArray) {
        require(value.isNotEmpty() && value.size<=1024*1024)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key)
        val encrypted=cipher.doFinal(value)
        val target=AtomicFile(file(name))
        val output=target.startWrite()
        try {
            output.write(cipher.iv.size); output.write(cipher.iv); output.write(encrypted)
            target.finishWrite(output)
        } catch(error: Exception) { target.failWrite(output); throw error }
    }
    @Synchronized fun read(name: String): ByteArray? {
        val target=file(name)
        if(!target.exists()) return null
        val bytes=AtomicFile(target).readFully()
        require(bytes.isNotEmpty()) { "INVALID_CREDENTIAL_FILE" }
        val ivSize=bytes[0].toInt() and 0xff
        require(ivSize in 12..16 && bytes.size>1+ivSize+16) { "INVALID_CREDENTIAL_FILE" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(1,1+ivSize)))
        return cipher.doFinal(bytes.copyOfRange(1+ivSize,bytes.size))
    }
    @Synchronized fun remove(name: String) { AtomicFile(file(name)).delete() }
    override fun clientSecret(): String? = read("client-secret")?.decodeToString()
    override fun licenseBytes(): ByteArray? = read("sn-license")
    fun harnessToken(): String = read("harness-token")?.decodeToString().orEmpty()
    fun putModelApiKey(scope: String,value: String) {
        require(value.isNotBlank() && value.length<=8192 && value.none {it.isISOControl()}) {"MODEL_API_KEY_INVALID"}
        put("model-api-key",StudyWire.payload("scope" to scope,"key" to value.trim()).toString().toByteArray())
    }
    fun modelApiKey(scope: String): String {
        val saved=read("model-api-key") ?: return ""
        val value=StudyWire.json.parseToJsonElement(saved.decodeToString()).jsonObject
        return if(value["scope"]?.jsonPrimitive?.content==scope) value.getValue("key").jsonPrimitive.content else ""
    }
    companion object { private const val ALIAS="opstate.credentials.v1" }
}
