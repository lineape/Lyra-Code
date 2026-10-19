package com.yukisoffd.lyracode.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class CompactTokenTrieTest {
    private class ReferenceTrie(tokens: Iterable<String>) {
        private class Node {
            val children = HashMap<Char, Node>()
            var terminal = false
        }
        private val root = Node()
        init {
            for (token in tokens) {
                if (token.isBlank() || token.length > 256 || token.startsWith("<｜")) continue
                fun insert(word: String) {
                    var node = root
                    for (ch in word) node = node.children.getOrPut(ch) { Node() }
                    node.terminal = true
                }
                insert(token)
                val normalized = token.replace("Ġ", " ").replace("▁", " ").replace("Ċ", "\n").replace("ĉ", "\t")
                if (normalized != token && normalized.isNotBlank()) insert(normalized)
            }
        }
        fun count(text: String): Long {
            var start = 0
            var count = 0L
            while (start < text.length) {
                var node = root
                var next = start
                var end = start + 1
                while (next < text.length) {
                    node = node.children[text[next]] ?: break
                    next++
                    if (node.terminal) end = next
                }
                start = end
                count++
            }
            return count
        }
    }

    @Test fun greedyMatchingNormalizationAndBinaryRoundTripPreserveCounts() {
        val tokens = listOf("a", "ab", "abcd", "Ġword", "▁你好", "Ċline", "ĉtab", "你好", "😀", "<｜ignore", " ", "x".repeat(257))
        val compact = CompactTokenTrie.fromTokens(tokens)
        val bytes = ByteArrayOutputStream().also { compact.write(it) }.toByteArray()
        val restored = CompactTokenTrie.read(ByteArrayInputStream(bytes))
        val reference = ReferenceTrie(tokens)
        val random = Random(42)
        val alphabet = "abcd word\n\t你好😀xyz"
        val texts = listOf("", "abc", "abcde", " word你好\nline\ttab", "<｜ignore", "😀你好", "x".repeat(257)) +
            List(1000) { buildString { repeat(random.nextInt(1, 400)) { append(alphabet.random(random)) } } }
        for (text in texts) {
            assertEquals(text, reference.count(text), compact.count(text))
            assertEquals(text, compact.count(text), restored.count(text))
        }
        assertEquals(8 + compact.nodeCount * 11, bytes.size)
        assertThrows(Exception::class.java) { CompactTokenTrie.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 1))) }
        assertThrows(Exception::class.java) { CompactTokenTrie.read(ByteArrayInputStream(ByteArray(8))) }
    }

    @Test fun shippedVocabularyMatchesOriginalAlgorithm() {
        val asset = listOf(File("../deepseek_v3_tokenizer/deepseek_v3_tokenizer/tokenizer.json"),
            File("deepseek_v3_tokenizer/deepseek_v3_tokenizer/tokenizer.json")).first { it.isFile }
        val vocabulary = JSONObject(asset.readText()).getJSONObject("model").getJSONObject("vocab").keys().asSequence().toList()
        val start = System.nanoTime()
        val compact = CompactTokenTrie.fromTokens(vocabulary)
        val buildMs = (System.nanoTime() - start) / 1_000_000
        val reference = ReferenceTrie(vocabulary)
        val random = Random(2026)
        val texts = List(300) { List(30) { vocabulary.random(random) }.joinToString(" ") } + listOf(
            "优化 tokenizer 历史对话的统计性能，包含中文、English 和 emoji 😀。",
            "fun main() { println(\"hello world\") }\n".repeat(100),
            "a".repeat(10_000), "这是超长工具结果。".repeat(1000),
        )
        for (text in texts) assertEquals(reference.count(text), compact.count(text))
        println("Compact trie: vocab=${vocabulary.size}, nodes=${compact.nodeCount}, primitiveBytes=${compact.nodeCount * 11}, buildMs=$buildMs")
    }

    @Test fun countCacheEvictsByTextSizeAndEntryCount() {
        val cache = TokenCountCache(maxChars = 5, maxEntries = 2)
        var calls = 0
        fun count(text: String) = cache.count(text) { calls++; text.length.toLong() }
        count("ab"); count("cd"); count("ab")
        assertEquals(2, calls)
        count("efg"); count("ab") // access to ab kept it newer than cd
        assertEquals(3, calls)
        count("cd")
        assertEquals(4, calls)
        count("oversized"); count("oversized")
        assertEquals(6, calls)
        count(""); count("x"); count(""); count("y"); count("x")
        assertEquals(10, calls)
    }
}
