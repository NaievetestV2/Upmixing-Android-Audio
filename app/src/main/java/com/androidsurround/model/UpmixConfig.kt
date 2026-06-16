package com.androidsurround.model

data class UpmixConfig(
    val enabled: Boolean = true,
    val method: UpmixMethod = UpmixMethod.PSD,
    val mixLfe: Boolean = true,
    val lfeCutoffHz: Int = 150,
    val fcCutoffHz: Int = 12000,
    val rearDelayMs: Float = 12.0f,
    val lfeLevel: Float = 1.0f,
)

enum class UpmixMethod(val displayName: String, val description: String) {
    PSD("Phase Shift Delay", "All-pass filters for spacious surround"),
    SIMPLE("Simple Matrix", "Basic channel mapping with level adjustments"),
    DOLBY("Dolby Pro Logic II-like", "Dolby-inspired active matrix decoding"),
}
