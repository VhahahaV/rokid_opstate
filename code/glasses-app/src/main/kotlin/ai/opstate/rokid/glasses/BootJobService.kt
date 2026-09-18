package ai.opstate.rokid.glasses

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Restarts the study service after a reboot.
 *
 * This ROM refuses to deliver `BOOT_COMPLETED` to third-party packages
 * ("Background execution Third-Party APP not allowed"), so [BootReceiver] never runs. A persisted
 * job is different: the system keeps it in its own store and reschedules it after boot, without
 * needing to hand the app a broadcast. The service owns the CXR subscription, so once it is up the
 * phone's handshake has a responder and the glasses stops looking disconnected.
 */
class BootJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if(!serviceRunning()) runCatching {
            startForegroundService(Intent(this,GlassesStudyService::class.java)
                .putExtra(GlassesStudyService.EXTRA_FROM_BOOT,true))
        }.onFailure { android.util.Log.w("OPSTATE","BOOT_JOB_START_${it.javaClass.simpleName}") }
        jobFinished(params,false)
        return false
    }

    private fun serviceRunning()=runCatching {
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getRunningServices(Int.MAX_VALUE).any {it.service.className==GlassesStudyService::class.java.name}
    }.getOrDefault(false)

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID=5201
        /** The platform minimum; the job is a cheap liveness check, not a recurring workload. */
        private const val JOB_PERIOD_MS=15*60*1000L
        /** Persisted so the system re-schedules it after a reboot; safe to call repeatedly. */
        fun schedule(context: Context) {
            val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            if(scheduler.getPendingJob(JOB_ID)!=null) return
            // Periodic, not one-shot: only a job still registered with the system is restored after a
            // reboot, and a one-shot would have already run and been discarded. When the last run is
            // older than the period the system fires it promptly on boot, which restarts the service.
            val job=JobInfo.Builder(JOB_ID,ComponentName(context,BootJobService::class.java))
                .setPersisted(true)
                .setPeriodic(JOB_PERIOD_MS)
                .build()
            runCatching { scheduler.schedule(job) }
                .onFailure { android.util.Log.w("OPSTATE","BOOT_JOB_SCHEDULE_${it.javaClass.simpleName}") }
        }
    }
}
