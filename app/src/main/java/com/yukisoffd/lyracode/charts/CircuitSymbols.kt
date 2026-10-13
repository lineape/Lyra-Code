package com.yukisoffd.lyracode.charts

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** JVM resources are packaged in the APK; the browser snapshot comes from the same generator. */
internal object CircuitSymbols {
    val definitions: JSONObject by lazy {
        val source = requireNotNull(CircuitSymbols::class.java.getResourceAsStream("/charts/circuit-symbols.json")) {
            "Bundled circuit symbol catalog is missing."
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        JSONObject(source).getJSONObject("symbols")
    }
    val types: Set<String> by lazy { definitions.keys().asSequence().toSet() }
    fun dynamic(type: String): Boolean = definitions.getJSONObject(type).optBoolean("dynamic")
    fun size(type: String, axis: Int): Int = definitions.getJSONObject(type).getJSONArray("size").getInt(axis)
    fun pins(type: String): Set<String> = definitions.getJSONObject(type).getJSONObject("pins").keys().asSequence().toSet()
}

internal fun circuitSymbolsToolResult(args: JSONObject): String {
    val selected = args.optJSONArray("types")
    require(!args.has("types") || selected != null) { "types must be an array of symbol names." }
    require(selected == null || selected.length() in 1..30) { "Request 1 to 30 symbol types." }
    val query = args.optString("query").trim().lowercase(Locale.ROOT)
    require(query.length <= 100) { "Symbol query exceeds 100 characters." }
    val category = args.optString("category").trim()
    val names = selected?.let { (0 until it.length()).map(it::getString) } ?: CircuitSymbols.types.toList()
    val result = JSONArray()
    names.forEach { name ->
        require(name in CircuitSymbols.types) { "Unknown circuit symbol: $name" }
        val definition = CircuitSymbols.definitions.getJSONObject(name)
        if ((category.isEmpty() || definition.getString("category") == category) &&
            (query.isEmpty() || "$name ${definition.getString("name")} ${definition.optString("name_zh")}".lowercase(Locale.ROOT).contains(query))) {
            val item = JSONObject().put("type", name).put("name", definition.getString("name"))
                .put("category", definition.getString("category"))
            if (definition.has("name_zh")) item.put("name_zh", definition.getString("name_zh"))
            if (selected == null) item.put("pin_names", JSONArray(CircuitSymbols.pins(name).toList()))
            else {
                item.put("pins", definition.getJSONObject("pins"))
                if (definition.has("bounds")) item.put("bounds", definition.getJSONArray("bounds"))
                if (definition.has("size")) item.put("default_size", definition.getJSONArray("size"))
                item.put("custom_pins", definition.optBoolean("dynamic"))
            }
            result.put(item)
        }
    }
    return JSONObject().put("symbol_count", CircuitSymbols.types.size).put("matches", result)
        .put("categories", JSONArray(CircuitSymbols.types.map { CircuitSymbols.definitions.getJSONObject(it).getString("category") }.distinct()))
        .put("note", "Use these type and pin names with create_chart(engine=circuit). Pin coordinates and bounds are relative to the component center before clockwise rotation. Request types for exact coordinates. ic/block/connector/converter require custom pins; pins support invert, clock and overline flags. The catalog includes IEC-style functional symbols and MIT Schemdraw-derived geometry; it is not the complete IEC 60617 database.")
        .toString()
}
