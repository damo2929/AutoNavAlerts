// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.util

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * A read-only view of one field of a state flow (DESIGN.md §2 "Threads": the route and marker
 * state each live in one flow, and the screens still collect the fields they always did). Pure
 * and thread-free: `value` is computed on every read from the source's current value, so a read
 * right after a write sees the write, which `stateIn` on a scope would not promise; collectors
 * get the source's current value first and then each result that differs from the last (the
 * StateFlow contract), never a repeat when another field of the source changed.
 */
fun <T, R> StateFlow<T>.view(f: (T) -> R): StateFlow<R> = object : StateFlow<R> {
    override val value: R get() = f(this@view.value)
    override val replayCache: List<R> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        var last: Any? = UNSET
        this@view.collect { v ->
            val r = f(v)
            if (last === UNSET || last != r) {
                last = r
                collector.emit(r)
            }
        }
    }
}

/** "No value yet" for [view]'s first comparison (a null result must still be emitted once). */
private val UNSET = Any()
