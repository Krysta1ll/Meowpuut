package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.krysta1ll.meowpuut.android.R

enum class HapticStrength(val label: Int, val amplitude: Int) {
    SOFT(R.string.haptic_soft, 54),
    STANDARD(R.string.effects_standard, 0),
    STRONG(R.string.haptic_strong, 180),
}

class KeyHaptics(private val context: Context) {
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }.takeIf { it.hasVibrator() }
    }
    private val audioAttributes by lazy {
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()
    }
    private val vibrationAttributes by lazy {
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    }
    private var purrEffect: VibrationEffect? = null
    private var purring = false
    private var tap: VibrationEffect? = null
    private var tick: VibrationEffect? = null
    private var enabled = false

    private companion object {
        // 17 pulses of 10 ms, 120 ms apart: just under 2 s. Amplitude 51 of 255 is 0.2.
        val PURR_TIMINGS = LongArray(34) { if (it % 2 == 0) 110L else 10L }
        val PURR_AMPLITUDES = IntArray(34) { if (it % 2 == 0) 0 else 51 }
    }

    fun update(enabled: Boolean, strength: HapticStrength) {
        this.enabled = enabled
        if (!enabled) stopPurr()
        val motor = if (enabled && strength != HapticStrength.STANDARD) vibrator else null
        if (motor == null) {
            tap = null
            tick = null
        } else if (motor.hasAmplitudeControl()) {
            tap = VibrationEffect.createOneShot(12L, strength.amplitude)
            tick = VibrationEffect.createOneShot(8L, strength.amplitude)
        } else {
            val strong = strength == HapticStrength.STRONG
            tap = VibrationEffect.createOneShot(if (strong) 17L else 6L, VibrationEffect.DEFAULT_AMPLITUDE)
            tick = VibrationEffect.createOneShot(if (strong) 11L else 5L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    /**
     * A held cat purrs under the finger: a soft pulse every 120 ms (about a fifth of full
     * strength), 2 s at most, only with key haptics on and a motor that can vary its strength.
     * [stopPurr] ends it when the finger leaves.
     */
    fun purr(view: View) {
        if (!enabled || !view.isHapticFeedbackEnabled) return
        val motor = vibrator?.takeIf { it.hasAmplitudeControl() } ?: return
        val effect = purrEffect ?: VibrationEffect.createWaveform(PURR_TIMINGS, PURR_AMPLITUDES, -1).also { purrEffect = it }
        purring = true
        if (Build.VERSION.SDK_INT >= 33) {
            motor.vibrate(effect, vibrationAttributes)
        } else {
            @Suppress("DEPRECATION")
            if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                motor.vibrate(effect, audioAttributes)
            }
        }
    }

    fun stopPurr() {
        if (!purring) return
        purring = false
        vibrator?.cancel()
    }

    fun play(view: View, feedback: Int) {
        if (!enabled || !view.isHapticFeedbackEnabled) return
        val effect = if (feedback == HapticFeedbackConstants.CLOCK_TICK) tick else tap
        val motor = if (effect != null) vibrator else null
        if (effect == null || motor == null) {
            view.performHapticFeedback(feedback)
        } else if (Build.VERSION.SDK_INT >= 33) {
            motor.vibrate(effect, vibrationAttributes)
        } else {
            @Suppress("DEPRECATION")
            if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                motor.vibrate(effect, audioAttributes)
            }
        }
    }
}
