package de.hecate.core.geo

/**
 * One 64-bit key for a cell of a square grid: [x] in the high half, the low 32
 * bits of [y] in the low half.
 *
 * Every grid in this project — the matcher's spatial index, the clustering
 * metric's buckets, the coverage bins — hashes its cells this way, and two of
 * them used to spell it with `xor` and one with `or`. Those are the same thing
 * here, which is why one function serves all three: `x shl 32` has nothing in
 * its low half, and `y and 0xffffffff` has nothing in its high half, so no bit
 * is ever set on both sides. Only [x]'s low 32 bits survive the shift, so the
 * key is unique for any cell index that fits an `Int`.
 */
internal fun cellKey(x: Long, y: Long): Long = (x shl 32) or (y and 0xffffffffL)
