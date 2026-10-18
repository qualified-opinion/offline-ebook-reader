package com.jan.offlinereader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal data class ReadingPosition(val bookId: String, val index: Int, val offset: Int, val progress: Float)

/** One application-owned worker; it never captures a ViewModel or a screen. */
internal class PositionSaver(scope: CoroutineScope, write: suspend (ReadingPosition) -> Unit) {
    private val pending = ConcurrentHashMap<String, ReadingPosition>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val flushes = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    private val failures = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors = failures.asSharedFlow()

    init {
        scope.launch {
            for (signal in signals) {
                val waiting = generateSequence { flushes.poll() }.toList()
                var failure: Exception? = null
                for ((id, position) in pending.entries.toList()) {
                    if (pending.remove(id, position)) {
                        try { write(position) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            failure = e
                            failures.tryEmit("Your reading spot couldn't be saved. Check free storage.")
                        }
                    }
                }
                for (waiter in waiting) {
                    if (failure == null) waiter.complete(Unit) else waiter.completeExceptionally(failure)
                }
                delay(150)
            }
        }
    }

    suspend fun awaitPendingSaves() {
        val waiter = CompletableDeferred<Unit>()
        flushes.add(waiter)
        signals.trySend(Unit)
        try { waiter.await() } finally { flushes.remove(waiter) }
    }

    fun enqueue(position: ReadingPosition) {
        pending[position.bookId] = position
        signals.trySend(Unit)
    }
}
