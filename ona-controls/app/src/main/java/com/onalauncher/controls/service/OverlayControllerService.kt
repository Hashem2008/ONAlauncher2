/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.service

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.onalauncher.controls.R
import com.onalauncher.controls.BackendType
import com.onalauncher.controls.OverlayPrefs
import com.onalauncher.controls.input.AccessibilityTouchBackend
import com.onalauncher.controls.input.InputBackend
import com.onalauncher.controls.input.InputDispatcher
import com.onalauncher.controls.input.RootShellBackend
import com.onalauncher.controls.input.TcpBridgeBackend
import com.onalauncher.controls.model.OverlayElement
import com.onalauncher.controls.model.OverlayProfile
import com.onalauncher.controls.monitor.ForegroundAppMonitor
import com.onalauncher.controls.monitor.UsageStatsForegroundPoller
import com.onalauncher.controls.profile.OverlayProfileManager
import com.onalauncher.controls.view.ElementGeometry
import com.onalauncher.controls.view.OverlayEditorView
import com.onalauncher.controls.view.OverlayElementView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating controller. Foreground service (specialUse) that:
 *  - watches [ForegroundAppMonitor.foreground] and auto SHOWS the controls when a whitelisted app
 *    comes to the front and auto HIDES them (after a short debounce) when it leaves,
 *  - play mode: one small TYPE_APPLICATION_OVERLAY window per control (touches elsewhere go to the app),
 *  - edit mode: one full-screen window with [OverlayEditorView] (drag / pinch / settings),
 *  - a small draggable "ONA" bubble for edit / hide / switch profile / stop.
 * Everything runs on the main thread; backends do their own I/O off it.
 */
class OverlayControllerService : Service(), OverlayEditorView.Listener {

    companion object {
        private const val TAG = "OnaOverlay"
        private const val CHANNEL = "ona_overlay"
        private const val NOTIFY_ID = 7781
        private const val HIDE_DEBOUNCE_MS = 350L

        const val ACTION_START = "ona.overlay.START"
        const val ACTION_STOP = "ona.overlay.STOP"
        const val ACTION_EDIT = "ona.overlay.EDIT"
        const val ACTION_RELOAD = "ona.overlay.RELOAD"
        const val EXTRA_PACKAGE = "pkg"

        @Volatile var running = false
            private set

        fun start(ctx: Context, action: String = ACTION_START, pkg: String? = null) {
            val i = Intent(ctx, OverlayControllerService::class.java).setAction(action)
            if (pkg != null) i.putExtra(EXTRA_PACKAGE, pkg)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, OverlayControllerService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var poller: UsageStatsForegroundPoller

    private var backend: InputBackend = AccessibilityTouchBackend()
    private lateinit var dispatcher: InputDispatcher

    /** Package the controls are shown for (null = hidden). */
    private var shownFor: String? = null
    private var profile: OverlayProfile? = null
    private val playViews = ArrayList<OverlayElementView>()
    private var bubble: View? = null
    private var editor: OverlayEditorView? = null
    private var editorFor: String? = null
    /** User hid the controls with the bubble; stays hidden until shown again or the app changes. */
    private var userHidden = false

    private val hideRunnable = Runnable { hideControls(removeBubble = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dispatcher = InputDispatcher(backend, { profile ?: OverlayProfileManager.profileFor(this, null) }, ::screenSize)
        startAsForeground()
        reloadBackend()
        poller = UsageStatsForegroundPoller(this, scope).also { it.start() }

        scope.launch {
            ForegroundAppMonitor.foreground.collect { pkg -> onForegroundChanged(pkg) }
        }
        scope.launch {
            // profile / whitelist edited in the settings screen -> rebuild what is on screen
            OverlayProfileManager.changes.drop(1).collect {
                if (editor == null) shownFor?.let { pkg ->
                    if (!OverlayProfileManager.isTarget(this@OverlayControllerService, pkg)) hideControls(true)
                    else showControls(pkg, force = true)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                OverlayPrefs.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EDIT -> openEditor(intent.getStringExtra(EXTRA_PACKAGE) ?: shownFor)
            ACTION_RELOAD -> {
                reloadBackend()
                shownFor?.let { showControls(it, force = true) }
            }
        }
        updateNotification()
        return START_STICKY
    }

    // ------------------------------------------------------------------ auto show / hide

    private fun onForegroundChanged(pkg: String?) {
        if (editor != null) return // editing: keep everything as it is
        if (pkg != null && OverlayProfileManager.isTarget(this, pkg)) {
            main.removeCallbacks(hideRunnable)
            if (shownFor != pkg) userHidden = false
            showControls(pkg)
        } else if (shownFor != null) {
            // debounce: a transient window (permission dialog, share sheet) must not make it flicker
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, HIDE_DEBOUNCE_MS)
        }
    }

    private fun canDraw() = Settings.canDrawOverlays(this)

    private fun showControls(pkg: String, force: Boolean = false) {
        if (!canDraw()) { Log.w(TAG, "No overlay permission"); return }
        if (!force && shownFor == pkg && (playViews.isNotEmpty() || userHidden)) return
        removePlayViews()
        shownFor = pkg
        val p = OverlayProfileManager.profileFor(this, pkg)
        profile = p
        if (!userHidden) addPlayViews(p)
        ensureBubble()
        updateNotification()
    }

    private fun hideControls(removeBubble: Boolean) {
        removePlayViews()
        if (removeBubble) {
            removeBubble()
            shownFor = null
        }
        updateNotification()
    }

    private fun baseParams(w: Int, h: Int, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun addPlayViews(p: OverlayProfile) {
        val (sw, sh) = screenSize()
        val density = resources.displayMetrics.density
        val haptics = OverlayPrefs.haptics(this)
        for (el in p.elements) {
            val (w, h) = ElementGeometry.sizePx(el, density)
            val v = OverlayElementView(this, el, { profile ?: p }, dispatcher, haptics)
            val lp = baseParams(w, h).apply {
                x = (el.x * sw - w / 2f).toInt()
                y = (el.y * sh - h / 2f).toInt()
            }
            try {
                wm.addView(v, lp)
                playViews.add(v)
            } catch (t: Throwable) {
                Log.w(TAG, "addView failed", t)
            }
        }
    }

    private fun removePlayViews() {
        playViews.forEach { v ->
            v.releaseAll()
            runCatching { wm.removeViewImmediate(v) }
        }
        playViews.clear()
        dispatcher.releaseAll()
    }

    // ------------------------------------------------------------------ bubble

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun ensureBubble() {
        if (bubble != null || !canDraw()) return
        val density = resources.displayMetrics.density
        val size = (42 * density).toInt()
        val v = TextView(this).apply {
            text = "ONA"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 3, 136, 209))
                setStroke((1.5f * density).toInt(), Color.argb(220, 255, 255, 255))
            }
        }
        val lp = baseParams(size, size).apply {
            x = OverlayPrefs.bubbleX(this@OverlayControllerService)
            y = OverlayPrefs.bubbleY(this@OverlayControllerService)
        }
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = lp.x; py = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) + abs(dy) > 8 * density) moved = true
                    if (moved) {
                        lp.x = px + dx.toInt(); lp.y = py + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) OverlayPrefs.setBubble(this, lp.x, lp.y) else showBubbleMenu()
            }
            true
        }
        runCatching { wm.addView(v, lp); bubble = v }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeViewImmediate(it) } }
        bubble = null
    }

    private fun showBubbleMenu() {
        val pkg = shownFor ?: return
        val items = arrayOf(
            getString(R.string.ona_overlay_menu_edit),
            getString(if (userHidden) R.string.ona_overlay_menu_show else R.string.ona_overlay_menu_hide),
            getString(R.string.ona_overlay_menu_profile),
            getString(R.string.ona_overlay_menu_stop)
        )
        overlayDialog(
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(profile?.name ?: "ONA")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> openEditor(pkg)
                        1 -> {
                            userHidden = !userHidden
                            showControls(pkg, force = true)
                        }
                        2 -> pickProfile { p ->
                            OverlayProfileManager.setAppProfile(this, pkg, p.id)
                        }
                        3 -> {
                            OverlayPrefs.setEnabled(this, false)
                            stopSelf()
                        }
                    }
                }
        )
    }

    // ------------------------------------------------------------------ edit mode

    private fun openEditor(pkg: String?) {
        if (!canDraw()) {
            Toast.makeText(this, R.string.ona_overlay_need_overlay, Toast.LENGTH_LONG).show()
            return
        }
        closeEditor()
        main.removeCallbacks(hideRunnable)
        removePlayViews()
        val target = pkg ?: OverlayProfileManager.appMap(this).keys.firstOrNull()
        editorFor = target
        // edit a COPY: "discard" must leave the saved profile untouched
        val working = OverlayProfileManager.profileFor(this, target).deepCopy()
        val (sw, sh) = screenSize()
        val ed = OverlayEditorView(this, working, this)
        val lp = baseParams(sw, sh).apply { x = 0; y = 0 }
        runCatching { wm.addView(ed, lp); editor = ed }
            .onFailure { Log.w(TAG, "editor addView failed", it) }
        removeBubble()
    }

    private fun closeEditor() {
        editor?.let { runCatching { wm.removeViewImmediate(it) } }
        editor = null
    }

    private fun afterEditor() {
        closeEditor()
        val pkg = editorFor
        editorFor = null
        val fg = ForegroundAppMonitor.foreground.value
        when {
            fg != null && OverlayProfileManager.isTarget(this, fg) -> showControls(fg, force = true)
            pkg != null && fg == null -> showControls(pkg, force = true)
            else -> hideControls(removeBubble = true)
        }
    }

    override fun onSave(profile: OverlayProfile) {
        OverlayProfileManager.save(this, profile)
        editorFor?.let { OverlayProfileManager.setAppProfile(this, it, profile.id) }
        Toast.makeText(this, R.string.ona_overlay_saved, Toast.LENGTH_SHORT).show()
        afterEditor()
    }

    override fun onDiscard() = afterEditor()

    override fun onAdd() {
        val ed = editor ?: return
        ElementDialogs.chooseNewElement(this, ::overlayDialog) { el -> ed.addElement(el) }
    }

    override fun onEditElement(element: OverlayElement) {
        val ed = editor ?: return
        ElementDialogs.editElement(
            this, element, ::overlayDialog,
            onChanged = { ed.refresh() },
            onPickPoint = { ed.startPick(getString(R.string.ona_overlay_pick_hint)) { x, y -> element.tapX = x; element.tapY = y; ed.refresh() } }
        )
    }

    override fun onPickProfile() {
        val ed = editor ?: return
        pickProfile { p -> ed.setProfileAndRefresh(p.deepCopy()) }
    }

    /** Profile chooser (+ "new profile") shown over everything. */
    private fun pickProfile(onPicked: (OverlayProfile) -> Unit) {
        val list = OverlayProfileManager.profiles(this)
        val names = list.map { it.name } + getString(R.string.ona_overlay_new_profile)
        overlayDialog(
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.ona_overlay_menu_profile)
                .setItems(names.toTypedArray()) { _, which ->
                    if (which < list.size) onPicked(list[which]) else {
                        val input = EditText(this).apply { setText(R.string.ona_overlay_new_profile) }
                        overlayDialog(
                            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(R.string.ona_overlay_new_profile)
                                .setView(input)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    val base = editor?.profile ?: profile
                                    onPicked(OverlayProfileManager.create(this, input.text.toString().ifBlank { "Profile" }, base))
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                        )
                    }
                }
        )
    }

    /** AlertDialogs from a service must be overlay windows themselves. */
    private fun overlayDialog(b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        d.show()
        return d
    }

    // ------------------------------------------------------------------ backend / notification

    private fun reloadBackend() {
        dispatcher.releaseAll()
        backend.stop()
        backend = when (OverlayPrefs.backend(this)) {
            BackendType.PC_BRIDGE -> TcpBridgeBackend(OverlayPrefs.host(this), OverlayPrefs.port(this))
            BackendType.ACCESSIBILITY_TOUCH -> com.onalauncher.controls.input.NoRootBackend(this)
            BackendType.ROOT_SHELL -> RootShellBackend()
        }
        backend.start()
        dispatcher.backend = backend
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.ona_overlay_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFY_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayControllerService::class.java).setAction(ACTION_STOP), flags)
        val edit = PendingIntent.getService(this, 2, Intent(this, OverlayControllerService::class.java).setAction(ACTION_EDIT), flags)
        val open = PendingIntent.getActivity(this, 3, Intent(this, com.onalauncher.controls.ui.OverlayControlsActivity::class.java), flags)
        val text = buildString {
            append(shownFor?.let { getString(R.string.ona_overlay_notify_on, it) } ?: getString(R.string.ona_overlay_notify_waiting))
            val s = backend.status()
            if (s.isNotEmpty()) append(" · ").append(s)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_settings_filled)
            .setContentTitle(getString(R.string.ona_overlay_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.ona_overlay_menu_edit), edit)
            .addAction(0, getString(R.string.ona_overlay_menu_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFY_ID, buildNotification())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // rotation / resolution change: fractions stay, pixels change -> rebuild
        main.post {
            if (editor != null) {
                val pkg = editorFor
                val working = editor!!.profile
                closeEditor()
                val (sw, sh) = screenSize()
                val ed = OverlayEditorView(this, working, this)
                editorFor = pkg
                runCatching { wm.addView(ed, baseParams(sw, sh)); editor = ed }
            } else shownFor?.let { showControls(it, force = true) }
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        closeEditor()
        removePlayViews()
        removeBubble()
        poller.stop()
        backend.stop()
        scope.cancel()
        super.onDestroy()
    }
}
