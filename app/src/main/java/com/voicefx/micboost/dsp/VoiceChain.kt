package com.voicefx.micboost.dsp

import kotlin.math.pow

/**
 * One mic character's own EQ + saturation stages, run as a standalone unit
 * so it can be cascaded with other active mics for the stacking feature.
 */
private class MicStage(sampleRate: Int) {
    val highPass = Biquad(sampleRate)
    val lowPass = Biquad(sampleRate)
    val lowShelf = Biquad(sampleRate)
    val presence = Biquad(sampleRate)
    val airShelf = Biquad(sampleRate) // the "perk" stage — Silver's clarity, Golden's polish, etc.
    val saturator = Saturator()
    var saturationMix = 0f
        private set
    var airGainDb = 0.0
        private set

    fun configure(p: MicPreset) {
        highPass.configure(Biquad.Type.HIGHPASS, p.highPassHz, 0.707)
        lowPass.configure(Biquad.Type.LOWPASS, p.lowPassHz, 0.707)
        lowShelf.configure(Biquad.Type.LOWSHELF, p.lowShelfFreq, 0.9, p.lowShelfGainDb)
        presence.configure(Biquad.Type.PEAK, p.presenceFreq, 1.1, p.presenceGainDb)
        airShelf.configure(Biquad.Type.HIGHSHELF, p.airFreq, 0.8, p.airGainDb)
        airGainDb = p.airGainDb
        saturator.drive = p.saturationDrive
        saturator.mix = p.saturationMix
        saturationMix = p.saturationMix
    }

    fun process(x: Float): Float {
        var s = highPass.process(x)
        s = lowPass.process(s)
        s = lowShelf.process(s)
        s = presence.process(s)
        if (airGainDb != 0.0) s = airShelf.process(s)
        if (saturationMix > 0f) s = saturator.process(s)
        return s
    }

    fun reset() { highPass.reset(); lowPass.reset(); lowShelf.reset(); presence.reset(); airShelf.reset() }
}

/**
 * Full per-sample signal path, now supporting one or several mics stacked
 * together at once (the "combo" feature):
 *
 *   pre-gain -> [cascade of each ACTIVE mic's own EQ+saturation, in a fixed
 *   order] -> ONE shared compressor -> ONE shared limiter (ceiling) ->
 *   optional echo
 *
 * Cascading each active mic's own filters is what makes combos genuinely
 * sound different from one another (Silver+Vintage keeps both the bright
 * presence peak and the warm low end; Beast+Alpha stacks two saturation
 * stages for extra grit) rather than just being "the same sound, louder".
 *
 * The shared compressor/limiter/pre-gain always come from whichever ACTIVE
 * preset is the loudest one selected — so adding Beast or Alpha to any combo
 * is what gives the "or a bit higher" bump the user asked for, while the
 * final Limiter still guarantees the combined result can never exceed the
 * digital ceiling no matter how many mics are stacked.
 *
 * Honest limitation, worth knowing: cascading many EQ stages at once can
 * start to sound a little hollow/resonant past 3-4 simultaneous mics
 * (that's just how stacked filters behave) — still safe, just a tonal
 * trade-off, not a bug.
 */
class VoiceChain(private val sampleRate: Int) {

    private val stages: Map<MicId, MicStage> = MicId.values().associateWith { MicStage(sampleRate) }
    private val compressor = Compressor(sampleRate)
    private val limiter = Limiter(sampleRate)
    private val echo = EchoEffect(sampleRate)

    @Volatile
    private var activeIds: Set<MicId> = setOf(MicId.GOLDEN)
    @Volatile
    private var preGainLinear = 10f.pow(Presets.GOLDEN.preGainDb / 20f)
    @Volatile
    private var echoEnabled = false

    init { setActivePresets(setOf(MicId.GOLDEN)) }

    fun currentActive(): Set<MicId> = activeIds

    /** Reconfigures the whole chain for the given set of simultaneously-active mics. */
    fun setActivePresets(ids: Set<MicId>) {
        val usable = if (ids.isEmpty()) setOf(MicId.GOLDEN) else ids
        activeIds = usable

        val activePresets = usable.map { Presets.byId(it) }
        usable.forEach { stages.getValue(it).configure(Presets.byId(it)) }

        // Shared dynamics section: driven by whichever active preset asks for
        // the most pre-gain (i.e. the loudest one selected).
        val loudest = activePresets.maxByOrNull { it.preGainDb } ?: Presets.GOLDEN
        preGainLinear = 10f.pow(loudest.preGainDb / 20f)
        compressor.thresholdDb = loudest.compThresholdDb
        compressor.ratio = loudest.compRatio
        compressor.attackMs = loudest.compAttackMs
        compressor.releaseMs = loudest.compReleaseMs
        compressor.makeupDb = loudest.compMakeupDb
        limiter.ceiling = activePresets.minOf { it.limiterCeilingLinear } // most conservative ceiling wins, stays safe

        echoEnabled = activePresets.any { it.echoEnabled }
        activePresets.firstOrNull { it.echoEnabled }?.let {
            echo.delayMs = it.echoDelayMs
            echo.feedback = it.echoFeedback
            echo.mix = it.echoMix
        }
    }

    /** Processes one buffer in place. */
    fun processBuffer(buffer: FloatArray, len: Int) {
        val ids = activeIds
        for (i in 0 until len) {
            var s = buffer[i] * preGainLinear
            for (id in ids) {
                s = stages.getValue(id).process(s)
            }
            s = compressor.process(s)
            s = limiter.process(s)
            if (echoEnabled) s = echo.process(s, sampleRate)
            buffer[i] = s.coerceIn(-1f, 1f)
        }
    }

    fun reset() {
        stages.values.forEach { it.reset() }
        compressor.reset()
    }
}
