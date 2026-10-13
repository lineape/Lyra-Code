package com.yukisoffd.lyracode.charts

import org.json.JSONArray
import org.json.JSONObject

internal val CIRCUIT_COMPONENT_TYPES: Set<String> get() = CircuitSymbols.types

/** Keep the data contract in sync with charts/circuit.js. Coordinates are SVG pixels. */
internal fun validateCircuitDocument(document: JSONObject) {
    fun fields(value: JSONObject, allowed: Set<String>) {
        value.keys().forEach { require(it in allowed) { "Unsupported circuit property: $it" } }
    }
    fun number(value: Any?, name: String, min: Double, max: Double): Double {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() in min..max) {
            "$name must be a finite number between $min and $max."
        }
        return value.toDouble()
    }
    fun text(value: JSONObject, key: String, max: Int = 100) {
        if (value.has(key)) require(value.get(key) is String && value.getString(key).length <= max) {
            "$key must be text of at most $max characters."
        }
    }
    fun string(value: JSONObject, key: String): String {
        require(value.opt(key) is String) { "$key must be text." }
        return value.getString(key)
    }
    fun array(value: JSONObject, key: String, max: Int, required: Boolean = false): JSONArray {
        if (!value.has(key)) {
            require(!required) { "Circuit must contain $key." }
            return JSONArray()
        }
        val result = value.getJSONArray(key)
        require(result.length() <= max && (!required || result.length() > 0)) { "Provide ${if (required) 1 else 0} to $max $key." }
        return result
    }
    fields(document, setOf("width", "height", "components", "wires", "nodes", "junctions", "labels"))
    val width = number(document.opt("width") ?: 960, "width", 160.0, 4096.0)
    val height = number(document.opt("height") ?: 640, "height", 160.0, 4096.0)
    fun point(value: Any?) {
        require(value is JSONArray && value.length() == 2) { "A point must be [x,y]." }
        number(value.get(0), "x", 0.0, width)
        number(value.get(1), "y", 0.0, height)
    }
    fun position(value: JSONObject) {
        number(value.opt("x"), "x", 0.0, width)
        number(value.opt("y"), "y", 0.0, height)
    }
    val ids = mutableSetOf<String>()
    val endpoints = mutableSetOf<String>()
    fun id(value: JSONObject): String {
        val id = string(value, "id")
        require(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}").matches(id) && ids.add(id)) { "Invalid or duplicate circuit id: $id" }
        return id
    }
    val components = array(document, "components", 200, required = true)
    for (index in 0 until components.length()) {
        val component = components.getJSONObject(index)
        fields(component, setOf("id", "type", "x", "y", "rotation", "label", "value", "width", "height", "pins", "mark"))
        val componentId = id(component)
        val type = string(component, "type")
        require(type in CIRCUIT_COMPONENT_TYPES) { "Unsupported circuit component: $type" }
        position(component)
        val rotation = number(component.opt("rotation") ?: 0, "rotation", 0.0, 270.0)
        require(rotation in listOf(0.0, 90.0, 180.0, 270.0)) { "rotation must be 0, 90, 180 or 270." }
        text(component, "label"); text(component, "value")
        val pinIds = if (CircuitSymbols.dynamic(type)) {
                val w = number(component.opt("width") ?: CircuitSymbols.size(type, 0), "Module width", 80.0, 2400.0)
                val h = number(component.opt("height") ?: CircuitSymbols.size(type, 1), "Module height", 80.0, 2400.0)
                text(component, "mark", 40)
                val pins = array(component, "pins", 512, required = true)
                val names = mutableSetOf<String>()
                val positions = mutableSetOf<Pair<String, Double>>()
                for (pinIndex in 0 until pins.length()) {
                    val pin = pins.getJSONObject(pinIndex)
                    fields(pin, setOf("id", "side", "offset", "label", "number", "invert", "clock", "overline"))
                    val pinId = string(pin, "id")
                    require(Regex("[A-Za-z0-9_+-]{1,40}").matches(pinId) && names.add(pinId)) { "Invalid or duplicate module pin: $pinId" }
                    val side = string(pin, "side")
                    require(side in setOf("left", "right", "top", "bottom")) { "Invalid IC pin side: $side" }
                    val extent = (if (side == "left" || side == "right") h else w) / 2 - 12
                    val offset = number(pin.opt("offset") ?: 0, "pin offset", -extent, extent)
                    require(positions.add(side to offset)) { "IC pins must have distinct positions." }
                    text(pin, "label", 40); text(pin, "number", 16)
                    for (flag in listOf("invert", "clock", "overline")) if (pin.has(flag)) {
                        require(pin.get(flag) is Boolean) { "$flag must be boolean." }
                    }
                }
                names
        } else {
            require(listOf("pins", "width", "height", "mark").none(component::has)) { "Only ic/block/connector/converter accept custom pins, dimensions or mark." }
            CircuitSymbols.pins(type)
        }
        endpoints.addAll(pinIds.map { "$componentId.$it" })
    }
    val nodes = array(document, "nodes", 200)
    for (index in 0 until nodes.length()) {
        val node = nodes.getJSONObject(index)
        fields(node, setOf("id", "x", "y", "label"))
        endpoints.add(id(node)); position(node); text(node, "label")
    }
    fun endpoint(value: Any?) {
        if (value is String) require(value in endpoints) { "Unknown circuit endpoint: $value" } else point(value)
    }
    val wires = array(document, "wires", 500)
    for (index in 0 until wires.length()) {
        val wire = wires.getJSONObject(index)
        fields(wire, setOf("from", "to", "via"))
        endpoint(wire.opt("from")); endpoint(wire.opt("to"))
        val via = array(wire, "via", 32)
        for (p in 0 until via.length()) point(via.get(p))
    }
    val junctions = array(document, "junctions", 500)
    for (index in 0 until junctions.length()) point(junctions.get(index))
    val labels = array(document, "labels", 100)
    for (index in 0 until labels.length()) {
        val label = labels.getJSONObject(index)
        fields(label, setOf("x", "y", "text")); position(label)
        require(label.has("text")) { "Circuit label requires text." }; text(label, "text", 200)
    }
}
