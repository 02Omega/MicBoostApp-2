package com.voicefx.micboost.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Standard RBJ biquad filter (used for the high-pass / low-pass band-limiting
 * on Vintage, and the presence / low-shelf boosts across the other presets).
 * One instance = one stage; chain several for a full EQ curve.
 */
class Biquad(sampleRate: Int) {

    enum class Type { LOWPASS, HIGHPASS, PEAK, LOWSHELF, HIGHSHELF }

    private val fs = sampleRate.toDouble()
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    fun configure(type: Type, freq: Double, q: Double, gainDb: Double = 0.0) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * (freq / fs).coerceIn(0.0001, 0.4999)
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val alpha = sinw0 / (2.0 * q)

        var b0n = 0.0; var b1n = 0.0; var b2n = 0.0
        var a0n = 1.0; var a1n = 0.0; var a2n = 0.0

        when (type) {
            Type.LOWPASS -> {
                b0n = (1 - cosw0) / 2; b1n = 1 - cosw0; b2n = (1 - cosw0) / 2
                a0n = 1 + alpha; a1n = -2 * cosw0; a2n = 1 - alpha
            }
            Type.HIGHPASS -> {
                b0n = (1 + cosw0) / 2; b1n = -(1 + cosw0); b2n = (1 + cosw0) / 2
                a0n = 1 + alpha; a1n = -2 * cosw0; a2n = 1 - alpha
            }
            Type.PEAK -> {
                b0n = 1 + alpha * a; b1n = -2 * cosw0; b2n = 1 - alpha * a
                a0n = 1 + alpha / a; a1n = -2 * cosw0; a2n = 1 - alpha / a
            }
            Type.LOWSHELF -> {
                val sq = sqrt(a) * 2.0 * alpha
                b0n = a * ((a + 1) - (a - 1) * cosw0 + sq)
                b1n = 2 * a * ((a - 1) - (a + 1) * cosw0)
                b2n = a * ((a + 1) - (a - 1) * cosw0 - sq)
                a0n = (a + 1) + (a - 1) * cosw0 + sq
                a1n = -2 * ((a - 1) + (a + 1) * cosw0)
                a2n = (a + 1) + (a - 1) * cosw0 - sq
            }
            Type.HIGHSHELF -> {
                val sq = sqrt(a) * 2.0 * alpha
                b0n = a * ((a + 1) + (a - 1) * cosw0 + sq)
                b1n = -2 * a * ((a - 1) + (a + 1) * cosw0)
                b2n = a * ((a + 1) + (a - 1) * cosw0 - sq)
                a0n = (a + 1) - (a - 1) * cosw0 + sq
                a1n = 2 * ((a - 1) - (a + 1) * cosw0)
                a2n = (a + 1) - (a - 1) * cosw0 - sq
            }
        }
        b0 = b0n / a0n; b1 = b1n / a0n; b2 = b2n / a0n
        a1 = a1n / a0n; a2 = a2n / a0n
    }

    fun process(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = xd
        y2 = y1; y1 = y
        return y.toFloat()
    }

    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
}
