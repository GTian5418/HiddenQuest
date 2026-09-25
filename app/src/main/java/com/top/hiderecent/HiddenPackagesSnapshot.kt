package com.top.hiderecent

import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicReference

internal class HiddenPackagesSnapshot(initial: Set<String> = emptySet()) {
    private val ref = AtomicReference(copyOf(initial))

    fun get(): Set<String> = ref.get()

    fun replace(next: Set<String>) {
        ref.set(copyOf(next))
    }

    private fun copyOf(source: Set<String>): Set<String> = LinkedHashSet(source)
}
