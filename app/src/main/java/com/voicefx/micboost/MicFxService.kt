package com.voicefx.micboost

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voicefx.micboost.dsp.MicId
import com.voicefx.micboost.dsp.Presets
import com.voicefx.micboost.dsp.VoiceChain
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the whole live loop:
 *   wired-headset check -> AudioRecord (mic) -> VoiceChain (DSP) -> AudioTrack (headset out)
 *
 * Runs continuously once started, regardless of whether MainActivity is on
 * screen, and only stops when the app explicitly calls stopSelf() (from the
 * UI's master power toggle) — matching "stays on until I turn it off".
 *
 * Deliberately does NOT attempt to route this processed audio into any other
 * app's call/broadcast session — it plays back only to the device the user
 * is wearing (wired headset / Bluetooth), which is the one thing a
 * third-party Android app can legitimately own without touching another
 * app's microphone capture.
 */
class MicFxService : Service() {

    companion object {
        const val ACTION_START = "com.voicefx.micboost.START"
        const val ACTION_STOP = "com.voicefx.micboost.STOP"
        const val ACTION_SWITCH = "com.voicefx.micboost.SWITCH"
        /** Comma-separated MicId names, e.g. "BEAST,VINTAGE" for a stacked combo. */
        const val EXTRA_PRESETS = "presets"
        const val CHANNEL_ID = "mic_fx_channel"
        const val NOTIF_ID = 1001

        const val SAMPLE_RATE = 48000
    }

    private var recordThread: Thread? = null
    private val running = AtomicBoolean(false)
    private lateinit var chain: VoiceChain
    private lateinit var audioManager: AudioManager

    override fun onCreate() {
        super.onCreate()
        chain = VoiceChain(SAMPLE_RATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProcessing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val ids = parsePresetIds(intent.getStringExtra(EXTRA_PRESETS))
                chain.setActivePresets(ids)

                if (!isWiredOrBluetoothHeadsetConnected()) {
                    // Hard safety rule: refuse to start without a headset, so the
                    // boosted output can never feed back into the mic through a
                    // phone speaker.
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIF_ID, buildNotification(labelFor(ids)))
                startProcessing()
            }
            ACTION_SWITCH -> {
                // Live-update which mics are stacked while already running.
                val ids = parsePresetIds(intent.getStringExtra(EXTRA_PRESETS))
                chain.setActivePresets(ids)
                if (running.get()) {
                    updateNotification(labelFor(ids))
                }
            }
        }
        return START_STICKY
    }

    private fun parsePresetIds(raw: String?): Set<MicId> {
        val names = raw?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val ids = names.mapNotNull { runCatching { MicId.valueOf(it) }.getOrNull() }.toSet()
        return ids.ifEmpty { setOf(MicId.GOLDEN) }
    }

    private fun labelFor(ids: Set<MicId>): String =
        ids.joinToString(" + ") { Presets.byId(it).label.substringBefore(" —") }

    private fun updateNotification(label: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(label))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProcessing()
        super.onDestroy()
    }

    private fun isWiredOrBluetoothHeadsetConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    @Suppress("MissingPermission") // RECORD_AUDIO is checked by MainActivity before starting the service
    private fun startProcessing() {
        if (running.get()) return
        running.set(true)

        val minBufRecord = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)
        val minBufTrack = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, minBufRecord
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufTrack)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Route output to the wired/Bluetooth headset explicitly, never the speaker.
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }?.let { track.preferredDevice = it }

        record.startRecording()
        track.play()

        recordThread = Thread {
            val buffer = FloatArray(minBufRecord)
            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    chain.processBuffer(buffer, read)
                    track.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)
                }
            }
            record.stop(); record.release()
            track.stop(); track.release()
        }.apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun stopProcessing() {
        running.set(false)
        recordThread?.join(500)
        recordThread = null
        chain.reset()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Mic FX active", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(presetLabel: String): android.app.Notification {
        val stopIntent = Intent(this, MicFxService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mic FX running — $presetLabel")
            .setContentText("Tap to open · boosting into your headset")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Turn off", stopPending)
            .setOngoing(true)
            .build()
    }
}
