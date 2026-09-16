package ai.opstate.rokid.phone.runtime

import ai.opstate.protocol.StudyWire
import ai.opstate.protocol.artifactSha256
import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

fun installedBuildIdentity(context: Context): JsonObject {
    val info=context.packageManager.getPackageInfo(context.packageName,0)
    val application=context.applicationInfo
    val artifacts=(listOf(application.sourceDir)+application.splitSourceDirs.orEmpty()).map {path->
        val file=File(path)
        StudyWire.payload("name" to file.name,"sha256" to artifactSha256(file),"bytes" to file.length())
    }
    return StudyWire.payload("package_name" to context.packageName,"version_name" to (info.versionName ?: "unknown"),
        "version_code" to info.longVersionCode,"artifacts" to JsonArray(artifacts))
}
