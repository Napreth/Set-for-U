package com.napreth.set4u.core.execution

import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal class QueueTimeoutException : Exception("Operation queue timed out")

/** A session remains serial, with bounded admission and FIFO writes ahead of waiting reads. */
internal class OperationScheduler {
    private class Ticket(val write: Boolean) {
        val ready = CompletableDeferred<Unit>()
        var granted = false
    }
    private val lock = Any()
    private val writes = ArrayDeque<Ticket>()
    private val reads = ArrayDeque<Ticket>()
    private var occupied = false

    suspend fun <T> run(write: Boolean, timeoutMillis: Long, block: suspend () -> T): T {
        val ticket = Ticket(write)
        synchronized(lock) {
            if (!occupied) { occupied = true; ticket.granted = true; ticket.ready.complete(Unit) }
            else (if (write) writes else reads).addLast(ticket)
        }
        try {
            if (withTimeoutOrNull(timeoutMillis) { ticket.ready.await(); true } != true) throw QueueTimeoutException()
            return block()
        } finally {
            synchronized(lock) {
                if (!ticket.granted) (if (write) writes else reads).remove(ticket)
                else {
                    val next = writes.pollFirst() ?: reads.pollFirst()
                    if (next == null) occupied = false
                    else { next.granted = true; next.ready.complete(Unit) }
                }
            }
        }
    }
}
