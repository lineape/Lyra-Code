package com.yukisoffd.lyracode.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Breadth-first trie: sorted children occupy consecutive slots in primitive arrays. */
internal class CompactTokenTrie private constructor(
    private val labels: CharArray,
    private val firstChild: IntArray,
    private val childCount: IntArray,
    private val terminal: BooleanArray,
) {
    internal val nodeCount: Int get() = labels.size
    // Most lookups begin at the wide root. A 256 KiB direct table avoids a binary search per token.
    private val rootNext = IntArray(65_536) { -1 }.also { lookup ->
        for (child in firstChild[0] until firstChild[0] + childCount[0]) lookup[labels[child].code] = child
    }

    fun count(text: String): Long {
        var index = 0
        var tokens = 0L
        while (index < text.length) {
            var node = rootNext[text[index].code]
            if (node < 0) {
                index++
                tokens++
                continue
            }
            var cursor = index + 1
            var longest = index + 1
            while (cursor < text.length) {
                val target = text[cursor]
                var low = firstChild[node]
                var high = low + childCount[node] - 1
                var next = -1
                while (low <= high) {
                    val middle = (low + high) ushr 1
                    val label = labels[middle]
                    when {
                        label < target -> low = middle + 1
                        label > target -> high = middle - 1
                        else -> { next = middle; break }
                    }
                }
                if (next < 0) break
                node = next
                cursor++
                if (terminal[node]) longest = cursor
            }
            index = longest
            tokens++
        }
        return tokens
    }

    fun write(output: OutputStream) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(nodeCount)
        for (node in labels.indices) {
            data.writeChar(labels[node].code)
            data.writeInt(firstChild[node])
            data.writeInt(childCount[node])
            data.writeBoolean(terminal[node])
        }
    }

    companion object {
        private const val MAGIC = 0x4C545231
        private const val MAX_NODES = 4_000_000

        fun read(input: InputStream): CompactTokenTrie {
            val data = DataInputStream(input)
            require(data.readInt() == MAGIC)
            val size = data.readInt()
            require(size in 1..MAX_NODES)
            val labels = CharArray(size)
            val first = IntArray(size)
            val counts = IntArray(size)
            val terminal = BooleanArray(size)
            for (node in 0 until size) {
                labels[node] = data.readChar()
                first[node] = data.readInt()
                counts[node] = data.readInt()
                terminal[node] = data.readBoolean()
                require(counts[node] >= 0 && first[node] >= 0 && first[node] <= size - counts[node])
                require(counts[node] == 0 || first[node] > node)
            }
            require(data.read() == -1)
            return CompactTokenTrie(labels, first, counts, terminal)
        }

        fun fromTokens(tokens: Iterable<String>): CompactTokenTrie {
            val words = buildList {
                for (token in tokens) {
                    if (token.isBlank() || token.length > 256 || token.startsWith("<｜")) continue
                    add(token)
                    val normalized = token.replace("Ġ", " ").replace("▁", " ")
                        .replace("Ċ", "\n").replace("ĉ", "\t")
                    if (normalized != token && normalized.isNotBlank()) add(normalized)
                }
            }.distinct().sorted()
            var capacity = 1024
            var labels = CharArray(capacity)
            var first = IntArray(capacity)
            var counts = IntArray(capacity)
            var terminal = BooleanArray(capacity)
            // Queue stores integer ranges into the sorted vocabulary, without node objects/maps.
            var starts = IntArray(capacity)
            var ends = IntArray(capacity)
            var depths = IntArray(capacity)
            var size = 1
            ends[0] = words.size
            var node = 0
            while (node < size) {
                var start = starts[node]
                val end = ends[node]
                val depth = depths[node]
                first[node] = size
                while (start < end) {
                    val label = words[start][depth]
                    var next = start + 1
                    while (next < end && words[next][depth] == label) next++
                    if (size == capacity) {
                        capacity *= 2
                        require(capacity <= MAX_NODES * 2)
                        labels = labels.copyOf(capacity)
                        first = first.copyOf(capacity)
                        counts = counts.copyOf(capacity)
                        terminal = terminal.copyOf(capacity)
                        starts = starts.copyOf(capacity)
                        ends = ends.copyOf(capacity)
                        depths = depths.copyOf(capacity)
                    }
                    labels[size] = label
                    val isTerminal = words[start].length == depth + 1
                    terminal[size] = isTerminal
                    starts[size] = start + if (isTerminal) 1 else 0
                    ends[size] = next
                    depths[size] = depth + 1
                    size++
                    counts[node]++
                    start = next
                }
                node++
            }
            require(size <= MAX_NODES)
            return CompactTokenTrie(labels.copyOf(size), first.copyOf(size), counts.copyOf(size), terminal.copyOf(size))
        }
    }
}
