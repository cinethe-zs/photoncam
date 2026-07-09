package com.photoncam.processing

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class GrainProcessor @Inject constructor() {

    suspend fun applyGrain(bitmap: Bitmap, amount: Float, size: Float): Bitmap {
        if (amount <= 0f) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val seed = System.nanoTime()

        val noiseScale = (1f / size).coerceIn(0.1f, 1f)
        val noiseW = (width * noiseScale).toInt().coerceAtLeast(1)
        val noiseH = (height * noiseScale).toInt().coerceAtLeast(1)
        // Generate the noise field in parallel; each chunk uses an independent RNG seeded
        // from the base seed. Grain is stochastic per-shot anyway, so the exact pattern is
        // irrelevant — this just removes a long single-threaded fill for large noise fields.
        val noise = IntArray(noiseW * noiseH)
        forEachChunk(noise.size) { start, end ->
            val r = Random(seed xor (start * 0x9E3779B97F4A7C15uL.toLong()))
            for (j in start until end) noise[j] = r.nextInt(256)
        }

        val maxDelta = (amount * 72f).toInt().coerceIn(1, 72)

        // Parallel over row-chunks; x/y tracked incrementally to avoid a modulo + divide
        // per pixel. The noise array is read-only here, so chunks don't need synchronization.
        forEachChunk(pixels.size) { start, end ->
            var x = start % width
            var y = start / width
            for (i in start until end) {
                val nx = (x * noiseScale).toInt().coerceIn(0, noiseW - 1)
                val ny = (y * noiseScale).toInt().coerceIn(0, noiseH - 1)
                val n = noise[ny * noiseW + nx]
                val d = (((n / 255f) * 2f - 1f) * maxDelta).toInt()

                val pixel = pixels[i]
                val r = ((pixel shr 16) and 0xFF)
                val g = ((pixel shr 8) and 0xFF)
                val b = (pixel and 0xFF)

                pixels[i] = (pixel and 0xFF000000.toInt()) or
                    ((r + d).coerceIn(0, 255) shl 16) or
                    ((g + d).coerceIn(0, 255) shl 8) or
                    (b + d).coerceIn(0, 255)

                if (++x == width) { x = 0; y++ }
            }
        }

        // Write modified pixels back onto the (already mutable) source bitmap in-place.
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
