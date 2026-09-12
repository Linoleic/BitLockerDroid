package com.bitlockerdroid.ntfs

import java.util.Arrays

/**
 * An [NtfsBlockSource] wrapper that adds an in-memory LRU block cache and ensures
 * all underlying disk reads and decryptions are strictly sector-aligned.
 *
 * Benefits:
 * 1. Sector Alignment Guarantee:
 *    Native decryption (dis_decrypt_region) strictly requires both offset and length
 *    to be multiples of the sector size (512 bytes). Higher layers (e.g. Fat32Reader
 *    reading a 4-byte FAT cluster number or 32-byte entry) can request unaligned or
 *    arbitrary byte slices; CachedBlockSource transparently maps them to aligned blocks.
 *
 * 2. High-Performance LRU Block Caching:
 *    Instead of invoking a separate `su -c dd` process for every small read (which costs
 *    30-50ms per fork), CachedBlockSource reads 64KB blocks. Consecutive MFT records,
 *    FAT cluster lookups, and directory entries hit the memory cache with zero IPC overhead,
 *    speeding up directory enumeration and traversal by orders of magnitude.
 *
 * 3. Secure Memory Zeroing:
 *    On volume teardown or close, [clear] explicitly fills all cached plaintext blocks
 *    with zeros before releasing references.
 */
class CachedBlockSource(
    private val volumeSize: Long,
    val sectorSize: Int = 512,
    val blockSize: Int = DEFAULT_BLOCK_SIZE,
    val maxCacheBlocks: Int = DEFAULT_MAX_CACHE_BLOCKS,
    private val rawSectorReader: (offset: Long, len: Int) -> ByteArray?
) : NtfsBlockSource {

    companion object {
        const val DEFAULT_BLOCK_SIZE = 64 * 1024 // 64 KB per block
        const val DEFAULT_MAX_CACHE_BLOCKS = 64  // Up to 4 MB cache footprint
    }

    override val size: Long get() = volumeSize

    private val lock = Any()

    /** LRU cache of blockStart (absolute byte offset) -> decrypted plaintext bytes. */
    private val cache = object : LinkedHashMap<Long, ByteArray>(maxCacheBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean {
            val evict = size > maxCacheBlocks
            if (evict && eldest?.value != null) {
                Arrays.fill(eldest.value, 0.toByte())
            }
            return evict
        }
    }

    override fun read(offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
        if (offset < 0 || offset >= volumeSize || len <= 0) return 0
        if (dstPos < 0 || dstPos >= dst.size) return 0

        val toRead = minOf(len.toLong(), volumeSize - offset).toInt()
        val safeToRead = minOf(toRead, dst.size - dstPos)
        if (safeToRead <= 0) return 0

        var bytesCopied = 0
        while (bytesCopied < safeToRead) {
            val curOffset = offset + bytesCopied
            val blockIndex = curOffset / blockSize
            val blockStart = blockIndex * blockSize
            val inBlockOff = (curOffset - blockStart).toInt()

            val blockData = getOrLoadBlock(blockStart) ?: break
            if (inBlockOff >= blockData.size) break

            val available = blockData.size - inBlockOff
            val chunk = minOf(safeToRead - bytesCopied, available)
            if (chunk <= 0) break

            System.arraycopy(blockData, inBlockOff, dst, dstPos + bytesCopied, chunk)
            bytesCopied += chunk
        }

        return bytesCopied
    }

    private fun getOrLoadBlock(blockStart: Long): ByteArray? {
        synchronized(lock) {
            cache[blockStart]?.let { return it }
        }

        val validLen = minOf(blockSize.toLong(), volumeSize - blockStart).toInt()
        if (validLen <= 0) return null

        val effSectorSize = if (sectorSize > 0) sectorSize else 512
        // Ensure aligned to sectorSize
        val alignedLen = ((validLen + effSectorSize - 1) / effSectorSize) * effSectorSize

        val decrypted = rawSectorReader(blockStart, alignedLen) ?: return null
        val blockData = if (decrypted.size == validLen) {
            decrypted
        } else if (decrypted.size > validLen) {
            decrypted.copyOf(validLen)
        } else {
            decrypted
        }

        synchronized(lock) {
            cache[blockStart] = blockData
        }
        return blockData
    }

    /** Clears and zeros out all cached plaintext memory. */
    fun clear() {
        synchronized(lock) {
            for (arr in cache.values) {
                Arrays.fill(arr, 0.toByte())
            }
            cache.clear()
        }
    }
}
