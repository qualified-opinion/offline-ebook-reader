package com.jan.offlinereader

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PositionSaverTest {
    private fun position(book: String, index: Int) = ReadingPosition(book, index, index * 2, .5f)

    @Test fun rapidUpdatesKeepOnlyTheLatestPositionForEachBook() = runTest {
        val saved = mutableListOf<ReadingPosition>()
        val saver = PositionSaver(backgroundScope) { saved += it }
        repeat(100) { saver.enqueue(position("a", it)) }
        saver.enqueue(position("b", 7))
        runCurrent()
        assertEquals(2, saved.size)
        assertEquals(mapOf("a" to position("a", 99), "b" to position("b", 7)), saved.associateBy { it.bookId })
    }

    @Test fun updatesDuringAWriteArePreservedAndWritesStaySerialized() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<ReadingPosition>()
        var active = 0
        var peak = 0
        val saver = PositionSaver(backgroundScope) { p ->
            active++; peak = maxOf(peak, active)
            try {
                if (p.index == 1) gate.await()
                saved += p
            } finally { active-- }
        }
        saver.enqueue(position("a", 1))
        runCurrent()
        saver.enqueue(position("a", 2))
        saver.enqueue(position("b", 3))
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(151); runCurrent()
        assertEquals(mapOf("a" to position("a", 2), "b" to position("b", 3)), saved.associateBy { it.bookId })
        assertEquals(1, peak)
    }

    @Test fun queuedSaveFinishesAfterItsCallerScopeIsCancelled() = runTest {
        val saved = mutableListOf<ReadingPosition>()
        val saver = PositionSaver(backgroundScope) { saved += it }
        saver.enqueue(position("a", 0))
        runCurrent()
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        caller.launch { saver.enqueue(position("a", 8)) }
        runCurrent()
        caller.cancel()
        assertEquals(listOf(position("a", 0)), saved)
        advanceTimeBy(151); runCurrent()
        assertEquals(position("a", 8), saved.last())
    }

    @Test fun failedWriteIsReportedAndDoesNotStopSubsequentSaves() = runTest {
        val saved = mutableListOf<ReadingPosition>()
        val errors = mutableListOf<String>()
        val saver = PositionSaver(backgroundScope) {
            if (it.bookId == "bad") throw IOException("Disk full")
            saved += it
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { saver.errors.collect { errors += it } }
        saver.enqueue(position("bad", 1))
        runCurrent()
        assertEquals(listOf("Your reading spot couldn't be saved. Check free storage."), errors)
        saver.enqueue(position("good", 2))
        advanceTimeBy(151); runCurrent()
        assertEquals(listOf(position("good", 2)), saved)
    }

    @Test fun cancellingTheWorkerIsNotReportedAsAStorageError() = runTest {
        val worker = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val errors = mutableListOf<String>()
        val saver = PositionSaver(worker) { awaitCancellation() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { saver.errors.collect { errors += it } }
        saver.enqueue(position("a", 1))
        runCurrent()
        worker.cancel()
        runCurrent()
        assertTrue(errors.isEmpty())
    }

    @Test fun stoppedErrorCollectorsReceiveNoFurtherEvents() = runTest {
        val oldErrors = mutableListOf<String>()
        val currentErrors = mutableListOf<String>()
        val saver = PositionSaver(backgroundScope) { throw IOException("Write failed") }
        val oldOwner = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        oldOwner.launch { saver.errors.collect { oldErrors += it } }
        oldOwner.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { saver.errors.collect { currentErrors += it } }
        saver.enqueue(position("a", 1))
        runCurrent()
        assertTrue(oldErrors.isEmpty())
        assertEquals(1, currentErrors.size)
    }
    @Test fun exportBarrierWaitsForQueuedAndInFlightSaves() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<Int>()
        val saver = PositionSaver(backgroundScope) {
            if (it.index == 1) gate.await()
            saved += it.index
        }
        saver.enqueue(position("a", 1)); runCurrent()
        saver.enqueue(position("a", 2))
        val flushed = async { saver.awaitPendingSaves() }
        runCurrent(); assertFalse(flushed.isCompleted)
        gate.complete(Unit); runCurrent()
        assertFalse(flushed.isCompleted)
        advanceTimeBy(151); runCurrent()
        flushed.await()
        assertEquals(listOf(1, 2), saved)
    }

}
