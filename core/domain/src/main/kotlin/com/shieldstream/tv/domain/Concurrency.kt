package com.shieldstream.tv.domain

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

suspend fun <T, R> Iterable<T>.mapConcurrent(
    concurrency: Int = 4,
    block: suspend (T) -> R,
): List<R> = coroutineScope {
    require(concurrency > 0)
    val semaphore = Semaphore(concurrency)
    map { element ->
        async { semaphore.withPermit { block(element) } }
    }.awaitAll()
}

/**
 * Groups a burst of values into lists, at most about two per [windowMs]: a value after a quiet
 * period is sent at once, and values arriving during the following window are sent together when
 * it ends. Nothing is dropped; the last batch is sent before the flow completes.
 */
fun <T> Flow<T>.batched(windowMs: Long): Flow<List<T>> = channelFlow {
    require(windowMs > 0)
    val mutex = Mutex()
    val buffer = ArrayList<T>()
    var window: Job? = null

    suspend fun flush() {
        val batch = mutex.withLock { if (buffer.isEmpty()) null else ArrayList(buffer).also { buffer.clear() } }
        if (batch != null) send(batch)
    }

    collect { value ->
        mutex.withLock { buffer += value }
        if (window?.isActive != true) {
            flush()
            window = launch {
                delay(windowMs)
                flush()
            }
        }
    }
    // Joined, not cancelled: a cancelled flush could lose a batch it had already taken.
    window?.join()
    flush()
}
