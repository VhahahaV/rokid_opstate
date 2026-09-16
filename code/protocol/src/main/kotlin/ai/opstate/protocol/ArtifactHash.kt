package ai.opstate.protocol

import java.io.File
import java.security.MessageDigest

/** Streaming hash for APKs; never copy credentials or load a full installation into memory. */
fun artifactSha256(file: File): String {
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().use {input->
        val buffer=ByteArray(65536)
        while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}
    }
    return digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
}
