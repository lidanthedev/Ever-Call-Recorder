/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp.services

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import com.coolappstore.evercallrecorder.by.svhp.ILogCallback
import com.coolappstore.evercallrecorder.by.svhp.IShellService
import com.coolappstore.evercallrecorder.by.svhp.integrations.scrcpy.ScrcpyAudioCodec
import com.coolappstore.evercallrecorder.by.svhp.integrations.scrcpy.ScrcpyAudioSource
import com.coolappstore.evercallrecorder.by.svhp.integrations.scrcpy.ScrcpyConfig
import com.coolappstore.evercallrecorder.by.svhp.integrations.scrcpy.ServerExtractor
import com.coolappstore.evercallrecorder.by.svhp.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * ShellService runs inside the privileged shell process (UID 2000 or 0) managed by Shizuku.
 *
 * By running under the app shell as ADB or root via Shizuku, we can
 * launch scrcpy-server with app_process and capture audio that a normal app cannot access.
 *
 * AI generated Overview:
 *
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │  Shell Process (UID 2000 or 0)                              │
 *   │                                                             │
 *   │  ShellService (this class, AIDL stub)                       │
 *   │    │                                                        │
 *   │    ├── launches  scrcpy-server (app_process)                │
 *   │    │     └── connects to  LocalServerSocket                 │
 *   │    │                          │                             │
 *   │    ├── AudioRelayCoroutine ◄──┘  (socket → pipe)            │
 *   │    │         │                                              │
 *   │    │     Pipe[1] write-end (kept in Shell)                  │
 *   │    │     Pipe[0] read-end  ───────────────► App Process     │
 *   │    │                                          ScrcpyClient  │
 *   │    ├── LogConsumerCoroutine   (drain stdout)                │
 *   │    └── ProcessMonitorCoroutine (wait for exit)              │
 *   └─────────────────────────────────────────────────────────────┘
 *
 * Shizuku requirements:
 *  • Must have a no-arg constructor AND a single-Context constructor (Shizuku v13+).
 *  • Must be annotated with [@Keep] so ProGuard/R8 does not remove/rename the class.
 *  • [destroy] must call [exitProcess] to terminate the shell process when Shizuku asks.
 */
@Keep
class ShellService : IShellService.Stub {

    private companion object {
        const val TAG = "SCR:ShellService"

        /**
         * Size of the byte buffer used when copying data from the socket to the pipe.
         * 32 KB is a balance between latency (larger = more delay per flush) and syscall
         * overhead (smaller = more read/write pairs per second).  At 16 kbps Opus the server
         * produces ≈ 2 KB/s, so 32 KB means ≈ 16 s of audio per buffer; this is fine since
         * the relay loop flushes on every read().
         */
        const val RELAY_BUFFER_SIZE = 32 * 1024

        /**
         * How long to wait for scrcpy-server to finish writing its final bytes after we call
         * [Process.destroy].  Giving it a short grace period avoids truncating the last audio
         * frame if the server is encoding when the stop request arrives.
         */
        const val PROCESS_STOP_GRACE_PERIOD_SEC = 2L

        // ── Samsung system screen recorder automation (see docs/SHIZUKU_SYSTEM_RECORD.md) ──

        /** Package of Samsung's Smart Capture / Screen Recorder. */
        const val SAMSUNG_CAPTURE_PACKAGE = "com.samsung.android.app.smartcapture"

        /** QS tile component that starts/stops Samsung's screen recorder (phone variant). */
        const val SAMSUNG_RECORDER_TILE = "$SAMSUNG_CAPTURE_PACKAGE/com.samsung.android.app.screenrecorder.view.RecordScreenTile"

        /** Directory where Samsung's screen recorder writes its mp4 files. Readable by shell. */
        const val SAMSUNG_RECORDINGS_DIR = "/sdcard/DCIM/Screen recordings"

        /** Max time to wait for the media projection session to appear/disappear after a tile click. */
        const val SCREEN_RECORD_STATE_TIMEOUT_MS = 15_000L

        /** Poll interval while waiting for the media projection state to change. */
        const val SCREEN_RECORD_STATE_POLL_MS = 250L

        /** Max time to wait for the recorder confirmation dialog to gain window focus after the tile click. */
        const val SCREEN_RECORD_DIALOG_TIMEOUT_MS = 6_000L

        /** Poll interval while waiting for the confirmation dialog window to appear. */
        const val SCREEN_RECORD_DIALOG_POLL_MS = 200L

        /** Temp path for the uiautomator UI dump used to locate the dialog's positive button. */
        const val UI_DUMP_PATH = "/sdcard/ui.xml"

        /** Resource-id of the recorder dialog's positive ("Start recording") button. Locale-safe. */
        const val DIALOG_BUTTON_RESOURCE_ID = "android:id/button1"

        /** Fallback text of the dialog's positive button (localized — used only if the id is absent). */
        const val DIALOG_BUTTON_FALLBACK_TEXT = "Start recording"

        /** Resource id of the "Media and mic" audio option (clickable container) in the recorder dialog. */
        const val DIALOG_AUDIO_MEDIA_AND_MIC_ID = "radio_button_sound_all"
    }

    /**
     * Atomic flag that controls the relay loop and prevents concurrent sessions.
     *
     * Why [AtomicBoolean]?  [stopRecording] can be called from any thread (e.g. the AIDL
     * thread pool) while [spawnAudioRelayCoroutine]'s loop runs on a coroutine.  AtomicBoolean
     * provides a lock-free compare-and-set operation that is visible across threads without
     * needing a `synchronized` block.
     */
    private val isRecordingActive = AtomicBoolean(false)

    // -- System resources (all nullable; null = not allocated in current session)

    /** The running scrcpy-server child process. Null when not recording. */
    private var scrcpyProcess: Process? = null

    /**
     * The Unix-domain server socket that waits for scrcpy-server to connect.
     */
    private var serverSocket: LocalServerSocket? = null

    /** The accepted connection from scrcpy-server after it dials our server socket. */
    private var clientConnection: LocalSocket? = null

    /**
     * Write end of the kernel pipe.  The relay coroutine copies bytes from the scrcpy-server
     * socket into this end; the app process holds the read end wrapped in a [ParcelFileDescriptor].
     *
     * **IMPORTANT**: Do NOT close this before the scrcpy-server process exits. The server may be
     * buffering its final audio frame and will write it after receiving SIGTERM. Closing the
     * write end early would cause a broken-pipe error in the relay coroutine and truncate the
     * recording.
     */
    private var audioWriteEnd: ParcelFileDescriptor? = null

    // --- Coroutine infrastructure

    /**
     * Coroutine scope for all background work in this recording session.
     */
    private var shellScope: CoroutineScope? = null

    /**
     * The relay job that copy bytes of the socket to the pipe for downstream app.
     * We keep this [shellScope] job reference so we can wait for late bytes to be relayed when shutting down in [stopRecording].
     */
    private var audioPipeRelayJob: Job? = null

    // ---- Shizuku-required constructors

    /**
     * No-arg constructor required by older versions of Shizuku.
     */
    @Keep constructor() : this(null)

    /**
     * Context constructor required by Shizuku v13+ for user-service instantiation.
     * The context is the shell process's context (not the app's).
     *
     * @param context The shell-process [Context] provided by Shizuku, or null on older versions.
     */
    @Keep constructor(context: Context?) {
        Log.i(TAG,"===============================\n" +
             "ShellService process started!\n" +
             "Running as UID=(${android.os.Process.myUid()})\n" +
             "===============================")
    }

    // -------- IShellService AIDL implementation

    /**
     * Starts the audio-capture pipeline.  Called from the app process via Binder IPC.
     *
     * Steps performed in this method (all in the shell process):
     *  1. Guard: reject if already recording.
     *  2. Verify scrcpy-server JAR hash
     *  3. Create a kernel pipe; keep the write-end here, return the read-end to the app.
     *  4. Open a [LocalServerSocket] and start an audio-relay coroutine that calls accept().
     *  5. Build the `app_process` launch command with scrcpy arguments.
     *  6. Start the scrcpy-server child process.
     *  7. Start log-consumer and process-monitor coroutines as background helpers.
     *
     * @param audioSource        scrcpy audio_source parameter (e.g. "mic-voice-communication").
     * @param audioCodec         scrcpy audio_codec parameter (e.g. "opus", "aac").
     * @param audioBitRate       scrcpy audio_bit_rate in bps (e.g. 16000 for 16 kbps Opus).
     * @param serverPath      Absolute path to scrcpy-server.jar in shared storage.
     * @param enableVerboseLogging  When true, logs relay throughput every second.
     * @return The read-end [ParcelFileDescriptor] of the audio pipe, or null on failure.
     */
    override fun startRecording(
        audioSource: String,
        audioCodec: String,
        audioBitRate: Int,
        serverPath: String,
        isDebuggingModeEnabled: Boolean,
        listener: ILogCallback
    ): ParcelFileDescriptor? {
        AppLogger.initAsRemote(listener, isDebuggingModeEnabled)

        if (isRecordingActive.get()) {
            AppLogger.w(TAG,"startRecording() rejected: a session is already active")
            return null
        }

        try {
            AppLogger.i(TAG,"Initialising the ShellService recording pipeline...")

            // 1. Security check
            // Verify the JAR's SHA-256, before exec.
            // Checking in the shell process, try to reduce TOCTOU but not perfect.
            val serverJarFile = File(serverPath)
            if (!serverJarFile.exists() || !ServerExtractor.verifyServerHash(serverJarFile)) {
                AppLogger.w(TAG,"Server JAR absent or SHA-256 mismatch at $serverPath - aborting")
                return null
            }

            // 2. Create Kernel pipe for Server ShellService --> App Process communication
            // We send readEnd to the app process and keep writeEnd here.
            val pipe = ParcelFileDescriptor.createPipe()
            val pipeReadEnd  = pipe[0] // --> returned to app process
            val pipeWriteEnd = pipe[1] // --> written by ShellService relay coroutine
            audioWriteEnd = pipeWriteEnd

            // 3. Create Local Unix-domain Socket Server for the Scrcpy-Server binary
            val socketName = ScrcpyConfig.getRandomSocketName();
            // Scrcpy server binary always add ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX prefix to the socket name.
            val serverFullSocketName = ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX + socketName
            serverSocket = LocalServerSocket(serverFullSocketName)
            AppLogger.d(TAG,"Listening on abstract socket '$serverFullSocketName'")

            // Create the scope for this session's coroutines.
            // (Similar to async Tasks in c#, shellScope will contain and manage all these tasks.)
            shellScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            // Start the relay coroutine BEFORE launching scrcpy-server so the accept() call is
            // already waiting when the server connects.
            spawnAudioRelayCoroutine(isDebuggingModeEnabled)

            // 4. Build and launch scrcpy-server
            // Convert the raw String parameters received via AIDL into type-safe enum values
            // before calling buildServerArgs.
            val audioSourceEnum = ScrcpyAudioSource.fromKey(audioSource)
            val audioCodecEnum  = ScrcpyAudioCodec.fromKey(audioCodec)
            val serverArgs    = ScrcpyConfig.buildServerArgs(socketName, audioSourceEnum, audioCodecEnum, audioBitRate)
            val launchCommand = mutableListOf("app_process", "/", ScrcpyConfig.SERVER_MAIN_CLASS)
            launchCommand.addAll(serverArgs)

            val scrcpyBuilder = ProcessBuilder(launchCommand).apply {
                // CLASSPATH tells app_process where to find the server binary file.
                environment()["CLASSPATH"] = serverPath
                // Merge stderr into stdout so the log-consumer coroutine only needs one stream.
                redirectErrorStream(true)
            }
            scrcpyProcess = scrcpyBuilder.start()
            isRecordingActive.set(true)
            AppLogger.i(TAG,"scrcpy-server launched successfully")

            // 5. Start Helper coroutines
            spawnLogConsumerCoroutine(scrcpyProcess!!)
            spawnProcessMonitorCoroutine(scrcpyProcess!!)

            AppLogger.i(TAG,"Recording pipeline established. Returning pipe read-end to app process.")
            return pipeReadEnd // ← returned to RecordingForegroundService via Binder
        } catch (e: Exception) {
            AppLogger.e(TAG,"Critical failure during pipeline startup: ${e.message}", e)
            stopRecording() // Best-effort cleanup of any partially-allocated resources.
            return null
        }
    }

    /**
     * Stops the recording pipeline and releases all resources.  Called from the app process
     * via Binder IPC, and also from internal coroutines when an error is detected.
     *
     * Cleanup order matters:
     *  1. Set the atomic flag to false first so the relay loop exits on its next iteration.
     *  2. Destroy scrcpy-server; give it a grace period to flush its last audio bytes.
     *  3. Cancel the coroutine scope (interrupts blocking I/O calls in relay/log/monitor).
     *  4. Close the client connection so the relay coroutine's read() unblocks.
     *  5. Close the server socket.
     *  6. Close the pipe write-end LAST so scrcpy-server can write its final bytes first.
     */
    override fun stopRecording() {
        // compareAndSet(true, false): atomically checks that we ARE recording and clears the flag.
        // Returns false (and skips the body) if we were already stopped, preventing double-free.
        if (!isRecordingActive.compareAndSet(true, false)) {
            AppLogger.w(TAG, "stopRecording() called but no active session - skipping redundant cleanup")
            return
        }

        AppLogger.i(TAG,"Stopping scrcpy-server process...")
        runCatching { scrcpyProcess?.destroy() }

        // Give the server up to PROCESS_STOP_GRACE_PERIOD_SEC to send its final audio bytes to the server socket.
        // waitFor() blocks until the process exits or the timeout elapses.
        try {
            scrcpyProcess?.waitFor(PROCESS_STOP_GRACE_PERIOD_SEC, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            AppLogger.w(TAG, "Interrupted while waiting for scrcpy-server exit: ${e.message}")
        }

        // Wait for the relay job to copy remaining socket data to the pipe for downstream app (Time-bounded).
        AppLogger.d(TAG,"Waiting for relay coroutine to finish copying late bytes...")
        runCatching {
            kotlinx.coroutines.runBlocking {
                // Time-bounded wait to strictly prevent any infinite loop if EOF is never reached or take too long
                kotlinx.coroutines.withTimeoutOrNull(2000L) { // 2s timeout
                    audioPipeRelayJob?.join()
                }
            }
        }

        AppLogger.d(TAG,"Cancelling all jobs running from the shell coroutine scope...")
        // This stops the execution of all of our coroutines tasks/threads.
        runCatching { shellScope?.cancel() }

        // Close in reverse-allocation order. The client socket connection should already have by closed
        // by scrcpy-server when it was requested to close itself, but we close it again here to force its closure if it's still open.
        AppLogger.d(TAG,"Ensuring client socket connection is closed...")
        runCatching { clientConnection?.close() }

        AppLogger.d(TAG,"Closing server socket...")
        runCatching { serverSocket?.close() }

        // Close the pipe write-end AFTER the process has had a chance to write final bytes.
        AppLogger.d(TAG,"Closing audio pipe write-end...")
        runCatching { audioWriteEnd?.close() }

        // Null out references to help GC.
        scrcpyProcess = null
        clientConnection  = null
        serverSocket      = null
        audioWriteEnd     = null
        shellScope        = null
        audioPipeRelayJob = null

        AppLogger.i(TAG,"Recording pipeline stopped and all ShellService resources were released")
    }

    /** Returns whether a recording session is currently active (thread-safe via AtomicBoolean). */
    override fun isRecording(): Boolean = isRecordingActive.get()

    // ── Samsung system screen recorder automation ─────────────────────────────────────────
    //
    // Ports docs/record.sh into the shell process. The direct service path is permission-gated
    // (signatureOrSystem), so we drive the Quick Settings tile instead: SystemUI performs the
    // click with its own privileges. The "Start recording" confirmation dialog is accepted
    // shell-side too: wait for the dialog window focus, then a single `uiautomator dump`,
    // parse the positive button's bounds and `input tap` its center.

    /**
     * Runs a shell command line in this shell process and returns its combined stdout.
     * The output and exit code are always logged: these commands drive the screen recorder
     * flow and their failures are otherwise invisible.
     * @return The command output, or null if the command could not be executed.
     */
    private fun execShellCommand(command: String): String? {
        return try {
            val process = ProcessBuilder("sh", "-c", command).apply {
                // The Shizuku user-service process may inherit a minimal environment; make sure
                // the standard tool directories are on PATH for `cmd`, `settings`, `dumpsys`, `input`.
                environment()["PATH"] = "/system/bin:/system/xbin:/product/bin:/apex/com.android.art/bin:" +
                    (environment()["PATH"] ?: "")
                redirectErrorStream(true)
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            AppLogger.d(TAG, "execShellCommand('$command') exit=$exitCode output='${output.trim().take(300)}'")
            output
        } catch (e: Exception) {
            AppLogger.w(TAG, "execShellCommand('$command') failed: ${e.message}")
            null
        }
    }

    /**
     * True while `dumpsys media_projection` reports an active session owned by smartcapture.
     * An idle system prints "null" right after the "Media Projection:" header.
     */
    override fun isScreenRecordingActive(): Boolean {
        val dump = execShellCommand("dumpsys media_projection") ?: return false
        return dump.contains(SAMSUNG_CAPTURE_PACKAGE)
    }

    /**
     * Ensures the Screen recorder tile is present in the Quick Settings layout.
     * `cmd statusbar click-tile` silently no-ops when the user removed the tile.
     * Checked only once per shell-process lifetime to keep session starts fast.
     */
    private var recorderTileEnsured = false

    private fun ensureRecorderTilePresent() {
        if (recorderTileEnsured) return
        val spec = "custom($SAMSUNG_RECORDER_TILE)"
        val current = execShellCommand("settings get secure sysui_qs_tiles")?.trim().orEmpty()
        if (spec !in current) {
            AppLogger.i(TAG, "Screen recorder tile missing from QS layout, appending it")
            execShellCommand("settings put secure sysui_qs_tiles \"$current,$spec\"")
            Thread.sleep(1000)
        }
        recorderTileEnsured = true
    }

    /**
     * Waits for Samsung's recorder confirmation dialog and taps its positive button.
     *
     * Instead of a separate `dumpsys window` focus poll followed by a single dump (record.sh
     * style), this loops `uiautomator dump` directly: on the fast path the dialog is already in
     * the first dump (~0.5–1 s saved per start). Matches the button by resource-id first
     * (locale-safe), with the (localized) text as a fallback.
     *
     * Bounds-parsing pitfall (docs/SHIZUKU_SYSTEM_RECORD.md): numbers must be extracted ONLY
     * from the `bounds="[x1,y1][x2,y2]"` attribute of the SAME node tag that carries the id —
     * a naive `[0-9]+` over the whole document picks up the stray `1` from `android:id/button1`
     * and offsets the tap.
     *
     * @return true if the button appeared and was tapped within the timeout.
     */
    private fun confirmRecorderDialogWhenItAppears(): Boolean {
        val deadline = System.currentTimeMillis() + SCREEN_RECORD_DIALOG_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            execShellCommand("uiautomator dump $UI_DUMP_PATH")
            val xml = execShellCommand("cat $UI_DUMP_PATH")
            if (xml != null) {
                val nodeTags = Regex("<node[^>]*>").findAll(xml).map { it.value }

                // The "Media and mic" audio option must be selected, otherwise the recording is
                // silent on one or both sides. The checked state lives on the child RadioButton
                // (the node right after the option's container in document order); the container
                // is the clickable one. If it is not selected, tap it and re-verify on the next
                // loop iteration before tapping Start.
                val audioTag = nodeTags.firstOrNull { it.contains("resource-id=\"$SAMSUNG_CAPTURE_PACKAGE:id/$DIALOG_AUDIO_MEDIA_AND_MIC_ID\"") }
                if (audioTag != null && !isMediaAndMicSelected(xml)) {
                    val bounds = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").find(audioTag)
                    if (bounds != null) {
                        val (x1, y1, x2, y2) = bounds.destructured
                        val cx = (x1.toInt() + x2.toInt()) / 2
                        val cy = (y1.toInt() + y2.toInt()) / 2
                        AppLogger.i(TAG, "'Media and mic' not selected, tapping it at ($cx, $cy)")
                        execShellCommand("input tap $cx $cy")
                        Thread.sleep(SCREEN_RECORD_DIALOG_POLL_MS)
                        continue
                    }
                    AppLogger.w(TAG, "Could not locate 'Media and mic' option bounds, proceeding with current audio setting")
                }

                val buttonTag = nodeTags.firstOrNull { it.contains("resource-id=\"$DIALOG_BUTTON_RESOURCE_ID\"") }
                    ?: nodeTags.firstOrNull { it.contains("text=\"$DIALOG_BUTTON_FALLBACK_TEXT\"") }
                if (buttonTag != null) {
                    val bounds = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").find(buttonTag)
                    if (bounds == null) {
                        AppLogger.w(TAG, "No bounds attribute on the recorder dialog button node")
                        return false
                    }
                    val (x1, y1, x2, y2) = bounds.destructured
                    val cx = (x1.toInt() + x2.toInt()) / 2
                    val cy = (y1.toInt() + y2.toInt()) / 2
                    AppLogger.i(TAG, "Tapping recorder dialog positive button at ($cx, $cy)")
                    execShellCommand("input tap $cx $cy")
                    return true
                }
            }
            Thread.sleep(SCREEN_RECORD_DIALOG_POLL_MS)
        }
        AppLogger.w(TAG, "Recorder dialog positive button never appeared (device locked?)")
        return false
    }

    /**
     * True when the recorder dialog's "Media and mic" option is currently selected.
     * The option container ($DIALOG_AUDIO_MEDIA_AND_MIC_ID) is not itself checkable; the state
     * is on the child `android.widget.RadioButton` node that follows it in document order.
     * Defaults to true when the structure is not recognized so we never block the recording.
     */
    private fun isMediaAndMicSelected(xml: String): Boolean {
        val idPos = xml.indexOf(DIALOG_AUDIO_MEDIA_AND_MIC_ID)
        if (idPos < 0) return true
        val after = xml.substring(idPos)
        val radioTag = Regex("<node[^>]*RadioButton[^>]*>").find(after)?.value ?: return true
        return radioTag.contains("checked=\"true\"")
    }

    /**
     * Screen resolution as "WxH" parsed from `wm size`, cached per process. Used to tap the
     * upper-center of the screen to skip Samsung's 3-second pre-recording countdown (verified
     * on One UI 8 / Android 16: a tap during the countdown starts the session immediately,
     * ~2.3 s saved).
     */
    private var cachedScreenCenter: Pair<Int, Int>? = null

    private fun getCountdownSkipTapPoint(): Pair<Int, Int> {
        cachedScreenCenter?.let { return it }
        val size = execShellCommand("wm size")?.trim().orEmpty()
        val match = Regex("(\\d+)x(\\d+)").find(size)
        val point = if (match != null) {
            val (w, h) = match.destructured
            // Screen center: verified on-device that only center taps skip the countdown
            // (upper-third taps were ignored by the overlay). Center of an in-call screen is
            // the contact/avatar area, so a stray pass-through tap is harmless there.
            Pair(w.toInt() / 2, h.toInt() / 2)
        } else {
            Pair(720, 1560) // Center fallback for 1440x3120-class devices.
        }
        cachedScreenCenter = point
        return point
    }

    /**
     * Sends taps that skip Samsung's ~3 s pre-recording countdown (verified on-device:
     * ~1.4 s vs ~3.7 s to projection-active). Multiple spaced taps because the overlay's
     * appearance time varies — a single tap can land before it is touchable. Best-effort:
     * if all taps miss, the countdown simply runs its course and the projection poll below
     * still catches the session start.
     */
    private fun skipRecorderCountdown() {
        val (x, y) = getCountdownSkipTapPoint()
        repeat(3) { attempt ->
            Thread.sleep(350)
            AppLogger.d(TAG, "Tapping ($x, $y) to skip the recorder countdown (attempt ${attempt + 1})")
            execShellCommand("input tap $x $y")
        }
    }

    /**
     * Starts Samsung's screen recorder: wake screen, ensure tile, click tile, wait for the
     * confirmation dialog, tap its positive button, then poll `dumpsys media_projection` until
     * the recorder session is active. May block up to ~20 s total.
     *
     * Safety: refuses to click when a session is already active, because the tile toggles —
     * clicking would STOP the user's own ongoing screen recording instead.
     *
     * @return true if the projection session became active within the timeout.
     */
    override fun startScreenRecording(): Boolean {
        if (isScreenRecordingActive()) {
            AppLogger.w(TAG, "startScreenRecording() refused: a screen recording is already active (tile toggles, clicking would stop it)")
            return false
        }

        // Best-effort screen wake. If the keyguard is still locked the dialog never gains focus
        // and the waits below time out.
        execShellCommand("input keyevent KEYCODE_WAKEUP")

        ensureRecorderTilePresent()

        AppLogger.i(TAG, "Clicking screen recorder tile")
        execShellCommand("cmd statusbar click-tile $SAMSUNG_RECORDER_TILE")

        if (!confirmRecorderDialogWhenItAppears()) {
            AppLogger.w(TAG, "Failed to confirm the recorder dialog")
            return false
        }

        skipRecorderCountdown()

        val deadline = System.currentTimeMillis() + SCREEN_RECORD_STATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isScreenRecordingActive()) {
                AppLogger.i(TAG, "Screen recorder session is active")
                return true
            }
            Thread.sleep(SCREEN_RECORD_STATE_POLL_MS)
        }
        AppLogger.w(TAG, "Timed out waiting for the screen recorder session to become active")
        return false
    }

    /**
     * Stops Samsung's screen recorder via the same tile (it toggles), then verifies the session
     * actually ended. Never clicks when no session is active, so it can never START a recording.
     *
     * @return true if no session is active when this method returns (stopped or never ran).
     */
    override fun stopScreenRecording(): Boolean {
        if (!isScreenRecordingActive()) {
            AppLogger.d(TAG, "stopScreenRecording(): no active session, nothing to stop")
            return true
        }

        AppLogger.i(TAG, "Clicking screen recorder tile to stop the session")
        execShellCommand("cmd statusbar click-tile $SAMSUNG_RECORDER_TILE")

        val deadline = System.currentTimeMillis() + SCREEN_RECORD_STATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!isScreenRecordingActive()) {
                AppLogger.i(TAG, "Screen recorder session stopped")
                return true
            }
            Thread.sleep(SCREEN_RECORD_STATE_POLL_MS)
        }
        AppLogger.w(TAG, "Screen recorder session still active after stop click")
        return false
    }

    /**
     * Finds the newest finalized recording in [SAMSUNG_RECORDINGS_DIR] with mtime >= [minEpochSec].
     * Hidden ".pending-*.mp4" files are skipped: Samsung writes the recording under that name
     * while finalizing and renames it to the final name only once the file is complete.
     */
    private fun findLatestScreenRecording(minEpochSec: Long): File? {
        return File(SAMSUNG_RECORDINGS_DIR).listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.extension.equals("mp4", ignoreCase = true) && it.lastModified() / 1000 >= minEpochSec }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * Opens the newest file in [SAMSUNG_RECORDINGS_DIR] with mtime >= [minEpochSec] and returns
     * a read-only FD for it. The mtime guard prevents reporting an older, unrelated recording.
     *
     * @return A read-only [ParcelFileDescriptor], or null if no matching file exists.
     */
    override fun openLatestScreenRecording(minEpochSec: Long): ParcelFileDescriptor? {
        val newest = findLatestScreenRecording(minEpochSec)
        if (newest == null) {
            AppLogger.w(TAG, "No new screen recording found in $SAMSUNG_RECORDINGS_DIR (minEpochSec=$minEpochSec)")
            return null
        }
        return try {
            AppLogger.i(TAG, "Opening recorded file: ${newest.absolutePath} (${newest.length()} bytes)")
            ParcelFileDescriptor.open(newest, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to open ${newest.absolutePath}", e)
            null
        }
    }

    /**
     * Deletes the newest finalized recording with mtime >= [minEpochSec] (same selection rule as
     * [openLatestScreenRecording]). Used to remove the source video after its audio track was
     * successfully imported into the app library.
     *
     * @return true if a file was deleted.
     */
    override fun deleteLatestScreenRecording(minEpochSec: Long): Boolean {
        val newest = findLatestScreenRecording(minEpochSec) ?: return false
        return try {
            val deleted = newest.delete()
            if (deleted) {
                AppLogger.i(TAG, "Deleted source screen recording: ${newest.absolutePath}")
            } else {
                AppLogger.w(TAG, "Failed to delete source screen recording: ${newest.absolutePath}")
            }
            deleted
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error deleting ${newest.absolutePath}", e)
            false
        }
    }

    /**
     * Called by Shizuku when it wants to shut down this user service.
     * MUST call [exitProcess] so the entire shell process is terminated; otherwise Shizuku may
     * be unable to clean up the process, and it will linger in memory.
     */
    override fun destroy() {
        AppLogger.i(TAG,"ShellService.destroy() – terminating shell process")
        stopRecording()
        exitProcess(0)
    }

    // -- Private coroutine helpers

    /**
     * Launches a coroutine that:
     *  1. Calls [LocalServerSocket.accept] to wait for scrcpy-server to connect.
     *  2. Copies all bytes from the socket input stream into the pipe write-end.
     *  3. Calls [stopRecording] when the loop exits so the app-side muxer is notified.
     *
     * @param verbose When true, logs relay throughput roughly every second.
     */
    private fun spawnAudioRelayCoroutine(verbose: Boolean) {
        audioPipeRelayJob = shellScope?.launch(Dispatchers.IO) {
            try {
                // accept() blocks until scrcpy-server dials our socket.
                // This is safe on Dispatchers.IO because IO threads are designed for blocking calls.
                AppLogger.d(TAG,"AudioRelayCoroutine: waiting for scrcpy-server connection...")
                val connection = serverSocket?.accept() ?: run {
                    AppLogger.w(TAG, "AudioRelayCoroutine: server socket was null or closed")
                    return@launch
                }
                clientConnection = connection
                AppLogger.i(TAG,"AudioRelayCoroutine: scrcpy-server connected to our socket server")

                val sourceStream = connection.inputStream
                // AutoCloseOutputStream closes the underlying ParcelFileDescriptor on close(), which
                // will tell make it so the ReadPipe we returned to the downstream app reports EOF.
                // We do NOT use .use{} here because audioWriteEnd is shared – it is closed
                // explicitly in stopRecording() AFTER the scrscpy-server process exits.
                val destinationStream = ParcelFileDescriptor.AutoCloseOutputStream(audioWriteEnd)

                val buffer = ByteArray(RELAY_BUFFER_SIZE)
                var lastLogTimeMs = System.currentTimeMillis()

                // We read while isActive. We don't want to check isRecordingActive here since
                // we want keep reading late bytes from scrcpy until EOF is reached or scope is cancelled.
                while (isActive) {
                    val bytesRead = sourceStream.read(buffer)
                    if (bytesRead == -1) {
                        AppLogger.d(TAG,"AudioRelayCoroutine: socket EOF - scrcpy-server disconnected")
                        break
                    }
                    destinationStream.write(buffer, 0, bytesRead)

                    // Verbose throughput logging (≈ once per second) to aid debugging.
                    if (verbose && bytesRead > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastLogTimeMs >= 1000) {
                            lastLogTimeMs = now
                            AppLogger.v(TAG, "AudioRelayCoroutine: relayed $bytesRead bytes. (Wrote to pipe).")
                        }
                    }
                }
            } catch (e: IOException) {
                // IOException here is expected when:
                //  a) stopRecording() closes the socket mid-read (produces a "Socket closed" error)
                //  b) scrcpy-server crashes and the socket is reset by peer
                if (isRecordingActive.get()) {
                    AppLogger.e(TAG,"AudioRelayCoroutine: unexpected I/O error: ${e.message}", e)
                } else {
                    AppLogger.d(TAG,"AudioRelayCoroutine: I/O error during shutdown (expected): ${e.message}")
                }
            } finally {
                // If we exit the relay (e.g. server crash), trigger a full stop so the app-side
                // muxer finalises the file.
                AppLogger.d(TAG,"AudioRelayCoroutine finished")
                stopRecording()
            }
        }
    }

    /**
     * Launches a daemon coroutine that drains scrcpy-server's stdout/stderr.
     *
     * @param process The running scrcpy-server [Process] whose output stream to consume.
     */
    private fun spawnLogConsumerCoroutine(process: Process) {
        shellScope?.launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    // Read one line at a time until EOF or the coroutine is cancelled.
                    var line = reader.readLine()
                    while (isActive && line != null) {
                        AppLogger.i(TAG,"[scrcpy-server] $line")
                        line = reader.readLine()
                    }
                }
            } catch (_: InterruptedIOException) {
                // Normal shutdown path: the scope was cancelled and the stream was closed.
                AppLogger.d(TAG,"LogConsumerCoroutine: interrupted (expected during shutdown)")
            } catch (e: IOException) {
                AppLogger.e(TAG,"LogConsumerCoroutine: I/O error: ${e.message}", e)
            } finally {
                AppLogger.d(TAG,"LogConsumerCoroutine finished")
            }
        }
    }

    /**
     * Launches a daemon coroutine that waits for scrcpy-server to exit.
     *
     * If scrcpy-server exits with a non-zero code while recording is still active, it crashed
     * unexpectedly.  We trigger [stopRecording] so the muxer can still finalise the file with
     * whatever audio was already captured.
     *
     * @param process The running scrcpy-server [Process] to monitor.
     */
    private fun spawnProcessMonitorCoroutine(process: Process) {
        shellScope?.launch(Dispatchers.IO) {
            try {
                // waitFor() blocks until the child process exits.
                val exitCode = process.waitFor()
                if (exitCode != 0 && isRecordingActive.get()) {
                    AppLogger.e(TAG,"ProcessMonitorCoroutine: scrcpy-server crashed (exit code $exitCode)")
                    stopRecording() // Trigger cleanup and file finalisation.
                } else {
                    AppLogger.i(TAG,"ProcessMonitorCoroutine: scrcpy-server exited normally (code $exitCode)")
                }
            } catch (_: InterruptedException) {
                // Normal: the scope was cancelled (service stopped) before the process exited.
                AppLogger.d(TAG,"ProcessMonitorCoroutine: interrupted (expected during shutdown)")
            } finally {
                AppLogger.d(TAG,"ProcessMonitorCoroutine finished")
            }
        }
    }
}