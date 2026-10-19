package com.yukisoffd.lyracode.data

import android.content.Context
import android.util.JsonReader
import java.io.File
import java.util.LinkedHashMap

/** Local estimate, preserving the original greedy vocabulary matching (not model BPE). */
class DeepSeekV3Tokenizer private constructor(private val trie: CompactTokenTrie?) {
    internal val cacheVersion: Int get() = if (trie == null) -1 else LOCAL_TOKEN_CACHE_VERSION
    private val counts = TokenCountCache()

    fun count(text: String): Long = counts.count(text) {
        if (text.isBlank()) 0L else trie?.count(text) ?: estimateFallback(text)
    }

    private fun estimateFallback(text: String): Long {
        var tokens = 0L
        var asciiRun = 0L
        for (ch in text) {
            if (ch.code in 0x20..0x7E) asciiRun++ else {
                tokens += (asciiRun + 3) / 4 + 1
                asciiRun = 0
            }
        }
        return tokens + (asciiRun + 3) / 4
    }

    companion object {
        @Volatile private var cached: DeepSeekV3Tokenizer? = null

        fun get(context: Context): DeepSeekV3Tokenizer = cached ?: synchronized(this) {
            cached ?: load(context.applicationContext).also { cached = it }
        }

        private fun load(context: Context): DeepSeekV3Tokenizer {
            // Disposable, cleared on application upgrades; never cache a fallback trie.
            val file = File(context.codeCacheDir, "deepseek-greedy-v1.trie")
            val diskTrie = runCatching { file.inputStream().buffered().use(CompactTokenTrie::read) }.getOrNull()
            if (diskTrie != null) return DeepSeekV3Tokenizer(diskTrie)
            val trie = runCatching {
                val tokens = ArrayList<String>(160_000)
                JsonReader(context.assets.open("tokenizer.json").bufferedReader()).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() != "model") {
                            reader.skipValue()
                            continue
                        }
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() != "vocab") {
                                reader.skipValue()
                                continue
                            }
                            reader.beginObject()
                            while (reader.hasNext()) {
                                tokens += reader.nextName()
                                reader.skipValue()
                            }
                            reader.endObject()
                        }
                        reader.endObject()
                    }
                    reader.endObject()
                }
                require(tokens.isNotEmpty())
                CompactTokenTrie.fromTokens(tokens)
            }.getOrNull()
            if (trie != null) runCatching {
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.outputStream().buffered().use(trie::write)
                if (!temporary.renameTo(file)) temporary.delete()
            }
            return DeepSeekV3Tokenizer(trie)
        }
    }
}

/** Bounds retained text as well as entry count; large histories belong in the database cache. */
internal class TokenCountCache(private val maxChars: Int = 1_048_576, private val maxEntries: Int = 256) {
    private val values = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var chars = 0

    fun count(text: String, calculate: () -> Long): Long {
        if (text.length > maxChars) return calculate()
        synchronized(this) { values[text]?.let { return it } }
        val result = calculate()
        synchronized(this) {
            if (values.put(text, result) == null) chars += text.length
            val iterator = values.entries.iterator()
            while (chars > maxChars || values.size > maxEntries) {
                chars -= iterator.next().key.length
                iterator.remove()
            }
        }
        return result
    }
}
