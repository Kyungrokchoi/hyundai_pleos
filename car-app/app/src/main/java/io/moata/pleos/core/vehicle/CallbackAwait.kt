package io.moata.pleos.core.vehicle

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume


sealed interface CallbackOutcome<out T> {
    data class Complete<T>(val value: T) : CallbackOutcome<T>
    data class Failed(val error: Exception) : CallbackOutcome<Nothing>
    data class Timeout(val afterMs: Long) : CallbackOutcome<Nothing>
    data class Threw(val error: Throwable) : CallbackOutcome<Nothing>
}

fun CallbackOutcome<*>.describe(): String = when (this) {
    is CallbackOutcome.Complete -> "COMPLETE"
    is CallbackOutcome.Failed -> "FAILED(onFailed): ${error::class.java.name}: ${error.message}"
    is CallbackOutcome.Timeout -> "TIMEOUT: neither onComplete nor onFailed within ${afterMs}ms"
    is CallbackOutcome.Threw -> "THREW: ${error::class.java.name}: ${error.message}"
}


suspend fun <T> awaitCallback(
    timeoutMs: Long,
    call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): CallbackOutcome<T> = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine<CallbackOutcome<T>> { cont ->
        val done = AtomicBoolean(false)
        fun finish(o: CallbackOutcome<T>) { if (done.compareAndSet(false, true)) cont.resume(o) }
        try {
            call({ finish(CallbackOutcome.Complete(it)) }, { finish(CallbackOutcome.Failed(it)) })
        } catch (t: Throwable) {
            finish(CallbackOutcome.Threw(t))
        }
    }
} ?: CallbackOutcome.Timeout(timeoutMs)
