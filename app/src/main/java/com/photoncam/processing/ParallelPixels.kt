package com.photoncam.processing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Threshold below which parallel dispatch isn't worth the coroutine overhead. */
private const val PARALLEL_MIN_SIZE = 100_000

/**
 * Splits the half-open range [0, [size]) into one contiguous chunk per CPU core and runs
 * [block] on each `[start, end)` concurrently on [Dispatchers.Default]. For small ranges
 * (or single-core devices) it runs [block] once on the whole range with no dispatch.
 *
 * [block] must only touch its own sub-range of any shared array (and may freely read
 * shared read-only data), so the chunks never write the same index — no locking needed.
 */
suspend fun forEachChunk(size: Int, block: (start: Int, end: Int) -> Unit): Unit = coroutineScope {
    val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    if (cores == 1 || size < PARALLEL_MIN_SIZE) {
        block(0, size)
        return@coroutineScope
    }
    val chunk = (size + cores - 1) / cores
    (0 until cores).mapNotNull { c ->
        val start = c * chunk
        val end = minOf(start + chunk, size)
        if (start >= end) null else async(Dispatchers.Default) { block(start, end) }
    }.awaitAll()
}

/** Branch-free linear interpolation, inlined into the hot pixel loops. */
inline fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
