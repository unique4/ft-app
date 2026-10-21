package app.ft.supercast

import android.content.Context
import android.content.pm.PackageManager
import app.ft.core.DiagLog
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Runs the SuperCast helper through Shizuku instead of a raw adb connection.
 *
 * Why this exists: FT's own [Helper.startWithAdb] talks to a fixed `127.0.0.1:5555`
 * (the legacy `adb tcpip` port) and does not survive a USB/Wireless debugging toggle —
 * every toggle tears down adbd's whole session, token included. Shizuku instead pairs
 * once through Android's real wireless-debugging protocol (the one "Pair device with
 * pairing code" uses) and keeps that trust in Android's own adb keystore, so it survives
 * debugging being switched off and back on, and reboots. This file does not reimplement
 * that protocol — it just asks an already-running, already-authorized Shizuku for a
 * shell, the same way `adb shell` would, and runs the exact same helper command
 * [Helper.run] already builds.
 */
object ShizukuBridge {
    enum class State { NOT_INSTALLED, NOT_RUNNING, UNAUTHORIZED, READY }

    const val PACKAGE_NAME = "moe.shizuku.privileged.api"
    private const val TAG = "ShizukuBridge"

    fun state(context: Context): State {
        return try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return State.NOT_INSTALLED
        }.let {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) return State.NOT_RUNNING
                if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    State.READY
                } else {
                    State.UNAUTHORIZED
                }
            } catch (_: Throwable) {
                State.NOT_RUNNING
            }
        }
    }

    fun requestPermission(code: Int = 7310) {
        try {
            rikka.shizuku.Shizuku.requestPermission(code)
        } catch (_: Throwable) {
        }
    }

    /** Runs a shell command the way `adb shell` would, through an already-authorized Shizuku. */
    fun exec(cmd: String, timeoutMs: Long = 6000L): Boolean {
        val binder = rikka.shizuku.Shizuku.getBinder() ?: run {
            DiagLog.w(TAG, "Shizuku has no binder, cannot run the helper")
            return false
        }
        return try {
            val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)
            Thread({
                try {
                    process.errorStream.use { pfd -> FileInputStream(pfd.fileDescriptor).readBytes() }
                } catch (_: Exception) {
                }
            }, "shizuku-stderr-drain").apply { isDaemon = true; start() }
            val finished = process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString())
            if (!finished) {
                // The helper backgrounds itself with `&`; this shell wrapper exiting on its
                // own timeout is expected and not a failure of the helper itself.
                DiagLog.i(TAG, "helper command still running after ${timeoutMs}ms, leaving it detached")
            }
            true
        } catch (t: Throwable) {
            DiagLog.w(TAG, "could not run the helper through Shizuku: ${t.message}")
            false
        }
    }
}
