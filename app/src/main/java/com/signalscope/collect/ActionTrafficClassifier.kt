package com.signalscope.collect

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executor

/**
 * Tier-0 traffic classifier — see docs/traffic-classes.md.
 *
 * What is running decides which remediation is safe, and the buffer the running traffic holds is
 * our permission to act. Everything here is public SDK: audio mode, the active playback
 * configurations, and the call state. No privilege, no user grant, no Shizuku.
 *
 * The policy is deliberately asymmetric. A wrong "it is safe" costs a dropped call; a wrong
 * "it is not safe" costs a few seconds of degraded Spotify nobody notices. So every ambiguity
 * resolves downwards, toward assuming real-time.
 */
enum class TrafficClass(
    val label: String,
    val slackLabel: String,
    /** Deepest gap the class survives, seconds. [Int.MAX_VALUE] = unbounded. */
    val slackSeconds: Int,
    val consequence: String
) {
    REALTIME_CALL(
        "Real-time call", "~1 s", 1,
        "Remediation is blocked right now — you are on a call."
    ),
    CONFERENCING(
        "Conferencing / VoIP", "~1–2 s", 2,
        "Remediation is blocked right now — a real-time session is open."
    ),
    VIDEO(
        "Video streaming", "10–30 s", 10,
        "Safe window: video is buffered, a 2-second re-anchor would be invisible."
    ),
    MUSIC(
        "Music / buffered audio", "30–60 s", 30,
        "Safe window: music is playing, a 2-second re-anchor would be invisible."
    ),
    IDLE(
        "Idle", "unbounded", Int.MAX_VALUE,
        "Ideal window: nothing is playing. Maintenance belongs here."
    ),
    UNKNOWN(
        "Unclassified", "~1 s assumed", 1,
        "Cannot classify what is running, so real-time is assumed and nothing will run."
    );

    val blocksDisruption: Boolean get() = slackSeconds < MIN_DISRUPTION_COST

    companion object {
        /** No remediation we have costs less than this. */
        const val MIN_DISRUPTION_COST = 3
    }
}

/** One visible playback stream, as [AudioManager.getActivePlaybackConfigurations] reports it. */
data class PlaybackStream(
    val usage: String,
    val content: String,
    val usageRaw: Int,
    val contentRaw: Int
)

data class TrafficState(
    val klass: TrafficClass = TrafficClass.UNKNOWN,
    val basis: String = "not sampled yet",
    val audioMode: Int = -1,
    val callState: Int = TelephonyManager.CALL_STATE_IDLE,
    val callStateReadable: Boolean = false,
    val musicActive: Boolean = false,
    val streams: List<PlaybackStream> = emptyList(),
    /** true when the class was reached by failing safe rather than by positive evidence */
    val failedSafe: Boolean = true,
    val samples: Int = 0,
    val sinceElapsed: Long = 0L
) {
    val slackSeconds: Int get() = klass.slackSeconds
    fun heldFor(): Long = if (sinceElapsed == 0L) 0 else (SystemClock.elapsedRealtime() - sinceElapsed) / 1000
}

/**
 * Single shared classifier. Callbacks give the instant transitions that matter (a call starting
 * must be seen immediately); the 1 s poll is the backstop for anything the callbacks miss, which
 * on OEM builds is not a theoretical concern.
 */
object ActionTraffic {

    val state = MutableStateFlow(TrafficState())

    private var am: AudioManager? = null
    private var tm: TelephonyManager? = null
    private var playbackCb: AudioManager.AudioPlaybackCallback? = null
    private var modeCb: AudioManager.OnModeChangedListener? = null
    private var telCb: TelephonyCallback? = null
    private var callState = TelephonyManager.CALL_STATE_IDLE
    private var callStateReadable = false
    private var refs = 0

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executor { main.post(it) }

    @Synchronized
    fun start(ctx: Context) {
        refs++
        if (refs > 1) { sample(); return }
        val app = ctx.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am = audio
        tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        if (audio != null) {
            val pcb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = sample()
            }
            runCatching { audio.registerAudioPlaybackCallback(pcb, main) }
                .onSuccess { playbackCb = pcb }

            val mcb = AudioManager.OnModeChangedListener { sample() }
            runCatching { audio.addOnModeChangedListener(exec, mcb) }.onSuccess { modeCb = mcb }
        }

        // CallStateListener needs READ_PHONE_STATE. If it is not granted the whole cellular-call
        // signal is gone and we must say so rather than silently classifying calls as idle.
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(newState: Int) {
                callState = newState
                callStateReadable = true
                sample()
            }
        }
        runCatching { tm?.registerTelephonyCallback(exec, cb) }
            .onSuccess { telCb = cb; callStateReadable = true }
            .onFailure { callStateReadable = false }

        sample()
    }

    @Synchronized
    fun stop() {
        refs--
        if (refs > 0) return
        refs = 0
        playbackCb?.let { cb -> runCatching { am?.unregisterAudioPlaybackCallback(cb) } }
        modeCb?.let { cb -> runCatching { am?.removeOnModeChangedListener(cb) } }
        telCb?.let { cb -> runCatching { tm?.unregisterTelephonyCallback(cb) } }
        playbackCb = null; modeCb = null; telCb = null
    }

    /** Re-read every source and re-classify. Cheap: three getters and a short list. */
    fun sample() {
        val audio = am
        val mode = runCatching { audio?.mode ?: -1 }.getOrDefault(-1)
        val musicActive = runCatching { audio?.isMusicActive == true }.getOrDefault(false)
        val streams: List<PlaybackStream> = runCatching {
            audio?.activePlaybackConfigurations.orEmpty().map { c ->
                val a: AudioAttributes = c.audioAttributes
                PlaybackStream(usageName(a.usage), contentName(a.contentType), a.usage, a.contentType)
            }
        }.getOrDefault(emptyList())

        val (klass, basis, positive) = classify(mode, callState, callStateReadable, streams, musicActive)
        val prev = state.value
        state.value = prev.copy(
            klass = klass,
            basis = basis,
            audioMode = mode,
            callState = callState,
            callStateReadable = callStateReadable,
            musicActive = musicActive,
            streams = streams,
            failedSafe = !positive,
            samples = prev.samples + 1,
            sinceElapsed = if (prev.klass == klass && prev.sinceElapsed != 0L) prev.sinceElapsed
            else SystemClock.elapsedRealtime()
        )
    }

    /**
     * Ordered so that the most dangerous state wins. Returns (class, basis, positive evidence).
     * "positive" is false when the class is the product of a fail-safe rather than a reading.
     */
    private fun classify(
        mode: Int,
        call: Int,
        callReadable: Boolean,
        streams: List<PlaybackStream>,
        musicActive: Boolean
    ): Triple<TrafficClass, String, Boolean> {

        if (!callReadable) {
            return Triple(
                TrafficClass.UNKNOWN,
                "call state unreadable — READ_PHONE_STATE not granted, so a call cannot be ruled out",
                false
            )
        }

        if (call == TelephonyManager.CALL_STATE_OFFHOOK || mode == AudioManager.MODE_IN_CALL) {
            return Triple(
                TrafficClass.REALTIME_CALL,
                if (mode == AudioManager.MODE_IN_CALL) "audio mode MODE_IN_CALL" else "call state OFFHOOK",
                true
            )
        }
        if (call == TelephonyManager.CALL_STATE_RINGING || mode == AudioManager.MODE_RINGTONE) {
            return Triple(
                TrafficClass.REALTIME_CALL,
                "a call is ringing — treated as in-call, because it is about to be one",
                true
            )
        }
        if (mode == AudioManager.MODE_IN_COMMUNICATION ||
            streams.any { it.usageRaw == AudioAttributes.USAGE_VOICE_COMMUNICATION }
        ) {
            return Triple(
                TrafficClass.CONFERENCING,
                if (mode == AudioManager.MODE_IN_COMMUNICATION) "audio mode MODE_IN_COMMUNICATION"
                else "a stream declares usage VOICE_COMMUNICATION",
                true
            )
        }

        if (streams.isNotEmpty()) {
            // The narrowest budget across everything playing wins.
            var worst: TrafficClass? = null
            var why = ""
            var positive = true
            for (s in streams) {
                val (k, p) = mediaClassOf(s)
                if (!p) positive = false
                if (worst == null || k.slackSeconds < worst!!.slackSeconds) {
                    worst = k; why = "${s.usage} / ${s.content}"
                }
            }
            return Triple(worst!!, "active playback · $why", positive)
        }

        if (musicActive) {
            // isMusicActive() says something is playing but no configuration is visible to us.
            return Triple(
                TrafficClass.VIDEO,
                "isMusicActive() is true but no playback configuration is visible — assuming the tighter budget",
                false
            )
        }

        return Triple(TrafficClass.IDLE, "no call, no active playback", true)
    }

    /** Media classes. CONTENT_TYPE_UNKNOWN is common on this platform — see the report. */
    private fun mediaClassOf(s: PlaybackStream): Pair<TrafficClass, Boolean> = when {
        s.contentRaw == AudioAttributes.CONTENT_TYPE_MOVIE -> TrafficClass.VIDEO to true
        s.contentRaw == AudioAttributes.CONTENT_TYPE_MUSIC -> TrafficClass.MUSIC to true
        s.contentRaw == AudioAttributes.CONTENT_TYPE_SPEECH -> TrafficClass.MUSIC to true
        s.usageRaw == AudioAttributes.USAGE_MEDIA -> TrafficClass.VIDEO to false
        s.usageRaw == AudioAttributes.USAGE_UNKNOWN -> TrafficClass.UNKNOWN to false
        // Notifications, alarms, sonification: audible but holding no network buffer at all.
        else -> TrafficClass.IDLE to true
    }

    fun modeName(m: Int) = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
        AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMM_REDIRECT"
        else -> "mode $m"
    }

    fun callStateName(s: Int) = when (s) {
        TelephonyManager.CALL_STATE_IDLE -> "IDLE"
        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
        else -> "—"
    }

    private fun usageName(u: Int) = when (u) {
        AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMM"
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "VOICE_SIG"
        AudioAttributes.USAGE_ALARM -> "ALARM"
        AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "A11Y"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAV"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "SONIFICATION"
        AudioAttributes.USAGE_GAME -> "GAME"
        AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
        else -> "usage $u"
    }

    private fun contentName(c: Int) = when (c) {
        AudioAttributes.CONTENT_TYPE_UNKNOWN -> "UNKNOWN"
        AudioAttributes.CONTENT_TYPE_SPEECH -> "SPEECH"
        AudioAttributes.CONTENT_TYPE_MUSIC -> "MUSIC"
        AudioAttributes.CONTENT_TYPE_MOVIE -> "MOVIE"
        AudioAttributes.CONTENT_TYPE_SONIFICATION -> "SONIFICATION"
        else -> "content $c"
    }
}
