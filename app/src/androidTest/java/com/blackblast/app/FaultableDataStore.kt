package com.blackblast.app

import androidx.datastore.core.DataStore
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Test-only DataStore wrapper; injects controlled read or write failures. */
class FaultableDataStore<T>(
    private val delegate: DataStore<T>,
    @Volatile var failNextRead: Boolean = false,
    @Volatile var failWrites: Boolean = false,
) : DataStore<T> {

    val writeCount = AtomicInteger(0)
    val readCount = AtomicInteger(0)
    @Volatile var readBarrier: CompletableDeferred<Unit>? = null

    override val data: Flow<T>
        get() = flow {
            readCount.incrementAndGet()
            readBarrier?.await()
            if (failNextRead) throw IOException("[FaultableDataStore] injected read failure")
            emitAll(delegate.data)
        }

    override suspend fun updateData(transform: suspend (t: T) -> T): T {
        writeCount.incrementAndGet()
        if (failWrites) throw IOException("[FaultableDataStore] injected write failure")
        return delegate.updateData(transform)
    }
}
