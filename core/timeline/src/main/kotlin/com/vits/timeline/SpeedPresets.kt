package com.vits.timeline

/** Built-in curve shapes, modelled on the common "montage / hero / bullet" editing styles. */
enum class SpeedPreset(private val raw: List<Pair<Double, Double>>) {
    MONTAGE(listOf(0.0 to 1.0, 0.3 to 1.0, 0.42 to 5.0, 0.58 to 0.3, 0.7 to 1.0, 1.0 to 1.0)),
    HERO(listOf(0.0 to 1.0, 0.2 to 5.0, 0.5 to 0.25, 0.8 to 5.0, 1.0 to 1.0)),
    BULLET(listOf(0.0 to 5.0, 0.3 to 5.0, 0.45 to 0.2, 0.55 to 0.2, 0.7 to 5.0, 1.0 to 5.0)),
    JUMP_CUT(listOf(0.0 to 1.0, 0.38 to 1.0, 0.5 to 8.0, 0.62 to 1.0, 1.0 to 1.0)),
    FLASH_IN(listOf(0.0 to 5.0, 0.35 to 5.0, 0.6 to 1.0, 1.0 to 1.0)),
    FLASH_OUT(listOf(0.0 to 1.0, 0.4 to 1.0, 0.65 to 5.0, 1.0 to 5.0));

    fun curve(): SpeedSpec.Curve = SpeedSpec.Curve(raw.map { (x, s) -> SpeedPoint(x, s) })

    companion object {
        /** Starting point for "Custom": flat 1x with editable interior points. */
        fun custom(): SpeedSpec.Curve = SpeedSpec.Curve(
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { SpeedPoint(it, 1.0) }
        )
    }
}
