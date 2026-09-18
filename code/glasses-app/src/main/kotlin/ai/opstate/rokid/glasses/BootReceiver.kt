package ai.opstate.rokid.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the study service after a reboot or an app update.
 *
 * The service owns the CXR subscription, so without it the phone's handshake has no responder and the
 * glasses looks permanently disconnected no matter how healthy the Bluetooth link is. The service
 * selects a foreground type that boot-time starts are allowed to use and defers the camera until a
 * session actually needs it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val action=intent.action
        if(action!=Intent.ACTION_BOOT_COMPLETED && action!=Intent.ACTION_MY_PACKAGE_REPLACED) return
        val start=Intent(context,GlassesStudyService::class.java).putExtra(GlassesStudyService.EXTRA_FROM_BOOT,true)
        runCatching { context.startForegroundService(start) }
            .onFailure { android.util.Log.w("OPSTATE","BOOT_SERVICE_START_${it.javaClass.simpleName}") }
    }
}
