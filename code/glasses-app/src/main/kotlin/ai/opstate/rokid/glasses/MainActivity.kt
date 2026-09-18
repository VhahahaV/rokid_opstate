package ai.opstate.rokid.glasses

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextUtils
import android.graphics.Typeface
import ai.opstate.rokid.glasses.display.VisibleDrawAcknowledger
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private val graph get()=application as GlassesApplication
    private val scope=MainScope()
    private lateinit var content: LinearLayout
    private var visible: VisibleDrawAcknowledger?=null
    private var displayed: GlassesCard?=null
    private var lastState: GlassesUiState?=null
    private var demoCollapsed=false
    // Collection status is passive: it still renders, but must not hold the panel lit for a whole session.
    private var passiveStatus=false
    private var choosingPhone=false
    private var phoneIndex=0
    private var pairingHint="在手机打开蓝牙设置，使手机可被发现"
    private val phones=linkedMapOf<String,BluetoothDevice>()
    private val bluetooth get()=getSystemService(BluetoothManager::class.java).adapter
    private var receiverRegistered=false
    private val discovery=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            val device=intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if(device!=null) phones[device.address]=device
            if(intent.action==BluetoothDevice.ACTION_BOND_STATE_CHANGED && device!=null) pairingHint=when(device.bondState) {
                BluetoothDevice.BOND_BONDED->"已配对。请在该手机打开 OPSTATE，点击连接眼镜"
                BluetoothDevice.BOND_BONDING->"请在手机确认配对请求"
                else->"配对未完成，请重试"
            }
            if(intent.action==BluetoothAdapter.ACTION_DISCOVERY_FINISHED && phones.isEmpty()) pairingHint="未找到手机。请在手机打开蓝牙设置后重新搜索"
            if(choosingPhone) renderPhones()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK); setPadding(28,20,28,20)
        }
        setContentView(content)
        content.setOnClickListener { primaryAction() }
        scope.launch { graph.ui.collect { render(it) } }
        startStudy()
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);startStudy()}
    override fun onResume() {
        super.onResume()
        if(!graph.ui.value.running) startStudy()
        lastState=null
        if(choosingPhone) renderPhones() else render(graph.ui.value)
    }
    private fun startStudy() {
        val required=mutableListOf(Manifest.permission.CAMERA)
        if(Build.VERSION.SDK_INT>=33) required+=Manifest.permission.POST_NOTIFICATIONS
        val missing=required.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),10)
        else startForegroundService(Intent(this,GlassesStudyService::class.java))
    }
    @Deprecated("Framework callback")
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==11 && grantResults.isNotEmpty() && grantResults.all {it==PackageManager.PERMISSION_GRANTED}) openPhones()
        if(requestCode==10 && checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this,GlassesStudyService::class.java))
        }
    }
    private fun render(state: GlassesUiState) {
        if(choosingPhone) {
            if(state.phoneConnected) {choosingPhone=false;stopDiscovery();lastState=null}
            else return
        }
        if(lastState==state) return
        val previous=lastState
        lastState=state
        if(state.card!=null && displayed==state.card && previous?.sessionId==state.sessionId && previous?.running==true && state.running) return
        visible?.close(); visible=null
        content.removeAllViews(); displayed=state.card; passiveStatus=false
        val card=state.card
        if(card!=null) {
            wakeForCard()
            text(when(card.kind) {"SERVICE"->"OPSTATE  /  主动服务";"STATUS"->"OPSTATE  /  会话状态";else->"OPSTATE  /  情境更新"},15f,Color.rgb(136,255,227),1)
            text(card.title,26f,Color.WHITE,2,bold=true)
            text(card.body,22f,Color.WHITE,9)
            text("单击收起 · 双击返回桌面",13f,Color.GRAY,2)
            if(card.kind in setOf("SERVICE","STATUS")) visible=VisibleDrawAcknowledger(content,{ displayed?.id==card.id && graph.ui.value.card?.id==card.id }) { frames ->
                graph.visibleEvents.trySend(VisibleEvent(card.id,card.epoch,frames,System.currentTimeMillis()))
            }
        } else if(demoCollapsed && state.running) {
            // Keep the capture service alive, but release the display and its light.
        } else if(!state.running) {
            text("OPSTATE",26f,Color.WHITE)
            text(state.setupError ?: "正在准备相机与连接",17f,Color.LTGRAY)
            content.addView(Button(this).apply { text="启动眼镜"; setOnClickListener { startStudy() } })
        } else if(state.sessionId==null) {
            text("OPSTATE",15f,Color.rgb(136,255,227),1)
            text(if(state.phoneConnected) "手机已连接" else "连接你的手机",26f,Color.WHITE,2,bold=true)
            text(state.setupError ?: when {state.phoneConnected->"在手机点击「开始采集」";!state.cameraReady->"相机准备中…";else->"轻触侧边，选择或配对手机"},18f,Color.LTGRAY,3)
            if(state.bufferedPhotos>0) text("眼镜还有 ${state.bufferedPhotos} 张照片\n开始采集后恢复同步",15f,Color.GRAY,3)
            else if(state.phoneConnected) text("照片先存眼镜，再分批同步手机",15f,Color.GRAY,2)
            text("双击返回桌面",13f,Color.GRAY,1)
        } else if(!state.phoneConnected) {
            text("OPSTATE · 采集",15f,Color.rgb(136,255,227),1)
            text(if(state.collectingLocally) "正在本地采集" else "手机连接已中断",24f,Color.WHITE,2,bold=true)
            text("眼镜待同步 ${state.bufferedPhotos} 张\n轻触侧边查找手机",18f,Color.LTGRAY,3)
        } else if(state.demoStatus!=null) {
            val demo=state.demoStatus
            if(demo.collection) {
                passiveStatus=true
                if(!demoCollapsed) {
                    text("OPSTATE · 采集",14f,Color.rgb(136,255,227),1)
                    text(demo.phase,24f,Color.WHITE,2,bold=true)
                    text("手机 ${demo.captures} 张 · 待同步 ${state.bufferedPhotos} 张",20f,Color.WHITE,2)
                    text("每 ${demo.intervalSeconds} 秒拍摄",16f,Color.LTGRAY,1)
                    text("单击收起 · 双击返回桌面\n采集继续，停止请在手机结束",14f,Color.GRAY,3)
                }
                updateDisplayPolicy()
                return
            }
            text("OPSTATE",14f,Color.rgb(136,255,227),1)
            text(demo.phase,22f,Color.WHITE,2,bold=true)
            if(!demoCollapsed) {
                text("当前情境",14f,Color.LTGRAY,1)
                text(demo.context,20f,Color.WHITE,7)
                text("${demo.captures} 次观察    ${demo.receipts} 条服务回执",15f,Color.LTGRAY,1)
                text(if(demo.automaticCapture) "每 60 秒自动观察 · 手机可手动拍照" else "自动观察已关闭 · 手机可手动拍照",13f,Color.GRAY,2)
            }
            text("单击收起 · 双击返回桌面",13f,Color.GRAY,2)
        }
        // An active session with no card is intentionally a completely black, silent view.
        updateDisplayPolicy()
    }
    /**
     * Wakes the panel for a card. The screen sleeps on its own while collecting, and a card drawn on a
     * dark display is not visible to anyone — the two-frame visible ACK would never be produced and the
     * delivery would fail for a reason the model cannot see. The wake lock carries a timeout because the
     * window's KEEP_SCREEN_ON flag keeps the panel lit for as long as the card is up.
     */
    @Suppress("DEPRECATION")
    private fun wakeForCard() {
        val power=getSystemService(PowerManager::class.java)
        if(power.isInteractive) return
        runCatching {
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,"OPSTATE:card").acquire(3_000)
        }
    }
    private fun updateDisplayPolicy() {
        val showing=content.childCount>0
        // A card holds the panel lit because its visible ACK needs the screen to stay interactive; the
        // passive collection status does not, so the system timeout is allowed to blank it.
        if(showing && !passiveStatus) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes=window.attributes.apply {
            screenBrightness=if(showing) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else 0f
        }
        graph.displayVisible.value=showing && content.hasWindowFocus() && !isFinishing
    }
    private fun backAction() {
        if(choosingPhone) {choosingPhone=false;stopDiscovery();lastState=null;render(graph.ui.value)}
        else {
            visible?.close();visible=null
            displayed?.let {graph.dismissEvents.trySend(it.id)}
            graph.displayVisible.value=false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            finishAndRemoveTask()
        }
    }
    private fun text(value: String,size: Float,color: Int,lines: Int=10,bold: Boolean=false) { content.addView(TextView(this).apply {
        text=value; textSize=size; setTextColor(color); setPadding(0,8,0,10);maxLines=lines;ellipsize=TextUtils.TruncateAt.END
        if(bold) typeface=Typeface.DEFAULT_BOLD
    }) }
    override fun onKeyUp(keyCode: Int,event: KeyEvent): Boolean {
        if(keyCode==KeyEvent.KEYCODE_BACK) {if(!event.isCanceled) backAction();return true}
        if(choosingPhone) {
            when(keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT->{phoneIndex=(phoneIndex+1).coerceAtMost(phones.size+1);renderPhones();return true}
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_LEFT->{phoneIndex=(phoneIndex-1).coerceAtLeast(0);renderPhones();return true}
            }
        }
        if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER || keyCode==KeyEvent.KEYCODE_ENTER) {primaryAction();return true}
        return super.onKeyUp(keyCode,event)
    }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if(keyCode in setOf(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_CENTER) || choosingPhone && keyCode in setOf(
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) return true
        return super.onKeyDown(keyCode,event)
    }
    @Deprecated("Framework callback")
    override fun onBackPressed() {backAction()}
    private fun primaryAction() {
        if(!graph.ui.value.running) {startStudy();return}
        if(choosingPhone) {selectPhone();return}
        displayed?.let {demoCollapsed=true;graph.dismissEvents.trySend(it.id);return}
        if(demoCollapsed) {demoCollapsed=false;lastState=null;render(graph.ui.value);return}
        if(!graph.ui.value.phoneConnected) {openPhones();return}
        demoCollapsed=true;lastState=null;render(graph.ui.value)
    }
    private fun openPhones() {
        val permissions=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing=permissions.filter {checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty()) {requestPermissions(missing.toTypedArray(),11);return}
        choosingPhone=true;phoneIndex=0;phones.clear()
        if(!receiverRegistered) {
            val filter=IntentFilter(BluetoothDevice.ACTION_FOUND).apply {addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)}
            if(Build.VERSION.SDK_INT>=33) registerReceiver(discovery,filter,RECEIVER_EXPORTED) else registerReceiver(discovery,filter)
            receiverRegistered=true
        }
        if(bluetooth?.isEnabled!=true) {pairingHint="请先在系统设置中打开蓝牙";renderPhones();return}
        // Advertise the CXR BLE service so the phone's UUID-filtered scan can actually see us.
        graph.link.startBTPairing()
        bluetooth.bondedDevices.forEach {phones[it.address]=it}
        pairingHint="在手机打开蓝牙设置；滑动选择，轻触配对"
        if(!bluetooth.startDiscovery()) pairingHint="搜索未启动，可选择已配对手机或打开系统配对"
        renderPhones()
    }
    private fun renderPhones() {
        visible?.close();visible=null;displayed=null;content.removeAllViews()
        text("连接手机",24f,Color.WHITE,1,bold=true)
        text(pairingHint,15f,Color.LTGRAY,3)
        val labels=phones.values.map {"${it.name ?: "未命名设备"} · ${if(it.bondState==BluetoothDevice.BOND_BONDED) "已配对" else "可配对"}"}+
            listOf("重新搜索附近手机","系统蓝牙与配对")
        phoneIndex=phoneIndex.coerceIn(0,labels.lastIndex)
        val first=(phoneIndex-1).coerceAtLeast(0).coerceAtMost((labels.size-3).coerceAtLeast(0))
        labels.drop(first).take(3).forEachIndexed {offset,label->
            val index=first+offset
            text((if(index==phoneIndex) "›  " else "   ")+label,19f,if(index==phoneIndex) Color.rgb(136,255,227) else Color.WHITE,2)
            content.getChildAt(content.childCount-1).setOnClickListener {phoneIndex=index;selectPhone()}
        }
        text("前后滑动选择 · 单击确认 · 双击返回",13f,Color.GRAY,2)
        updateDisplayPolicy()
    }
    private fun selectPhone() {
        val device=phones.values.elementAtOrNull(phoneIndex)
        if(device!=null) {
            stopDiscovery()
            pairingHint=if(device.bondState==BluetoothDevice.BOND_BONDED) "已配对 ${device.name ?: "手机"}。请在该手机 OPSTATE 点击连接眼镜"
                else if(device.createBond()) "请在手机确认配对请求" else "无法发起配对，请打开系统配对重试"
            renderPhones()
        } else if(phoneIndex==phones.size) openPhones()
        else {
            stopDiscovery()
            try {startActivity(Intent().setClassName("com.rokid.os.sprite.launcher","com.rokid.os.sprite.launcher.setting.bluetooth.SettingBluetoothActivity"))}
            catch(error: android.content.ActivityNotFoundException) {pairingHint="系统配对界面不可用，请在手机蓝牙设置中配对";renderPhones()}
        }
    }
    private fun stopDiscovery() {
        if(Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED) bluetooth?.cancelDiscovery()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        graph.displayVisible.value=hasFocus && content.childCount>0 && !isFinishing
        if(hasFocus) content.postInvalidateOnAnimation()
    }
    override fun onStop() {graph.displayVisible.value=false;window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onStop()}
    override fun onDestroy() {if(receiverRegistered) {stopDiscovery();unregisterReceiver(discovery)};visible?.close(); scope.cancel(); super.onDestroy() }
}
