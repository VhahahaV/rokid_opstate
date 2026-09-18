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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextUtils
import android.graphics.Typeface
import ai.opstate.rokid.glasses.display.VisibleDrawAcknowledger
import kotlinx.coroutines.*

class MainActivity : Activity() {
    /**
     * The panel is a single-wavelength display: one emitter hue, and the only thing that varies is how
     * much of it a pixel emits. So the whole display is drawn in the emitter green and alpha is the
     * only ink there is. White and grey would land on exactly this green anyway - they would just spend
     * the most light the panel can make to say nothing extra.
     *
     * Every value here is light, not decoration. Light from the combiner scatters back across the field
     * of view, so a large or bright area does not read as "something drawn": it reads as a green wash
     * over whatever the wearer is actually looking at. That is why the ladder stops well short of the
     * emitter's maximum, and why nothing on this display is ever filled in.
     */
    private fun ink(alpha: Int)=Color.argb(alpha,76,255,151)
    private val Strong=ink(225)
    private val Body=ink(170)
    private val Meta=ink(118)
    private val Faint=ink(80)
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
    /**
     * What a tap on empty space means. Spacers and dividers are not clickable, so a touch between
     * blocks falls through to the page itself; naming the owner per screen keeps the pairing screen
     * from treating a stray tap as "confirm", which would start a bond with whatever row is selected.
     */
    private var screenTap:(()->Unit)={primaryAction()}
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
            // Top-anchored: a card's first line lands in the same place every time, so the eye knows
            // where to look without hunting a block whose position depends on how long it is.
            orientation=LinearLayout.VERTICAL; gravity=Gravity.TOP or Gravity.START
            setBackgroundColor(Color.BLACK); setPadding(dp(24f),dp(28f),dp(24f),dp(20f))
        }
        setContentView(content)
        // Reaching here from a card means the panel was asleep; these let the page turn it back on
        // rather than waiting for the wearer to touch the glasses.
        if(Build.VERSION.SDK_INT>=27) { setTurnScreenOn(true);setShowWhenLocked(true) }
        content.setOnClickListener { screenTap() }
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
        screenTap={primaryAction()}
        content.removeAllViews(); displayed=state.card
        // Cards are the only view that holds the panel; anything else renders and lets it sleep.
        passiveStatus=state.card==null
        val card=state.card
        if(card!=null) {
            wakeForCard()
            cardView(card)
            if(card.kind in setOf("SERVICE","STATUS")) visible=VisibleDrawAcknowledger(content,{ displayed?.id==card.id && graph.ui.value.card?.id==card.id }) { frames ->
                graph.visibleEvents.trySend(VisibleEvent(card.id,card.epoch,frames,System.currentTimeMillis()))
            }
        } else if(demoCollapsed && state.running) {
            // Keep the capture service alive, but release the display and its light.
        } else if(!state.running) {
            eyebrow("OPSTATE")
            gap(12f)
            hero(state.setupError ?: "正在准备相机与连接")
            gap(24f)
            outlineButton("启动眼镜") { startStudy() }
        } else if(state.sessionId==null) {
            eyebrow("OPSTATE")
            gap(12f)
            hero(if(state.phoneConnected) "手机已连接" else "连接你的手机")
            gap(12f)
            support(state.setupError ?: when {state.phoneConnected->"在手机上选择模式并开始";!state.cameraReady->"相机准备中…";else->"轻触侧边，选择或配对手机"},3)
            if(state.bufferedPhotos>0) { gap(18f);meta("眼镜还有 ${state.bufferedPhotos} 张照片\n开始会话后恢复同步",3) }
            else if(state.phoneConnected) { gap(18f);meta("照片先存眼镜，按手机设置分批同步") }
            gap(20f)
            footer("单击收起 · 双击返回桌面")
        } else if(!state.phoneConnected) {
            eyebrow("OPSTATE")
            gap(12f)
            hero(if(state.collectingLocally) "正在本地采集" else "手机连接已中断")
            gap(12f)
            support("眼镜待同步 ${state.bufferedPhotos} 张",2)
            gap(20f)
            footer("轻触侧边查找手机")
        } else if(state.demoStatus!=null) {
            val demo=state.demoStatus
            if(demo.collection) {
                passiveStatus=true
                if(!demoCollapsed) {
                    eyebrow("OPSTATE · 采集")
                    gap(12f)
                    hero(demo.phase,2)
                    gap(12f)
                    support("手机 ${demo.captures} 张 · 待同步 ${state.bufferedPhotos} 张",2)
                    gap(8f)
                    meta("每 ${demo.intervalSeconds} 秒拍摄")
                    gap(20f)
                    footer("单击收起 · 双击返回桌面\n采集继续，停止请在手机结束")
                }
                updateDisplayPolicy()
                return
            }
            eyebrow("OPSTATE")
            gap(12f)
            hero(demo.phase,2)
            if(!demoCollapsed) {
                gap(14f)
                meta("当前情境")
                gap(6f)
                // The status view carries two footers, so its body gets fewer lines than a card's.
                support(demo.context,7)
                gap(14f)
                meta("${demo.captures} 次观察    ${demo.receipts} 条服务回执")
                gap(8f)
                footer(if(demo.automaticCapture) "每 60 秒自动观察 · 手机可手动拍照" else "自动观察已关闭 · 手机可手动拍照")
            }
            gap(20f)
            footer("单击收起 · 双击返回桌面")
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
        // Only a card earns a lit panel: its visible ACK needs the screen interactive. Every other view -
        // connection state, collection status, sensor notices - waits for a tap instead of burning the
        // whole panel, which on these glasses lights every pixel whether or not anything is drawn on it.
        val lit=showing && !passiveStatus && displayed!=null
        if(lit) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // This window never sets screenBrightness, and that is a finding rather than an omission.
        //
        // Writing that attribute - at any value, including the system-brightness sentinel this page
        // used to defer to - makes this device composite the whole frame over a #101010 veil. Measured
        // on the glasses: with an override set, 0% of the frame came back true black and every
        // background pixel sat at (16,16,16); with the attribute left alone, 98% of it is true black,
        // which is what the working RealTopia HUD on this same hardware shows. On a single-wavelength
        // panel that veil is a uniform green field across the entire display - precisely the "green
        // background" the wearer reports - and no amount of redrawing the card could remove it, because
        // the card was never what was lighting up.
        //
        // Brightness on these glasses belongs to the wearer's own setting. This page's only light
        // control is its ink.
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
    /**
     * The card. A service is the payoff of the whole system, so it is the one card with an accent and a
     * mode label; everything else stays quiet so a service is unmistakable at a glance. A replay draws
     * itself exactly like a live service, because a demonstration is meant to read the same.
     */
    private fun cardView(card: GlassesCard) {
        val service=card.kind in setOf("SERVICE","REPLAY")
        val kind=when(card.kind) {
            "SERVICE","REPLAY"->"主动服务"
            "STATUS"->"会话状态"
            else->"情境更新"
        }
        // A service earns a marker and its mode; both ride in the eyebrow line, so the accent costs a
        // glyph instead of a bar of light.
        eyebrow(if(service) "◆ OPSTATE · $kind · ${serviceModeLabel(card.mode)}" else "· OPSTATE · $kind",service)
        gap(10f)
        hero(card.title)
        gap(12f)
        support(card.body)
        gap(16f)
        footer("单击收起 · 双击返回桌面")
    }
    private fun serviceModeLabel(mode: String)=when(mode) {
        "AWARENESS"->"提醒";"DECISION"->"建议";"PREPARATION"->"准备";"EXECUTION"->"执行";else->"服务"
    }
    private val density get()=resources.displayMetrics.density
    private fun dp(value: Float)=(value*density).toInt()
    /** The rhythm between blocks lives here rather than in padding on every line. */
    private fun gap(value: Float) { content.addView(View(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(value)) }) }
    private fun line(value: String,size: Float,color: Int,lines: Int,leading: Float,bold: Boolean=false,spacing: Float=0f) {
        content.addView(TextView(this).apply {
            text=value; textSize=size; setTextColor(color); maxLines=lines; ellipsize=TextUtils.TruncateAt.END
            // Wearing distance: a little extra leading keeps a wrapped service body readable.
            setLineSpacing(0f,leading)
            if(spacing!=0f) letterSpacing=spacing
            if(bold) typeface=Typeface.DEFAULT_BOLD
        })
    }
    /** What kind of thing this is. The leading marker is the accent; nothing is drawn under it. */
    private fun eyebrow(value: String,accented: Boolean=false)=line(value,11f,if(accented) Body else Meta,1,1.3f,spacing=0.06f)
    /**
     * The smallest type that still reads through the combiner, and much smaller than a phone would use.
     * On this display the size of the glyphs is the amount of light behind them, and a large bright
     * block is exactly what comes back as a wash over the room. Sizing down also pays for itself: the
     * full 140-codepoint service body fits at this size, where it had to be cut before.
     */
    private fun hero(value: String,lines: Int=2)=line(value,18f,Strong,lines,1.3f,bold=true)
    private fun support(value: String,lines: Int=9)=line(value,13f,Body,lines,1.5f)
    private fun meta(value: String,lines: Int=3)=line(value,11f,Meta,lines,1.4f)
    private fun footer(value: String)=line(value,10f,Faint,2,1.4f)
    /**
     * The only control on the glasses. A filled button would be the largest single lit area this page
     * could draw, so it is a word with a hairline around it and nothing inside.
     */
    private fun outlineButton(label: String,onClick: ()->Unit) { content.addView(TextView(this).apply {
        text=label; textSize=14f; setTextColor(Body); gravity=Gravity.CENTER
        setPadding(dp(18f),dp(9f),dp(18f),dp(9f))
        layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT)
        background=GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1f).coerceAtLeast(1),Faint); cornerRadius=dp(8f).toFloat() }
        isClickable=true; setOnClickListener { onClick() }
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
        // Only the rows themselves act here, so a tap that misses them cannot start a pairing.
        screenTap={}
        eyebrow("OPSTATE")
        gap(12f)
        hero("连接手机")
        gap(12f)
        support(pairingHint,3)
        gap(16f)
        val labels=phones.values.map {"${it.name ?: "未命名设备"} · ${if(it.bondState==BluetoothDevice.BOND_BONDED) "已配对" else "可配对"}"}+
            listOf("重新搜索附近手机","系统蓝牙与配对")
        phoneIndex=phoneIndex.coerceIn(0,labels.lastIndex)
        val first=(phoneIndex-1).coerceAtLeast(0).coerceAtMost((labels.size-3).coerceAtLeast(0))
        labels.drop(first).take(3).forEachIndexed {offset,label->
            val index=first+offset
            phoneRow(label,index==phoneIndex) {phoneIndex=index;selectPhone()}
        }
        gap(16f)
        footer("前后滑动选择 · 单击确认 · 双击返回")
        updateDisplayPolicy()
    }
    /**
     * A selectable row. The cursor and the label's brightness both carry the selection; there is no
     * button behind it and no rule beside it, because on this panel either would cost more light than
     * the word it decorates. The row owns its own vertical space rather than sitting behind a spacer,
     * so a tap that lands between two rows belongs to nobody.
     */
    private fun phoneRow(label: String,selected: Boolean,onClick: ()->Unit) {
        content.addView(TextView(this).apply {
            text=(if(selected) "› " else "· ")+label
            textSize=14f; maxLines=2; ellipsize=TextUtils.TruncateAt.END
            setTextColor(if(selected) Strong else Meta)
            setPadding(0,dp(14f),0,dp(14f)); isClickable=true; setOnClickListener { onClick() }
        })
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
