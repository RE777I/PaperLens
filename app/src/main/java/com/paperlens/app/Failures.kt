package com.paperlens.app

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for steps the app can carry on without (a missing text layer, an unreadable cache, a page
 * the detector can't place): the failure is logged under [tag] with [what] was being done, so nothing fails
 * silently. Callers keep choosing their fallback (`getOrNull()`, `getOrDefault()`…). Coroutine
 * cancellation isn't a failure and isn't logged.
 */
inline fun <T> runLogged(tag: String, what: String, block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it !is CancellationException) Log.w(tag, what, it) }
