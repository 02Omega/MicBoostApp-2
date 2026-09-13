package com.voicefx.micboost.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** dB <-> linear helpers used throughout the chain. */
object DbUtil {
    fun toDb(linear: Float): Float = if (linear <= 1e-9f) -120f else (20.0 * ln(linear.toDouble()) / ln(10.0)).toFloat()
    fun toLinear(db: Float): Float = 10f.pow(db / 20f)
}

/**
 * Feed-forward compressor with an envelope follower. This is the stage that
 * evens out a voice so it sits at a consistent, confident loudness instead of
 * spiking and dipping with every word.
 */
class Compressor(private val sampleRate: Int) {
    var thresholdDb = -20f
    var ratio = 4f
    var attackMs = 5f
    var releaseMs = 80f
    var makeupDb = 3f

    private var envelope = 0f

    fun process(x: Float): Float {
        val inputDb = DbUtil.toDb(abs(x))
        val attackCoef = exp(-1000f / (attackMs * sampleRate))
        val releaseCoef = exp(-1000f / (releaseMs * sampleRate))

        envelope = if (inputDb > envelope) {
            attackCoef * envelope + (1 - attackCoef) * inputDb
        } else {
            releaseCoef * envelope + (1 - releaseCoef) * inputDb
        }

        val overDb = envelope - thresholdDb
        val gainReductionDb = if (overDb > 0f) overDb - overDb / ratio else 0f
        val totalGainDb = makeupDb - gainReductionDb
        return x * DbUtil.toLinear(totalGainDb)
    }

    fun reset() { envelope = -120f }
}

/**
 * True brickwall peak limiter — the safety net that guarantees output never
 * exceeds the digital ceiling (0 dBFS), which is what keeps every preset
 * "loud but clean" instead of clipped.
 */
class Limiter(private val sampleRate: Int) {
    /** Linear ceiling, e.g. 0.98 ~= -0.18 dBFS of headroom. */
    var ceiling = 0.97f
    var releaseMs = 60f

    private var gain = 1f

    fun process(x: Float): Float {
        val absX = abs(x)
        val neededGain = if (absX > ceiling) ceiling / absX else 1f
        val releaseCoef = exp(-1000f / (releaseMs * sampleRate))
        gain = if (neededGain < gain) {
            neededGain // instant clamp down, no audible overshoot
        } else {
            releaseCoef * gain + (1 - releaseCoef) * neededGain
        }
        return (x * gain).coerceIn(-ceiling, ceiling)
    }
}

/**
 * Soft-clip waveshaper used for the Vintage "fizz" character and the Alpha
 * "roar" growl. `drive` pushes the signal into the curve harder; `mix` blends
 * the saturated signal back with the clean one so it's tunable, not all-or-nothing.
 */
class Saturator {
    var drive = 2f       // higher = more harmonic grit
    var mix = 0.3f        // 0 = clean, 1 = fully saturated

    fun process(x: Float): Float {
        val driven = x * drive
        val shaped = driven / (1f + abs(driven)) // soft tanh-like curve, no hard digital clipping
        return x * (1 - mix) + shaped * mix
    }
}

/** Simple circular-buffer delay for the Echo mic. */
class EchoEffect(sampleRate: Int, maxDelayMs: Int = 800) {
    private val buffer = FloatArray((sampleRate * maxDelayMs / 1000f).toInt().coerceAtLeast(1))
    private val maxDelayMsF = maxDelayMs.toFloat()
    private var writeIdx = 0
    var delayMs = 220f
        set(v) { field = v.coerceIn(1f, maxDelayMsF - 1f) }
    var feedback = 0.28f  // how many repeats
    var mix = 0.22f        // wet/dry blend

    fun process(x: Float, sampleRate: Int): Float {
        val delaySamples = ((delayMs / 1000f) * sampleRate).toInt().coerceIn(1, buffer.size - 1)
        val readIdx = ((writeIdx - delaySamples) + buffer.size) % buffer.size
        val delayed = buffer[readIdx]
        val toWrite = x + delayed * feedback
        buffer[writeIdx] = toWrite.coerceIn(-1f, 1f)
        writeIdx = (writeIdx + 1) % buffer.size
        return x * (1 - mix) + delayed * mix
    }
}
