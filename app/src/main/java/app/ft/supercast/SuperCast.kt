package app.ft.supercast

import android.os.Bundle
import android.view.Surface
import app.ft.FTApp
import app.ft.core.DiagLog
import app.ft.core.Root
import app.ft.server.IFtClient
import app.ft.server.IFtServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

object SuperCast {
    private const val TAG = "SuperCast"

    val token: String
        get() {
            val stored = FTApp.instance.prefs.supercastToken
            return if (stored.isNotEmpty()) stored else freshToken()
        }
    @Volatile private var server: IFtServer? = null
    @Volatile var helperUid: Int = -1

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** True while [quickSetup] is actively trying (Shizuku wake, wait, start) - for a
     * loading state on the car screen, so a tap that can't open anything yet still shows
     * something happened instead of looking like it did nothing. */
    private val _settingUp = MutableStateFlow(false)
    val settingUp: StateFlow<Boolean> = _settingUp

    /** True for a moment after [quickStop] fires - there is no reliable signal for "Shizuku
     * has now actually stopped" to wait on the way [quickSetup] polls for READY, and
     * [ready] itself does not change (the helper outlives Shizuku on purpose), so without
     * this a tap to stop looks identical to a tap that did nothing. */
    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping

    val client: IFtClient = object : IFtClient.Stub() {
        override fun onServer(bound: IFtServer?, sent: String?) {
            if (sent != token) {
                DiagLog.w(TAG, "a helper offered itself with the wrong token, ignoring it")
                return
            }
            server = bound
            _ready.value = bound != null
            DiagLog.i(TAG, "the SuperCast helper is connected")
        }

        override fun onEvent(event: Bundle?) {
            DiagLog.i(TAG, "helper event: ${event?.getString("event")}")
        }

        override fun onAudio(pcm: ByteArray?, sampleRate: Int, channels: Int) = Unit
    }

    fun freshToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val made = bytes.joinToString("") { "%02x".format(it) }
        FTApp.instance.prefs.supercastToken = made
        return made
    }

    fun drop() {
        server = null
        _ready.value = false
    }

    fun call(request: Bundle): Bundle? {
        val bound = server ?: return null
        request.putString("token", token)
        return runCatching { bound.call(request) }
            .onFailure { DiagLog.w(TAG, "the helper did not answer: ${it.message}"); drop() }
            .getOrNull()
    }

    private fun callWith(surface: Surface, request: Bundle): Bundle? {
        val bound = server ?: return null
        request.putString("token", token)
        return runCatching { bound.withSurface(surface, request) }
            .onFailure { DiagLog.w(TAG, "the helper did not answer: ${it.message}"); drop() }
            .getOrNull()
    }

    private fun ask(what: String, build: Bundle.() -> Unit = {}): Bundle? =
        call(Bundle().apply { putString("do", what); build() })

    fun ping(): Boolean = ask("ping")?.getString("pong") == "ft"

    fun openPane(name: String, surface: Surface, width: Int, height: Int, dpi: Int, packageName: String?): Int {
        val answer = callWith(surface, Bundle().apply {
            putString("do", "open")
            putString("name", name)
            putInt("width", width)
            putInt("height", height)
            putInt("dpi", dpi)
            if (!packageName.isNullOrEmpty()) putString("package", packageName)
        }) ?: return -1
        return answer.getInt("display", -1)
    }

    fun closePane(name: String) {
        ask("close") { putString("name", name) }
    }

    fun detachPane(name: String) {
        ask("detach") { putString("name", name) }
    }

    fun launch(name: String, packageName: String): Boolean =
        ask("launch") { putString("name", name); putString("package", packageName) }?.getBoolean("ok") == true

    private val queue = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ft-supercast").apply { isDaemon = true }
    }

    fun later(block: () -> Unit) {
        runCatching { queue.execute { runCatching(block).onFailure { DiagLog.w(TAG, "helper call failed: ${it.message}") } } }
    }

    /**
     * Wakes Shizuku (if it's the Shizuku-Next fork, which answers this broadcast with no
     * app open and no manual tap) and sets the helper up, from one place FT itself
     * controls — no Tasker, no touching the phone. Meant to be called from the car
     * screen's dock, e.g. the first tap on a tile after a cold start / debug cycle.
     */
    fun quickSetup(context: android.content.Context) {
        // A tap while one is already running just joins it instead of queueing a second
        // full attempt behind it - otherwise every extra tap while the car screen looks
        // unresponsive makes the actual wait longer, which is the opposite of helpful.
        if (!_settingUp.compareAndSet(false, true)) return
        later {
            try {
                // The helper can already be alive and fine even though ready reads false
                // (see start()'s own ping check) - but by the time start() would catch that,
                // this function has already woken Shizuku and possibly waited 8s for it, for
                // nothing. Checking here first means a tap when nothing actually needs doing
                // never touches Shizuku at all.
                if (ping()) {
                    _ready.value = true
                    DiagLog.i(TAG, "apps on the car were already running, no need to wake Shizuku")
                    return@later
                }
                ShizukuBridge.start(context)
                // Shizuku takes real time to come up after the broadcast - start its service,
                // connect wireless debug, bind its permission-check binder - so poll instead of
                // a single short sleep, and log every state so a silent skip is never a mystery.
                var state = ShizukuBridge.state(context)
                var waited = 0
                while (state != ShizukuBridge.State.READY && waited < 8000) {
                    DiagLog.i(TAG, "waiting for Shizuku: $state")
                    Thread.sleep(500)
                    waited += 500
                    state = ShizukuBridge.state(context)
                }
                DiagLog.i(TAG, "Shizuku state after waiting: $state")
                start(context.applicationContext)
            } finally {
                _settingUp.value = false
            }
        }
    }

    /**
     * Puts Shizuku away without touching the helper at all - no [stopAll] (which would
     * close the open panes) and no [Helper.stop] (which would kill the process). The
     * helper outlives whatever started it; Shizuku was only ever the way to get it
     * running in the first place, and once it is up there is nothing left for Shizuku
     * to do, so stopping it here is purely "don't leave it running for no reason" and
     * apps on the car keep working exactly as before.
     */
    fun quickStop(context: android.content.Context) {
        if (!_stopping.compareAndSet(false, true)) return
        later {
            try {
                val intent = android.content.Intent("moe.shizuku.privileged.api.STOP")
                    .setPackage(ShizukuBridge.PACKAGE_NAME)
                    .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                context.sendBroadcast(intent)
                DiagLog.i(TAG, "asked Shizuku to stop")
                // The broadcast itself returns instantly, well before Shizuku has actually
                // torn down - this is just long enough for the tap to visibly register.
                Thread.sleep(1200)
            } catch (t: Throwable) {
                DiagLog.w(TAG, "could not ask Shizuku to stop: ${t.message}")
            } finally {
                _stopping.value = false
            }
        }
    }

    private val touchThread = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ft-supercast-touch").apply { isDaemon = true }
    }
    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)

    fun touch(name: String, action: Int, x: Float, y: Float, fromWidth: Int, fromHeight: Int) {
        if (server == null) return
        val moving = action == android.view.MotionEvent.ACTION_MOVE
        if (moving && waiting.get() > 2) return
        waiting.incrementAndGet()
        runCatching {
            touchThread.execute {
                try {
                    ask("touch") {
                        putString("name", name)
                        putInt("action", action)
                        putFloat("x", x)
                        putFloat("y", y)
                        putInt("from", fromWidth)
                        putInt("fromHeight", fromHeight)
                    }
                } finally {
                    waiting.decrementAndGet()
                }
            }
        }.onFailure { waiting.decrementAndGet() }
    }

    fun key(name: String, keyCode: Int) {
        ask("key") { putString("name", name); putInt("key", keyCode) }
    }

    /** Injects a key with no pane and no display targeting - wherever a real hardware
     * key of the same code would go, car screen or not. */
    fun globalKey(keyCode: Int): Boolean =
        ask("globalkey") { putInt("key", keyCode) }?.getBoolean("ok") == true

    /** True only if Gemini's own voice-assistant bubble is what's actually resumed right
     * now - checked before a discard key goes out, so it never lands on whatever else
     * happens to be focused (Maps, FT's own screen) if opening the assistant silently
     * failed. */
    fun assistantIsOnTop(): Boolean {
        val answer = ask("topfocus") ?: return false
        val pkg = answer.getString("package") ?: return false
        val cls = answer.getString("class") ?: ""
        return pkg == "com.google.android.googlequicksearchbox" && (cls.contains("floaty", true) || cls.contains("robin", true))
    }

    fun apps(): List<Pair<String, String>> {
        val answer = ask("apps") ?: return emptyList()
        val packages = answer.getStringArrayList("packages") ?: return emptyList()
        val labels = answer.getStringArrayList("labels") ?: return emptyList()
        return packages.mapIndexed { index, pkg -> pkg to (labels.getOrNull(index) ?: pkg) }
    }

    fun phoneScreen(on: Boolean) {
        ask("screen") { putBoolean("on", on) }
    }

    fun manageScreen(darkenByItself: Boolean) {
        ask("screen") { putBoolean("on", false); putBoolean("auto", darkenByItself) }
    }

    fun powerPressed() {
        ask("power")
    }

    fun keepAwake(on: Boolean, wake: Boolean = false) {
        ask("awake") { putBoolean("on", on); putBoolean("wake", wake) }
    }

    fun stopAll() {
        ask("stop")
        drop()
    }

    fun start(context: android.content.Context): Boolean {
        if (_ready.value) return true
        // ready can read false without the helper actually being gone - FT's own process
        // can be frozen/trimmed and come back with this flag's in-memory value lost while
        // the detached helper (which answers to no app's lifecycle) kept running the whole
        // time. startWithShizuku/startWithRoot both pkill the helper unconditionally before
        // relaunching it, so skipping straight to those would kill a perfectly working
        // session just to rebuild the same thing - a ping first avoids that for free.
        if (ping()) {
            _ready.value = true
            DiagLog.i(TAG, "apps on the car were already running, reconnected without restarting anything")
            return true
        }
        val fresh = freshToken()
        if (Root.ensure()) {
            if (Helper.startWithRoot(context, fresh) { _ready.value }) {
                FTApp.instance.prefs.supercastWay = WAY_ROOT
                DiagLog.i(TAG, "apps on the car are ready, set up with root")
                return true
            }
            DiagLog.w(TAG, "root is there but the helper did not come back to FT")
        }
        if (ShizukuBridge.state(context) == ShizukuBridge.State.READY) {
            if (Helper.startWithShizuku(context, fresh) { _ready.value }) {
                FTApp.instance.prefs.supercastWay = WAY_SHIZUKU
                DiagLog.i(TAG, "apps on the car are ready, set up through Shizuku")
                return true
            }
            DiagLog.w(TAG, "Shizuku is there but the helper did not come back to FT")
        }
        return when (Helper.startWithAdb(context, fresh)) {
            Helper.Adb.STARTED -> {
                val up = waitForHandshake()
                if (up) {
                    FTApp.instance.prefs.supercastWay = WAY_BRIDGE
                    DiagLog.i(TAG, "apps on the car are ready, set up through the phone's debug bridge")
                } else {
                    DiagLog.w(TAG, "the helper started over the debug bridge but did not come back to FT")
                }
                up
            }
            Helper.Adb.NOT_ALLOWED -> {
                DiagLog.w(TAG, "the phone has not allowed FT on its debug bridge yet")
                false
            }
            Helper.Adb.NO_BRIDGE -> {
                DiagLog.w(TAG, "the phone's debug bridge is not switched on, so FT cannot set this up on its own")
                false
            }
            Helper.Adb.FAILED -> false
        }
    }

    private fun waitForHandshake(): Boolean {
        repeat(24) {
            if (_ready.value) return true
            Thread.sleep(250)
        }
        return _ready.value
    }

    fun startOverBridge(context: android.content.Context): Boolean {
        val fresh = freshToken()
        val result = Helper.startWithAdb(context, fresh)
        DiagLog.i(TAG, "bridge start result: $result")
        return result == Helper.Adb.STARTED
    }

    const val WAY_NONE = 0
    const val WAY_ROOT = 1
    const val WAY_BRIDGE = 2
    const val WAY_SHIZUKU = 3
}
