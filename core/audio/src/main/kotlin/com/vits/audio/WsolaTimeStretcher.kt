package com.vits.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Streaming, pitch-preserving time stretcher (WSOLA: waveform-similarity overlap-add) that
 * supports a speed that varies continuously over the input, which is what speed curves need.
 *
 * Each output hop of [hop] samples is synthesized from a Hann-windowed input frame whose start
 * is searched within ±[tolerance] of the nominal position, choosing the offset that best continues
 * the previous frame's waveform. Output length tracks ∫ 1/speed(t) dt over the input, so it stays
 * in sync with video retimed by the same speed function.
 *
 * @param speedAt speed multiplier at an absolute input frame index (samples per channel).
 * @param sink receives interleaved 16-bit PCM; the array is reused between calls.
 */
class WsolaTimeStretcher(
    sampleRate: Int,
    private val channels: Int,
    private val speedAt: (inputFrame: Long) -> Double,
    private val sink: (pcm: ShortArray, frames: Int) -> Unit,
) {
    private val frameLen = (sampleRate * 0.025).toInt().let { it + (it and 1) }  // ~25 ms, even
    private val hop = frameLen / 2
    private val tolerance = hop
    private val window = FloatArray(frameLen) { (0.5 - 0.5 * cos(2 * PI * it / frameLen)).toFloat() }

    // Input history: interleaved samples plus a mono mix used only for the similarity search.
    private var inData = FloatArray(frameLen * channels * 8)
    private var inMono = FloatArray(frameLen * 8)
    private var inStart = 0L      // absolute frame index of inMono[0]
    private var inCount = 0       // frames currently buffered

    private val ola = FloatArray(frameLen * channels)
    private val outPcm = ShortArray(hop * channels)

    private var nominal = 0.0     // absolute nominal start of the next analysis frame
    private var prevSel = -1L     // absolute start of the previously selected frame
    private var ended = false

    fun queue(pcm: ShortArray, frames: Int) {
        check(!ended) { "queue after end" }
        ensureCapacity(inCount + frames)
        var src = 0
        for (f in 0 until frames) {
            val dstFrame = inCount + f
            var mono = 0f
            for (c in 0 until channels) {
                val v = pcm[src++] / 32768f
                inData[dstFrame * channels + c] = v
                mono += v
            }
            inMono[dstFrame] = mono / channels
        }
        inCount += frames
        while (canProduce()) produceHop()
        compact()
    }

    /** Flushes the remaining input. Call once after the last [queue]. */
    fun finish() {
        if (ended) return
        ended = true
        val inputEnd = inStart + inCount
        // Pad with silence so the last real samples get windowed out completely.
        val pad = frameLen * 2 + tolerance * 2
        ensureCapacity(inCount + pad)
        inMono.fill(0f, inCount, inCount + pad)
        inData.fill(0f, inCount * channels, (inCount + pad) * channels)
        inCount += pad
        while (nominal < inputEnd) produceHop()
        for (i in 0 until hop * channels) outPcm[i] = toPcm(ola[i])
        sink(outPcm, hop)
    }

    private fun canProduce(): Boolean {
        val need = maxOf(nominal.roundToLong() + tolerance, prevSel + hop) + frameLen
        return need <= inStart + inCount
    }

    private fun produceHop() {
        val sel = if (prevSel < 0) 0L else bestOffset()
        val base = (sel - inStart).toInt() * channels
        val first = prevSel < 0
        for (k in 0 until frameLen) {
            // The very first frame has no left neighbour: keep its rising half at full gain.
            val w = if (first && k < hop) 1f else window[k]
            val o = k * channels
            for (c in 0 until channels) ola[o + c] += w * inData[base + o + c]
        }
        for (i in 0 until hop * channels) outPcm[i] = toPcm(ola[i])
        sink(outPcm, hop)
        ola.copyInto(ola, 0, hop * channels, frameLen * channels)
        ola.fill(0f, (frameLen - hop) * channels)

        prevSel = sel
        nominal += hop * speedAt(nominal.toLong()).coerceIn(0.01, 1000.0)
    }

    /** Frame start near [nominal] whose waveform best continues the previous frame. */
    private fun bestOffset(): Long {
        val natural = (prevSel + hop - inStart).toInt()
        val center = (nominal.roundToLong() - inStart).toInt()
        val lo = maxOf(0, center - tolerance)
        val hi = center + tolerance
        var coarse = center.coerceAtLeast(lo)
        var coarseScore = Float.NEGATIVE_INFINITY
        var c = lo
        while (c <= hi) {  // coarse pass on a 4× decimated grid
            val s = similarity(c, natural, 4)
            if (s > coarseScore) { coarseScore = s; coarse = c }
            c += 4
        }
        // Refine at a finer stride. The natural continuation competes too, with a slight bias, so
        // near-1x speeds pass audio through untouched instead of jumping by whole periods.
        var best = coarse
        var bestScore = Float.NEGATIVE_INFINITY
        if (natural in lo..hi) {
            best = natural
            bestScore = similarity(natural, natural, 2) * 0.999f
        }
        for (d in -3..3) {
            val cand = coarse + d
            if (cand < lo || cand > hi) continue
            val s = similarity(cand, natural, 2)
            if (s > bestScore) { bestScore = s; best = cand }
        }
        return best + inStart
    }

    private fun similarity(cand: Int, ref: Int, stride: Int): Float {
        var dot = 0f
        var energy = 1e-9f
        var k = 0
        while (k < frameLen) {
            val a = inMono[cand + k]
            dot += a * inMono[ref + k]
            energy += a * a
            k += stride
        }
        return dot / sqrt(energy)
    }

    private fun ensureCapacity(frames: Int) {
        if (frames <= inMono.size) return
        val size = maxOf(frames, inMono.size * 2)
        inMono = inMono.copyOf(size)
        inData = inData.copyOf(size * channels)
    }

    /** Drops input that no future frame can reference. */
    private fun compact() {
        val keepFrom = minOf(prevSel + hop, nominal.roundToLong() - tolerance)
        val drop = (keepFrom - inStart).toInt().coerceIn(0, inCount)
        if (drop < inMono.size / 2) return
        inMono.copyInto(inMono, 0, drop, inCount)
        inData.copyInto(inData, 0, drop * channels, inCount * channels)
        inStart += drop
        inCount -= drop
    }

    private fun toPcm(v: Float): Short = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
}
