package ai.opstate.rokid.phone.runtime

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.os.BatteryManager
import kotlinx.serialization.json.JsonObject
import ai.opstate.protocol.StudyWire
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface StudyRuntimeProvider {val studyRuntime: StudyRuntimeEngine;suspend fun beforeSessionStart() {}}

/** Android lifecycle adapter. Domain state, capture, inference and receipt ownership live in the actor. */
class StudyRuntimeService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val engine get()=(application as StudyRuntimeProvider).studyRuntime
    private var wakeLock: PowerManager.WakeLock?=null
    private val locations get()=getSystemService(LocationManager::class.java)
    private val locationListener=object: LocationListener {
        override fun onLocationChanged(location: Location) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Required on Android 9")
        override fun onStatusChanged(provider: String?,status: Int,extras: android.os.Bundle?) = Unit
    }
    private var locationTracking=false
    private var locationForeground=false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("opstate-runtime","OPSTATE 采集与服务",NotificationManager.IMPORTANCE_LOW))
        foreground("正在准备")
        wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OPSTATE:collection").apply {setReferenceCounted(false)}
        // Held whenever the session is doing something the wearer asked for, and also while it is waiting
        // for the glasses link to come back. That second case is the one that matters: losing the link
        // pauses the session, and a lock keyed only off the running phases was released at exactly the
        // moment the engine needed the CPU most - with the screen off the device then slept through the
        // reconnect backoff and the link never returned.
        scope.launch {engine.state.map {it.holdsCpu()}.distinctUntilChanged().collect {active->
            if(active) wakeLock?.acquire() else wakeLock?.takeIf {it.isHeld}?.release()
            if(active && !locationTracking && locationForeground && hasLocationPermission()) {
                try {
                    locations.getProviders(true).filter {it in setOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)}.forEach {
                        locations.requestLocationUpdates(it,5_000,0f,locationListener)
                    }
                    locationTracking=true
                } catch(_: SecurityException) {android.util.Log.w("OPSTATE","LOCATION_PERMISSION_UNAVAILABLE")}
            } else if(!active && locationTracking) {locations.removeUpdates(locationListener);locationTracking=false}
        }}
        scope.launch {engine.state.map {
            if(it.mode==SessionMode.COLLECTION) "${if(it.phase=="PAUSED") "已暂停" else if(it.phase=="SYNCING") "正在收尾同步" else "后台采集中"} · 手机 ${it.liveCaptures} 张 · 待同步 ${it.bufferedPhotos} 张"
            else "${it.phase} · ${it.observations} 次观察 · ${it.receipts} 条回执"
        }.distinctUntilChanged().collect {detail->
            getSystemService(NotificationManager::class.java).notify(ID,notification(detail))
        }}
        scope.launch {
            var started=false
            engine.state.collect {state->
                if(state.sessionId!=null) started=true
                else if(started && state.phase!="PREPARING") stopSelf()
            }
        }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        scope.launch {
            try {
                when(intent?.action) {
                    ACTION_START -> {
                        (application as StudyRuntimeProvider).beforeSessionStart()
                        val setup=StudyWire.json.decodeFromString(SessionSetup.serializer(),requireNotNull(intent.getStringExtra("setup")))
                        val result=engine.start(setup)
                        // A repeated tap must not tear down an already active/preparing session.
                        if(result.isFailure && engine.state.value.sessionId==null && engine.state.value.phase!="PREPARING") stopSelf()
                    }
                    ACTION_PAUSE -> engine.pauseSession()
                    ACTION_RESUME -> engine.resumeSession()
                    ACTION_STOP -> {engine.stopSession();if(engine.state.value.sessionId==null) stopSelf()}
                    ACTION_SEEK -> engine.seekTo(intent.getLongExtra("position_ms",0)).getOrThrow()
                }
            } catch(error: Exception) {
                if(error is CancellationException && !currentCoroutineContext().isActive) throw error
                android.util.Log.e("OPSTATE","SERVICE_${error.javaClass.simpleName}")
                if(intent?.action==ACTION_START && engine.state.value.sessionId==null) stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder?=null
    private fun notification(detail: String): Notification {
        val launch=packageManager.getLaunchIntentForPackage(packageName)
        val open=PendingIntent.getActivity(this,1,launch,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,2,Intent(this,StudyRuntimeService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"opstate-runtime").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("OPSTATE 会话运行中").setContentText(detail).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"结束并同步",stop).build()).build()
    }
    private fun foreground(detail: String) {
        locationForeground=hasLocationPermission() && locations.isLocationEnabled
        if(Build.VERSION.SDK_INT>=29) startForeground(ID,notification(detail),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if(locationForeground) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        else startForeground(ID,notification(detail))
    }
    override fun onDestroy() {
        if(engine.state.value.sessionId!=null || engine.state.value.phase=="PREPARING") engine.stopSoon()
        scope.cancel();super.onDestroy()
        wakeLock?.takeIf {it.isHeld}?.release()
        locations.removeUpdates(locationListener)
    }
    companion object {
        const val ACTION_START="ai.opstate.action.START_SESSION"
        const val ACTION_PAUSE="ai.opstate.action.PAUSE_SESSION"
        const val ACTION_RESUME="ai.opstate.action.RESUME_SESSION"
        const val ACTION_STOP="ai.opstate.action.STOP_SESSION"
        const val ACTION_SEEK="ai.opstate.action.SEEK_SESSION"
        private const val ID=4102
    }
}

private fun Context.hasLocationPermission()=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED

/** Phone position sampled when the image arrives; never claim a stale fix is a capture-time GPS fix. */
internal fun phoneCaptureMetadata(context: Context): JsonObject {
    val manager=context.getSystemService(LocationManager::class.java)
    var status="NOT_AVAILABLE"
    val position=try {
        when {
            !context.hasLocationPermission()->{status="PERMISSION_REQUIRED";null}
            !manager.isLocationEnabled->{status="LOCATION_DISABLED";null}
            else->manager.getProviders(true).mapNotNull {manager.getLastKnownLocation(it)}
                .filter {freshLocation(it,SystemClock.elapsedRealtimeNanos())}
                .maxByOrNull {it.elapsedRealtimeNanos}.also {status=if(it==null) "NO_RECENT_FIX" else "AVAILABLE"}
        }
    } catch(_: SecurityException) {status="PERMISSION_REQUIRED";null}
    val battery=context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return StudyWire.payload("phone_metadata_at_epoch_ms" to System.currentTimeMillis(),"phone_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "phone_battery_percent" to battery.takeIf {it in 0..100},"location_status" to status,"phone_location" to position?.let {location->
            StudyWire.payload("latitude" to location.latitude,"longitude" to location.longitude,"provider" to location.provider,
                "accuracy_m" to location.accuracy.takeIf {location.hasAccuracy()},"fix_at_epoch_ms" to location.time,
                "age_ms" to (SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1_000_000,
                "altitude_m" to location.altitude.takeIf {location.hasAltitude()},"speed_mps" to location.speed.takeIf {location.hasSpeed()},
                "bearing_degrees" to location.bearing.takeIf {location.hasBearing()},"mock" to location.isFromMockProvider)
        })
}

internal fun freshLocation(location: Location,nowNanos: Long): Boolean =
    location.elapsedRealtimeNanos>0 && (nowNanos-location.elapsedRealtimeNanos) in 0..120_000_000_000L &&
        location.latitude.isFinite() && location.latitude in -90.0..90.0 && location.longitude.isFinite() && location.longitude in -180.0..180.0
