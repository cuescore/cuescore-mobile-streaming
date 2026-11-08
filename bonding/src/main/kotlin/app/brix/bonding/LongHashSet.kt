package app.brix.bonding

/**
 * Packet sequence-number set without boxing.
 *
 * Why: `packetsInFlight` was `HashSet<Long>`, boxing each number on insertion,
 * lookup and every element of a full traversal. Measurement 31.08 showed
 * ~350 microseconds of CPU per packet instead of single/tens of microseconds,
 * with packet processing generating three times the heat of encoding.
 * Open addressing with linear probing avoids all per-operation allocation.
 *
 * SRT numbers are in 0..2^32-1, leaving -1 available as the empty-slot marker.
 *
 * Not thread-safe: the caller holds its own monitor, as before.
 */
internal class LongHashSet(initialCapacity: Int = 2048) {

    private var keys = LongArray(tableSizeFor(initialCapacity)) { EMPTY }
    private var mask = keys.size - 1

    var size: Int = 0
        private set

    fun add(value: Long): Boolean {
        require(value != EMPTY) { "номер $value зарезервирован как метка пустоты" }
        var i = index(value)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) {
                keys[i] = value
                size++
                if (size * 10 >= keys.size * 7) grow()
                return true
            }
            if (k == value) return false
            i = (i + 1) and mask
        }
    }

    fun remove(value: Long): Boolean {
        if (value == EMPTY) return false
        var i = index(value)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) return false
            if (k == value) {
                removeAt(i)
                size--
                return true
            }
            i = (i + 1) and mask
        }
    }

    /** Remove everything matching the predicate. Return true if anything was removed. */
    inline fun removeIf(predicate: (Long) -> Boolean): Boolean {
        var removed = false
        var i = 0
        while (i < capacity()) {
            val k = keyAt(i)
            if (k != EMPTY && predicate(k)) {
                // Deletion shifts may move an element into an already visited
                // slot, so recheck without advancing the position.
                removeSlot(i)
                removed = true
            } else {
                i++
            }
        }
        return removed
    }

    fun clear() {
        keys.fill(EMPTY)
        size = 0
    }

    /** For removeIf: operate directly on slots without creating an iterator. */
    @PublishedApi
    internal fun capacity(): Int = keys.size

    @PublishedApi
    internal fun keyAt(i: Int): Long = keys[i]

    @PublishedApi
    internal fun removeSlot(i: Int) {
        removeAt(i)
        size--
    }

    /**
     * Open-addressing deletion: leaving an empty slot in a probe chain makes
     * subsequent elements unfindable. Classic Knuth shift (algorithm R, 6.4).
     */
    private fun removeAt(pos: Int) {
        var i = pos
        keys[i] = EMPTY
        var j = i
        while (true) {
            j = (j + 1) and mask
            val k = keys[j]
            if (k == EMPTY) return
            val home = index(k)
            // If k lies "beyond" the hole in its probe chain, shift it.
            val shouldMove = if (j > i) home <= i || home > j else home <= i && home > j
            if (shouldMove) {
                keys[i] = k
                keys[j] = EMPTY
                i = j
            }
        }
    }

    private fun grow() {
        val old = keys
        keys = LongArray(old.size * 2) { EMPTY }
        mask = keys.size - 1
        size = 0
        for (k in old) if (k != EMPTY) add(k)
    }

    private fun index(value: Long): Int {
        // Mix sequential numbers to prevent linear probing from degenerating
        // into long chains.
        var h = value * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return (h.toInt()) and mask
    }

    companion object {
        const val EMPTY = -1L

        private fun tableSizeFor(n: Int): Int {
            var c = 16
            while (c < n) c = c shl 1
            return c
        }
    }
}
