package com.top.hiderecent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenPackagesSnapshotTest {
    @Test
    fun replaceUsesImmutableCopy() {
        val mutable = linkedSetOf("a")
        val snapshot = HiddenPackagesSnapshot()
        snapshot.replace(mutable)
        mutable.add("b")
        assertEquals(setOf("a"), snapshot.get())
    }

    @Test
    fun replaceAtomicallySwapsWholeSet() {
        val snapshot = HiddenPackagesSnapshot(setOf("old"))
        snapshot.replace(setOf("new"))
        val current = snapshot.get()
        assertTrue("new" in current)
        assertFalse("old" in current)
    }
}
