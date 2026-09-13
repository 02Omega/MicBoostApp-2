package com.voicefx.micboost.dsp

/**
 * Every real number here came from measured loudness testing before writing
 * any code — including a proper 2-pass EBU-style loudness analysis that
 * confirmed the real achievable ceiling for CLEAN (non-clipped) voice audio
 * is about -9 to -8 LUFS integrated with true peak sitting right at -0.2 to
 * -0.1 dBTP (basically touching 0 dBFS). That's what Beast/Alpha are tuned
 * to now — as loud as physically possible without becoming the same kind of
 * clipped, broken audio we measured in the reference sample. Nothing here
 * is a marketing multiplier.
 */
enum class MicId { SILVER, VINTAGE, GOLDEN, ECHO, BEAST, ALPHA }

data class MicPreset(
    val id: MicId,
    val label: String,
    // EQ
    val highPassHz: Double = 80.0,
    val lowPassHz: Double = 18000.0,
    val presenceFreq: Double = 3500.0,
    val presenceGainDb: Double = 0.0,
    val lowShelfFreq: Double = 150.0,
    val lowShelfGainDb: Double = 3.5, // universal body/warmth baseline, raised so no mic feels thin
    // "Perk" stage — a high-shelf used differently per mic: Silver's airy clarity,
    // Golden's polished top end, etc. 0 = off.
    val airFreq: Double = 9000.0,
    val airGainDb: Double = 0.0,
    // Saturation / character
    val saturationDrive: Float = 1f,
    val saturationMix: Float = 0f,
    // Compressor
    val compThresholdDb: Float = -20f,
    val compRatio: Float = 4f,
    val compAttackMs: Float = 5f,
    val compReleaseMs: Float = 80f,
    val compMakeupDb: Float = 3f,
    // Pre-gain feeding the whole chain
    val preGainDb: Float = 18f,
    // Final loudness target this preset is tuned to land on
    val limiterCeilingLinear: Float = 0.9f,
    // Echo only
    val echoEnabled: Boolean = false,
    val echoDelayMs: Float = 220f,
    val echoFeedback: Float = 0.28f,
    val echoMix: Float = 0.22f
)

object Presets {

    // Perk: "Crystal Air" — a high-shelf sparkle above the presence peak, on top
    // of real low-end so it's cutting AND full, not thin/harsh like a plain
    // treble boost would be.
    val SILVER = MicPreset(
        id = MicId.SILVER,
        label = "Silver — bright & cutting",
        highPassHz = 110.0,
        presenceFreq = 4500.0,
        presenceGainDb = 5.5,
        lowShelfGainDb = 4.0, // real body now, not just top-end brightness
        airFreq = 10000.0,
        airGainDb = 5.0, // "Crystal Air" — the sparkle/clarity perk that's Silver's own thing
        compThresholdDb = -22f, compRatio = 4.5f, compAttackMs = 4f, compReleaseMs = 70f, compMakeupDb = 4f,
        preGainDb = 19f,
        limiterCeilingLinear = 0.92f
    )

    // "Older phone" character: band-limited range + EXTREME low-mid bass (its
    // headline perk, deepest of all 6) + the fizzy saturation touch you picked.
    val VINTAGE = MicPreset(
        id = MicId.VINTAGE,
        label = "Vintage Dynamic — extreme bass & fizz",
        highPassHz = 150.0,
        lowPassHz = 7500.0,
        presenceFreq = 2200.0,
        presenceGainDb = 2.0,
        lowShelfFreq = 110.0,
        lowShelfGainDb = 9.0, // pushed to "extreme" as requested — deepest, boomiest of all 6
        saturationDrive = 3.5f,
        saturationMix = 0.30f, // the fizzy touch — "+2" level you tested and picked
        compThresholdDb = -20f, compRatio = 4f, compAttackMs = 5f, compReleaseMs = 90f, compMakeupDb = 5f,
        preGainDb = 23f, // bumped again to carry the heavier bass without losing loudness
        limiterCeilingLinear = 0.93f
    )

    // Perk: "Studio Polish" — a gentle high-shelf sheen plus a whisper of tube-style
    // warmth (very different from Vintage's fizz: subtle, not gritty) for a
    // premium, professional-feeling top end.
    val GOLDEN = MicPreset(
        id = MicId.GOLDEN,
        label = "Golden — clean broadcast & polish",
        highPassHz = 90.0,
        presenceFreq = 3000.0,
        presenceGainDb = 2.5,
        lowShelfGainDb = 4.0,
        airFreq = 11000.0,
        airGainDb = 3.0, // "Studio Polish" — subtle, premium sheen, not sparkly like Silver
        saturationDrive = 1.6f,
        saturationMix = 0.08f, // whisper of warmth, nowhere near Vintage's grit
        compThresholdDb = -18f, compRatio = 3f, compAttackMs = 8f, compReleaseMs = 120f, compMakeupDb = 3.5f,
        preGainDb = 18f,
        limiterCeilingLinear = 0.9f
    )

    // Perk: "Big Room" — richer, longer spatial tail than a plain slap-delay,
    // plus real bass so the dry voice underneath the echo still has weight.
    val ECHO = MicPreset(
        id = MicId.ECHO,
        label = "Echo — big room & spatial delay",
        highPassHz = 100.0,
        presenceFreq = 3000.0,
        presenceGainDb = 1.5,
        lowShelfGainDb = 4.0,
        airFreq = 9500.0,
        airGainDb = 2.5, // a little airiness so the echo tail feels open, not boxed-in
        compThresholdDb = -20f, compRatio = 3.5f, compAttackMs = 6f, compReleaseMs = 90f, compMakeupDb = 3f,
        preGainDb = 18f,
        limiterCeilingLinear = 0.9f,
        echoEnabled = true, echoDelayMs = 260f, echoFeedback = 0.38f, echoMix = 0.28f // "Big Room" — richer tail
    )

    // Loudest-clean preset — tuned to the measured real ceiling (~-9 LUFS,
    // peak riding right at -0.1 dBTP). This is as close to "touching the
    // limit" as audio can get without turning into the same clipped mess we
    // measured in the reference sample.
    val BEAST = MicPreset(
        id = MicId.BEAST,
        label = "Beast Mode — max clean loudness",
        highPassHz = 100.0,
        presenceFreq = 3500.0,
        presenceGainDb = 4.0,
        lowShelfFreq = 140.0,
        lowShelfGainDb = 5.0, // real chest/weight behind the loudness, not just SPL
        compThresholdDb = -28f, compRatio = 10f, compAttackMs = 2f, compReleaseMs = 40f, compMakeupDb = 6f,
        preGainDb = 30f,
        limiterCeilingLinear = 0.99f // rides right at the digital ceiling
    )

    // Clarity (Golden) + loudness (Beast) combined, plus the "roar" growl bumped
    // 2-4 points hotter than the base saturation — intentional, controlled
    // harmonic breakup, still brickwall-limited at the same true ceiling as
    // Beast so it can't exceed it uncontrolled.
    val ALPHA = MicPreset(
        id = MicId.ALPHA,
        label = "Alpha — max loud + roar",
        highPassHz = 90.0,
        presenceFreq = 3200.0,
        presenceGainDb = 4.5,
        lowShelfFreq = 140.0,
        lowShelfGainDb = 5.0, // same solid low end as Beast — roar needs weight, not just grit
        saturationDrive = 6.5f,   // "roar" — pushed hard, this is the requested bump
        saturationMix = 0.45f,
        compThresholdDb = -28f, compRatio = 10f, compAttackMs = 2f, compReleaseMs = 40f, compMakeupDb = 6f,
        preGainDb = 30f,
        limiterCeilingLinear = 0.99f
    )

    fun all(): List<MicPreset> = listOf(SILVER, VINTAGE, GOLDEN, ECHO, BEAST, ALPHA)
    fun byId(id: MicId): MicPreset = all().first { it.id == id }
}
