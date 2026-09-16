package ai.opstate.rokid.phone

import android.app.Application
import ai.opstate.rokid.phone.rokid.CxrPhoneLink
import ai.opstate.rokid.phone.security.PrivateCredentialStore
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.transport.LegacyCxrTransport
import kotlinx.coroutines.*
import ai.opstate.rokid.phone.runtime.StudyRuntimeProvider
import ai.opstate.rokid.phone.runtime.StudyRuntimeEngine
import ai.opstate.rokid.phone.runtime.DirectModelConfig
import ai.opstate.rokid.phone.runtime.InferenceClient
import ai.opstate.rokid.phone.runtime.SessionSetup
import ai.opstate.protocol.StudyWire

class PhoneApplication : Application(), StudyRuntimeProvider {
    val appScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val storageReady by lazy { appScope.async { journal.recoverInterrupted() } }
    override fun onCreate() { super.onCreate();storageReady.start() }
    override suspend fun beforeSessionStart() {
        storageReady.await()
        check(!datasetReplay.state.value.running) { "REPLAY_ALREADY_RUNNING" }
    }
    val credentials by lazy { PrivateCredentialStore(this) }
    val link by lazy { CxrPhoneLink(this,credentials) }
    val transport by lazy { LegacyCxrTransport(link,appScope) }
    val database by lazy { StudyDatabase.open(this) }
    val journal by lazy { StudyJournal(this,database) }
    val datasetReplay by lazy { ai.opstate.rokid.phone.replay.DatasetReplayRunner(this,journal,credentials::harnessToken) }
    val publicReplayDataset by lazy {ai.opstate.rokid.phone.replay.PublicReplayDataset {path->
        assets.open(ai.opstate.rokid.phone.replay.PublicReplayDataset.ASSET_ROOT+path).use {it.readBytes()}}}
    val settings by lazy { getSharedPreferences("study-settings",MODE_PRIVATE) }
    internal var validationInferenceFactory: ((SessionSetup)->InferenceClient?)?=null
    fun modelConfig(): DirectModelConfig=settings.getString("model_api_config",null)?.let {
        StudyWire.json.decodeFromString(DirectModelConfig.serializer(),it)
    } ?: DirectModelConfig.qwen()
    override val studyRuntime by lazy {StudyRuntimeEngine(this,appScope,transport,journal,credentials::harnessToken,
        {config->credentials.modelApiKey(config.credentialScope())},
        {setup->if(ai.opstate.rokid.phone.app.BuildConfig.DEBUG) validationInferenceFactory?.invoke(setup) else null})}
}
