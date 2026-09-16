package ai.opstate.rokid.phone.replay

import ai.opstate.rokid.phone.PhoneApplication
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*

/** Keeps a user-started replay alive while the operator reads other screens. */
class DatasetReplayService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var run: Job?=null
    private val graph get()=application as PhoneApplication
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"OPSTATE 数据回放",NotificationManager.IMPORTANCE_LOW))
        if(Build.VERSION.SDK_INT>=29) startForeground(ID,notification("准备回放数据"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID,notification("准备回放数据"))
        scope.launch { graph.datasetReplay.state.collect { state ->
            getSystemService(NotificationManager::class.java).notify(ID,notification("已完成 ${state.completed} / ${state.total} · ${state.failures} 项异常"))
        } }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) { run?.cancel();if(run==null) stopSelf();return START_NOT_STICKY }
        if(run?.isActive==true) return START_NOT_STICKY
        run=scope.launch {
            try {
                graph.storageReady.await()
                check(graph.studyRuntime.state.value.sessionId==null && graph.studyRuntime.state.value.phase!="PREPARING") { "LIVE_SESSION_ACTIVE" }
                graph.datasetReplay.run(graph.settings.getString("harness_url","http://127.0.0.1:8765")!!,intent?.getIntExtra("limit",266) ?: 266,graph.settings.getString("expected_model_id",null))
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { android.util.Log.e("OPSTATE","REPLAY_${error.javaClass.simpleName}") }
            finally { stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun notification(detail: String): Notification {
        val open=PendingIntent.getActivity(this,30,packageManager.getLaunchIntentForPackage(packageName),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,31,Intent(this,DatasetReplayService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_rotate).setContentTitle("OPSTATE 数据回放")
            .setContentText(detail).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"停止回放",stop).build()).build()
    }
    override fun onTimeout(startId: Int,fgsType: Int) { run?.cancel();stopSelf() }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onDestroy() { scope.cancel();super.onDestroy() }
    companion object { const val STOP="ai.opstate.action.STOP_REPLAY";private const val ID=4103;private const val CHANNEL="opstate-replay" }
}
