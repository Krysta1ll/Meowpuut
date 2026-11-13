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
    private var tap: VibrationEffect? = null
    private var tick: VibrationEffect? = null
    private var enabled = true

    fun update(enabled: Boolean, strength: HapticStrength) {
        this.enabled = enabled
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
