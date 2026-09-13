package com.signalscope.collect

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shell-UID privileges without root, via Shizuku.
 *
 * Shizuku runs a process as uid 2000 that the user starts themselves through wireless debugging.
 * That UID holds MODIFY_PHONE_STATE and READ_PRECISE_PHONE_STATE, which is why it reaches a whole
 * measurement and actuation class we otherwise cannot -- see docs/device-findings.md.
 *
 * Everything here degrades to a reported state rather than an exception: Shizuku is optional, and
 * the app has to be completely usable without it.
 */
enum class ShizukuState(val label: String, val detail: String) {
    NOT_INSTALLED("not installed", "Shizuku is not on this device"),
    NOT_RUNNING("not running", "Installed, but the service has not been started since boot"),
    PERMISSION_NEEDED("not authorised", "Running — SignalScope has not been granted access"),
    DENIED("denied", "Access was refused"),
    READY("ready", "Shell-level access available")
}

object ShizukuBridge {

    val state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    private const val REQ = 8731

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQ) {
            state.value = if (result == PackageManager.PERMISSION_GRANTED)
                ShizukuState.READY else ShizukuState.DENIED
        }
    }

    fun init(ctx: Context) {
        runCatching { Shizuku.addRequestPermissionResultListener(permListener) }
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { refresh(ctx) }
            Shizuku.addBinderDeadListener { state.value = ShizukuState.NOT_RUNNING }
        }
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        val installed = runCatching {
            ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
        }.getOrDefault(false)

        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        state.value = when {
            !alive && !installed -> ShizukuState.NOT_INSTALLED
            !alive -> ShizukuState.NOT_RUNNING
            runCatching { Shizuku.checkSelfPermission() }.getOrNull() ==
                PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.PERMISSION_NEEDED
        }
    }

    fun requestPermission() {
        runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                state.value = ShizukuState.READY
            else Shizuku.requestPermission(REQ)
        }
    }

    data class Exec(val ok: Boolean, val code: Int, val out: String)

    /**
     * Run a shell command as uid 2000.
     *
     * `Shizuku.newProcess` is @RestrictTo rather than public API, so it is reached reflectively.
     * That is a deliberate, contained use: it is the documented way apps run shell commands through
     * Shizuku, and a failure here surfaces as [Exec.ok] = false rather than an exception.
     */
    suspend fun exec(cmd: String): Exec = withContext(Dispatchers.IO) {
        if (state.value != ShizukuState.READY) return@withContext Exec(false, -1, "shizuku not ready")
        runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                r.lineSequence().forEach { out.appendLine(it) }
            }
            BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                r.lineSequence().forEach { out.appendLine(it) }
            }
            val code = proc.waitFor()
            Exec(code == 0, code, out.toString().trim())
        }.getOrElse { Exec(false, -1, it.message ?: it.javaClass.simpleName) }
    }
}
