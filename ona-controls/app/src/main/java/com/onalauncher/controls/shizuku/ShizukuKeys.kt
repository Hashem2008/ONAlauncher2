/*
 * ONA Controls - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.onalauncher.controls.IInjector
import rikka.shizuku.Shizuku

/**
 * App side of the Shizuku key injector. States the UI shows:
 *  NOT_INSTALLED -> NOT_RUNNING (open Shizuku, start it) -> NO_PERMISSION (allow) -> READY.
 * Binds [InjectorService] once Shizuku is running and allowed; rebinds after Shizuku restarts.
 */
object ShizukuKeys {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 7701

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    @Volatile private var injector: IInjector? = null
    @Volatile var probe: String = ""
        private set
    private var appContext: Context? = null
    private var binding = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext!!.packageName, InjectorService::class.java.name))
            .daemon(false)
            .processNameSuffix("injector")
            .debuggable(false)
            .version(2) // bump when IInjector changes, so Shizuku restarts the service with the new code
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            val i = service?.takeIf { it.pingBinder() }?.let { IInjector.Stub.asInterface(it) }
            injector = i
            probe = runCatching { i?.probe() }.getOrNull() ?: "no binder"
            Log.i("OnaOverlay", "Shizuku injector connected: $probe")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            injector = null
        }
    }

    private val onBinder = Shizuku.OnBinderReceivedListener { ensureBound() }
    private val onDead = Shizuku.OnBinderDeadListener { injector = null; binding = false }
    private var listening = false

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
        if (!listening) {
            listening = true
            Shizuku.addBinderReceivedListenerSticky(onBinder)
            Shizuku.addBinderDeadListener(onDead)
        }
        ensureBound()
    }

    fun state(ctx: Context): State {
        val installed = try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
        } catch (_: PackageManager.NameNotFoundException) { false }
        if (!Shizuku.pingBinder()) return if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
        if (Shizuku.isPreV11()) return State.NOT_RUNNING
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) State.READY else State.NO_PERMISSION
    }

    fun requestPermission() {
        runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE) }
    }

    fun ensureBound() {
        if (appContext == null || injector != null || binding) return
        val ok = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!ok) return
        binding = true
        runCatching { Shizuku.bindUserService(args, conn) }.onFailure {
            binding = false
            Log.w("OnaOverlay", "bindUserService failed", it)
        }
    }

    val ready: Boolean get() = injector != null

    const val MOUSE_MOVE = 0
    const val MOUSE_PRESS = 1
    const val MOUSE_RELEASE = 2
    const val MOUSE_WHEEL = 3

    /** Returns true if the mouse event was injected. */
    fun mouse(kind: Int, x: Float, y: Float, button: Int, value: Float): Boolean {
        val i = injector ?: run { ensureBound(); return false }
        return try {
            i.mouse(kind, x, y, button, value)
        } catch (_: Throwable) {
            injector = null
            false
        }
    }

    /** Returns true if the key was injected. */
    fun key(code: Int, down: Boolean, meta: Int, downTime: Long): Boolean {
        val i = injector ?: run { ensureBound(); return false }
        return try {
            i.key(code, down, meta, downTime)
        } catch (_: Throwable) {
            injector = null
            false
        }
    }
}
