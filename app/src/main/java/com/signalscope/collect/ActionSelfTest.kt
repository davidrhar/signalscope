package com.signalscope.collect

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Proof that the traffic classifier is live rather than decorative.
 *
 * Emits **silence** — PCM zeros, which are inaudible at any volume — with no audio-focus request,
 * so nothing the user is listening to is paused, carrying `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`.
 * The platform reports it through `getActivePlaybackConfigurations()` like any other stream, so
 * the class must move from Idle to Music for as long as it runs, and the disruptive levers must
 * stay allowed (music holds 30–60 s of buffer).
 *
 * Caveat worth knowing: a player in our own process is never anonymised, so this proves the
 * plumbing, not what another app's attributes look like to us.
 */
object ActionSelfTest {

    val running = MutableStateFlow(0)          // seconds remaining, 0 = idle
    val runningLabel = MutableStateFlow("")
    private var job: Job? = null
    // SupervisorJob plus an explicit handler: a failure here is a failed diagnostic, never a
    // reason to take the process down or to poison the scope for the next run.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            ActionQueue.note("self-test aborted: ${t.javaClass.simpleName}", "FAILED")
            running.value = 0
        }
    )

    /**
     * @param usage   `AudioAttributes` usage to declare — MEDIA/MUSIC proves the safe-window path,
     *                UNKNOWN/UNKNOWN proves the fail-safe path that blocks everything.
     */
    fun start(
        seconds: Int = 10,
        usage: Int = AudioAttributes.USAGE_MEDIA,
        content: Int = AudioAttributes.CONTENT_TYPE_MUSIC,
        label: String = "MEDIA/MUSIC"
    ) {
        if (job?.isActive == true) return
        runningLabel.value = label
        job = scope.launch {
            val rate = 44100
            val min = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            // Constructed outside the try/catch below, this was the one uncaught throw in the
            // app: AudioTrack's constructor raises IllegalArgumentException or
            // UnsupportedOperationException where the format or the session cannot be honoured,
            // and an exception escaping a plain `scope.launch` reaches the thread's default
            // handler and terminates the process. The self-test is a diagnostic; it may fail,
            // and failing must cost a line in the queue rather than the app.
            val track = try {
                AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(content)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    min, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            } catch (t: Throwable) {
                ActionQueue.note("self-test could not open an audio track: " +
                        t.javaClass.simpleName, "FAILED")
                running.value = 0
                return@launch
            }
            val silence = ShortArray(min / 2)
            try {
                // The samples are zeros, so this is digital silence at any volume. Muting the
                // track would have been redundant — and on this device it also hid the player
                // from getActivePlaybackConfigurations(), which is itself worth knowing.
                track.play()
                ActionQueue.note("self-test: silent $label stream for ${seconds}s", "SELF-TEST")
                val end = System.currentTimeMillis() + seconds * 1000L
                var lastSample = 0L
                while (isActive && System.currentTimeMillis() < end) {
                    track.write(silence, 0, silence.size)
                    val now = System.currentTimeMillis()
                    running.value = ((end - now) / 1000).toInt() + 1
                    if (now - lastSample > 250) { lastSample = now; ActionTraffic.sample() }
                }
            } catch (t: Throwable) {
                ActionQueue.note("self-test failed: ${t.javaClass.simpleName}", "FAILED")
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
                running.value = 0
                delay(150)
                ActionTraffic.sample()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
