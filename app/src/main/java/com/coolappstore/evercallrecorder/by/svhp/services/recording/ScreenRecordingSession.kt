/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp.services.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.coolappstore.evercallrecorder.by.svhp.IShellService
import com.coolappstore.evercallrecorder.by.svhp.R
import com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences
import com.coolappstore.evercallrecorder.by.svhp.data.recordings.RecordingMetadata
import com.coolappstore.evercallrecorder.by.svhp.integrations.scrcpy.ScrcpyAudioCodec
import com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder.ScreenRecorderAccessibilityService
import com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder.ScreenRecorderDriver
import com.coolappstore.evercallrecorder.by.svhp.system.storage.SafHelper
import com.coolappstore.evercallrecorder.by.svhp.utils.AppLogger
import com.coolappstore.evercallrecorder.by.svhp.utils.RecordingFileNameFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * App-side orchestrator for the "Samsung Screen Recorder" audio source
 * ([com.coolappstore.evercallrecorder.by.svhp.integrations.scrcpy.ScrcpyAudioSource.SAMSUNG_SCREEN_RECORDER]).
 *
 * Replaces the whole scrcpy pipeline for VoIP calls on devices where remote-submix capture is
 * broken (Android 16). Flow (see docs/SHIZUKU_SYSTEM_RECORD.md for the full rationale):
 *
 *  1. [start]: preflight checks → the shell process wakes the screen, clicks the recorder QS
 *     tile, confirms the "Start recording" dialog (see docs/SHIZUKU_SYSTEM_RECORD.md) and
 *     polls `dumpsys media_projection`. Start succeeds when the
 *     projection session is reported active (Samsung's ~3s countdown included).
 *  2. [stopAndImport]: the shell process clicks the tile again to stop, waits for the mp4 to
 *     appear in /sdcard/DCIM/Screen recordings/, and streams it back over a binder pipe FD.
 *     The audio track is then extracted into an .m4a file in the user's configured storage,
 *     named with the normal call-recording template.
 *
 * All methods run on [Dispatchers.IO]; the shell calls block for several seconds (state polling).
 */
class ScreenRecordingSession(
    private val context: Context,
    private val shellService: IShellService,
    private val metadata: RecordingMetadata,
    private val backendKey: String
) {
    companion object {
        private const val TAG = "SCR:ScreenRecordingSession"

        /** Max time to wait for the recorder mp4 to appear after the session stopped. Samsung
         * finalizes (renames the hidden .pending file) within a few seconds; longer calls take longer. */
        private const val FILE_APPEAR_TIMEOUT_MS = 15_000L
        private const val FILE_APPEAR_POLL_MS = 500L

        /** Sample copy buffer for the MediaExtractor → MediaMuxer audio extraction. */
        private const val EXTRACT_BUFFER_SIZE = 1024 * 1024
    }

    /** Epoch seconds captured right before the recorder start, used to reject older files on import. */
    @Volatile
    private var sessionStartEpochSec: Long = 0L

    /** True after [start] succeeded and until [stopAndImport] completes. */
    @Volatile
    var isActive: Boolean = false
        private set

    /**
     * Starts the system screen recorder.
     *
     * @throws PipelineInitializationException when a screen recording is already running
     * (tile toggles — clicking would stop the user's own session), or the shell-side dialog
     * confirmation failed (locked screen / timeout).
     */
    suspend fun start() {
        withContext(Dispatchers.IO) {
            if (runCatching { shellService.isScreenRecordingActive(backendKey) }.getOrDefault(false)) {
                throw PipelineInitializationException(
                    userFriendlyMessage = context.getString(R.string.recording_error_screen_recorder_busy),
                    technicalLogMessage = "A screen recording is already active; refusing to click the toggling tile"
                )
            }

            sessionStartEpochSec = System.currentTimeMillis() / 1000

            // Accessibility confirmation: arm the service so it only auto-confirms the dialog
            // while OUR start flow is in flight (never a dialog the user opened manually).
            val confirmMode = resolveConfirmMode()
            if (confirmMode == ScreenRecorderDriver.CONFIRM_ACCESSIBILITY) {
                ScreenRecorderAccessibilityService.isConfirmationExpected = true
            }

            val started = try {
                shellService.startScreenRecording(backendKey, confirmMode)
            } catch (e: Exception) {
                throw PipelineInitializationException(
                    userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
                    technicalLogMessage = "Remote exception calling startScreenRecording",
                    cause = e
                )
            } finally {
                ScreenRecorderAccessibilityService.isConfirmationExpected = false
            }

            if (!started) {
                throw PipelineInitializationException(
                    userFriendlyMessage = context.getString(R.string.recording_error_screen_recorder_start_failed),
                    technicalLogMessage = "Screen recorder session never became active (dialog not confirmed, locked screen, or tile missing)"
                )
            }
            isActive = true
            AppLogger.i(TAG, "Samsung screen recorder session started for ${metadata.sourceApp} call")
        }
    }

    /**
     * Maps the user's "Recorder dialog confirmation" preference to the shell-side constant.
     * ACCESSIBILITY requires [ScreenRecorderAccessibilityService] to be enabled by the user in
     * system settings. Fail safely when disabled instead of switching to coordinate taps.
     */
    private fun resolveConfirmMode(): Int {
        val mode = AppPreferences(context).getScreenRecorderConfirmMode()
        if (mode == AppPreferences.ScreenRecorderConfirmMode.ACCESSIBILITY) {
            if (ScreenRecorderAccessibilityService.isServiceEnabled(context)) {
                AppLogger.i(TAG, "Dialog confirmation via accessibility service (fast path)")
                return ScreenRecorderDriver.CONFIRM_ACCESSIBILITY
            }
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.settings_screen_recorder_a11y_disabled_warning),
                technicalLogMessage = "Accessibility confirmation selected but the service is not enabled"
            )
        }
        return ScreenRecorderDriver.CONFIRM_UIAUTOMATOR
    }

    /**
     * Stops the recorder, waits for the mp4, imports it, and writes an audio-only .m4a into the
     * configured storage.
     *
     * @return The [Uri] of the imported .m4a recording, or null when stop/import failed (the
     * user's original mp4 always stays in DCIM either way).
     */
    suspend fun stopAndImport(): Uri? = withContext(Dispatchers.IO) {
        isActive = false

        val stopped = runCatching { shellService.stopScreenRecording(backendKey) }.getOrDefault(false)
        if (!stopped) {
            AppLogger.w(TAG, "Screen recorder did not confirm stop; attempting import anyway")
        }

        val pfd = waitForNewRecordingFile() ?: run {
            AppLogger.e(TAG, "No new screen recording file found after stop; nothing to import")
            return@withContext null
        }

        // MediaExtractor needs a seekable file, so buffer the pipe stream to a temp file first.
        val tempFile = File(context.cacheDir, "screen_recording_import_${System.currentTimeMillis()}.mp4")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            AppLogger.d(TAG, "Buffered screen recording to temp file (${tempFile.length()} bytes)")

            val preferences = AppPreferences(context)
            val fileName = RecordingFileNameFormatter.formatFileName(context, metadata, ScrcpyAudioCodec.AAC)
            val safResult = SafHelper.createAudioFile(context, preferences, fileName, "audio/mp4") ?: run {
                AppLogger.e(TAG, "Failed to create output file for imported recording")
                return@withContext null
            }

            val extracted = runCatching {
                extractAudioTrack(tempFile, safResult.descriptor.fileDescriptor)
            }.onFailure { AppLogger.e(TAG, "Audio extraction failed", it) }.getOrDefault(false)
            runCatching { safResult.descriptor.close() }

            if (!extracted) {
                SafHelper.deleteRecording(context, safResult.uri)
                return@withContext null
            }

            // Optionally remove the source video from DCIM now that its audio is safely stored.
            if (preferences.isDeleteScreenRecordingAfterImportEnabled()) {
                val deleted = runCatching { shellService.deleteLatestScreenRecording(backendKey, sessionStartEpochSec) }.getOrDefault(false)
                if (!deleted) {
                    AppLogger.w(TAG, "Source screen recording could not be deleted; it remains in DCIM/Screen recordings")
                }
            }

            AppLogger.i(TAG, "Imported screen recording as $fileName (${safResult.uri})")
            safResult.uri
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Polls the shell for the newly written recording file. The mtime guard inside
     * [IShellService.openLatestScreenRecording] ensures we never pick up an older session's file.
     */
    private suspend fun waitForNewRecordingFile(): ParcelFileDescriptor? {
        val deadline = System.currentTimeMillis() + FILE_APPEAR_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pfd = runCatching { shellService.openLatestScreenRecording(backendKey, sessionStartEpochSec) }.getOrNull()
            if (pfd != null) return pfd
            delay(FILE_APPEAR_POLL_MS)
        }
        return null
    }

    /**
     * Copies the first audio track of [source] into a new MPEG-4 container written to [destFd].
     * Video and metadata tracks are dropped, producing a compact audio-only .m4a.
     */
    private fun extractAudioTrack(source: File, destFd: java.io.FileDescriptor): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) {
                AppLogger.w(TAG, "No audio track found in screen recording")
                return false
            }

            extractor.selectTrack(audioTrackIndex)
            muxer = MediaMuxer(destFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val destTrack = muxer.addTrack(extractor.getTrackFormat(audioTrackIndex))
            muxer.start()

            val buffer = ByteBuffer.allocate(EXTRACT_BUFFER_SIZE)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(destTrack, buffer, info)
                extractor.advance()
            }
            return true
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
