package com.jan.offlinereader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal data class ReadingPosition(val bookId: String, val index: Int, val offset: Int, val progress: Float)

/** One application-owned worker; it never captures a ViewModel or a screen. */
internal class PositionSaver(scope: CoroutineScope, write: suspend (ReadingPosition) -> Unit) {
    private val pending = ConcurrentHashMap<String, ReadingPosition>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val failures = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors = failures.asSharedFlow()

    init {
        scope.launch {
            for (signal in signals) {
                for ((id, position) in pending.entries.toList()) {
                    if (pending.remove(id, position)) {
                        try { write(position) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            failures.tryEmit("Your reading spot couldn't be saved. Check free storage.")
                        }
                    }
                }
                delay(150)
            }
        }
    }

    fun enqueue(position: ReadingPosition) {
        pending[position.bookId] = position
        signals.trySend(Unit)
    }
}
