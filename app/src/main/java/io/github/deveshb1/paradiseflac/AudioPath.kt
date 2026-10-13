package io.github.deveshb1.paradiseflac

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/** Where the audio ends up, and whether that hop keeps it lossless. */
object AudioPath {
    enum class Kind { BLUETOOTH, USB, WIRED, SPEAKER, OTHER }

    private const val PREFS = "audio"
    private const val KEY_BIT_PERFECT = "bit_perfect"

    val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun kindOf(type: Int): Kind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> Kind.USB
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> Kind.WIRED
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Kind.SPEAKER
        else -> Kind.OTHER
    }

    /** The device media is routed to right now. */
    fun currentOutput(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (Build.VERSION.SDK_INT >= 33) {
            val routed = am.getAudioDevicesForAttributes(mediaAttributes).firstOrNull()
            if (routed != null) {
                outputs.firstOrNull { it.type == routed.type && it.address == routed.address }
                    ?.let { return it }
            }
        }
        // Older devices: Android prefers USB > wired > Bluetooth > speaker for media.
        return listOf(Kind.USB, Kind.WIRED, Kind.BLUETOOTH, Kind.SPEAKER).firstNotNullOfOrNull { kind ->
            outputs.firstOrNull { kindOf(it.type) == kind }
        }
    }

    private fun usbOutput(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { kindOf(it.type) == Kind.USB }

    // --- Bit-perfect USB (Android 14+) -------------------------------------------------

    val bitPerfectSupported: Boolean get() = Build.VERSION.SDK_INT >= 34

    fun bitPerfectWanted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BIT_PERFECT, false)

    fun setBitPerfectWanted(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BIT_PERFECT, on).apply()
        applyBitPerfect(context)
    }

    /**
     * Ask Android to hand our 44.1 kHz / 16-bit PCM to the USB DAC untouched (no mixer,
     * no resampling, no volume scaling). Takes effect the next time an AudioTrack opens.
     */
    fun applyBitPerfect(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val am = context.getSystemService(AudioManager::class.java)
        val usb = usbOutput(am) ?: return
        if (!bitPerfectWanted(context)) {
            am.clearPreferredMixerAttributes(mediaAttributes, usb)
            return
        }
        bitPerfectMixer(am, usb)?.let { am.setPreferredMixerAttributes(mediaAttributes, usb, it) }
    }

    @RequiresApi(34)
    private fun bitPerfectMixer(am: AudioManager, usb: AudioDeviceInfo): AudioMixerAttributes? =
        am.getSupportedMixerAttributes(usb).firstOrNull {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                it.format.sampleRate == 44_100 &&
                it.format.encoding == AudioFormat.ENCODING_PCM_16BIT &&
                it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO
        }

    fun bitPerfectActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val am = context.getSystemService(AudioManager::class.java)
        val usb = usbOutput(am) ?: return false
        return am.getPreferredMixerAttributes(mediaAttributes, usb)?.mixerBehavior ==
            AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
    }

    fun usbConnected(context: Context): Boolean =
        usbOutput(context.getSystemService(AudioManager::class.java)) != null
}
