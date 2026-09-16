package ai.opstate.rokid.phone.rokid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Per-connection negotiation. The SDK creates the glasses group; the phone joins as a client. */
@SuppressLint("MissingPermission")
internal class StudyWifiDirect(private val context: Context,private val update: (String,String?)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel?=null
    private var receiver: BroadcastReceiver?=null
    private var active=false
    private var connecting=false
    private var connected=false
    private var target=""
    private var attempts=0
    private var ownsGroup=false
    private var requestedGroup=false
    private var groupPollScheduled=false
    private var retryScheduled=false
    private val negotiationTimeout=Runnable {if(active && !connected) fail("P2P_NEGOTIATION_TIMEOUT")}
    fun start(address: String)=main.post {
        if(active) return@post
        if(!BluetoothAdapter.checkBluetoothAddress(address.uppercase())) {update("FAILED","P2P_PEER_ADDRESS_INVALID");return@post}
        active=true;target=address
        val service=manager ?: run {fail("P2P_UNAVAILABLE");return@post}
        channel=service.initialize(context,Looper.getMainLooper()) {if(active) fail("P2P_CHANNEL_DISCONNECTED")}
        receiver=object: BroadcastReceiver() {
            override fun onReceive(context: Context?,intent: Intent?) {
                if(!active) return
                when(intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> if(intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,0)!=WifiP2pManager.WIFI_P2P_STATE_ENABLED) fail("P2P_WIFI_DISABLED")
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> peers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> inspectGroup()
                }
            }
        }
        val filter=IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver,filter)
        main.postDelayed(negotiationTimeout,40000)
        update("DISCOVERING",null);inspectGroup();discover()
    }
    private fun discover() {
        retryScheduled=false
        if(!active || connected || connecting) return
        manager?.discoverPeers(channel,object: WifiP2pManager.ActionListener {
            override fun onSuccess() {if(active) {peers();retry()}}
            override fun onFailure(reason: Int) {if(active) {update("DISCOVERING","P2P_DISCOVERY_$reason");retry()}}
        })
    }
    private fun retry() {
        if(!active || connected || connecting || retryScheduled) return
        retryScheduled=true;main.postDelayed({if(active) discover()},2000)
    }
    private fun peers() {
        if(!active || connected || connecting) return
        manager?.requestPeers(channel) {list->
            if(!active || connected || connecting) return@requestPeers
            val peer=list.deviceList.firstOrNull {it.deviceAddress.equals(target,true)} ?: return@requestPeers
            if(peer.status==WifiP2pDevice.CONNECTED) {inspectGroup();return@requestPeers}
            connecting=true;attempts++
            if(attempts>5) {fail("P2P_CONNECT_RETRIES_EXHAUSTED");return@requestPeers}
            update("NEGOTIATING",null)
            val config=WifiP2pConfig().apply {deviceAddress=peer.deviceAddress;wps.setup=WpsInfo.PBC;groupOwnerIntent=0}
            manager?.connect(channel,config,object: WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if(!active) return
                    requestedGroup=true
                    main.postDelayed({if(active && !connected) inspectGroup()},2000)
                }
                override fun onFailure(reason: Int) {
                    if(!active) return
                    connecting=false;update("DISCOVERING","P2P_CONNECT_$reason");retry()
                }
            })
        }
    }
    private fun inspectGroup() {
        if(!active) return
        manager?.requestGroupInfo(channel) {group->
            if(!active) return@requestGroupInfo
            if(group==null) {
                if(connected) {
                    connected=false;update("DISCONNECTED","P2P_DISCONNECTED")
                    main.removeCallbacks(negotiationTimeout);main.postDelayed(negotiationTimeout,40000)
                }
                ownsGroup=false
                if(connecting) {
                    if(!groupPollScheduled) {
                        groupPollScheduled=true
                        main.postDelayed({groupPollScheduled=false;if(active && !connected) inspectGroup()},1000)
                    }
                } else retry()
                return@requestGroupInfo
            }
            val matches=group.owner?.deviceAddress?.equals(target,true)==true || group.clientList.any {it.deviceAddress.equals(target,true)}
            if(!matches) {ownsGroup=false;fail("P2P_DIFFERENT_GROUP_ACTIVE");return@requestGroupInfo}
            manager?.requestConnectionInfo(channel) {info->
                if(active && info.groupFormed && info.groupOwnerAddress!=null) {
                    connected=true;connecting=false;ownsGroup=requestedGroup;groupPollScheduled=false;main.removeCallbacksAndMessages(null)
                    update("CONNECTED",null)
                }
            }
        }
    }
    private fun fail(code: String) {if(active) {cleanup();update("FAILED",code)}}
    fun close()=main.post {cleanup()}
    private fun cleanup() {
        active=false;connecting=false;connected=false;main.removeCallbacksAndMessages(null)
        receiver?.let {runCatching {context.unregisterReceiver(it)}};receiver=null
        channel?.let {current->
            runCatching {manager?.stopPeerDiscovery(current,null);manager?.cancelConnect(current,null)}
            if(ownsGroup) runCatching {manager?.removeGroup(current,null)}
            runCatching {current.close()}
        }
        channel=null
    }
}
