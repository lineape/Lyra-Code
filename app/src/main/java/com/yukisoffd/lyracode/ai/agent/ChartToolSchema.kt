package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.charts.MAX_CHART_SOURCE_LENGTH
import com.yukisoffd.lyracode.charts.CIRCUIT_COMPONENT_TYPES
import org.json.JSONArray
import org.json.JSONObject

internal fun chartToolDefinition(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
    .put("name", "create_chart")
    .put("description", "Create a chart for inline display in your answer with fullscreen and PNG/SVG/source saving. Use mermaid for flowcharts, sequence diagrams, Gantt charts, mind maps, architecture (flowchart subgraphs or architecture-beta), ER/class/state diagrams; use echarts for line, bar, pie, scatter, graph, tree and other data charts; use circuit for electrical schematics with component symbols, IC pins, wires and junctions. No image model or network is required. Embed the returned markdown verbatim in your final answer where the chart belongs. This prepares chart data; the client renders it.")
    .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
        .put("required", JSONArray(listOf("engine", "source")))
        .put("properties", JSONObject()
            .put("engine", JSONObject().put("type", "string").put("enum", JSONArray(listOf("mermaid", "echarts", "circuit"))))
            .put("title", JSONObject().put("type", "string").put("maxLength", 200).put("description", "Optional short chart title."))
            .put("source", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", MAX_CHART_SOURCE_LENGTH)
                .put("description", "For mermaid, plain diagram syntax with a diagram header, without Markdown fences, YAML frontmatter or init directives. For echarts, a strict JSON option object with series type(s) and data; no JavaScript, functions or external resources. Example: {\"xAxis\":{\"type\":\"category\",\"data\":[\"A\",\"B\"]},\"yAxis\":{\"type\":\"value\"},\"series\":[{\"type\":\"bar\",\"data\":[12,18]}]}. " + circuitSourceDescription())))))

private fun circuitSourceDescription(): String = """
    For circuit, use a strict JSON object: {width?:960,height?:640,components:[...],wires?:[...],nodes?:[...],junctions?:[[x,y]],labels?:[{x,y,text}]}. Coordinates are SVG pixels, x right, y down; width/height 160..4096. Components (1..200) are {id,type,x,y,rotation?:0|90|180|270,label?,value?}; id is a unique ASCII identifier, label/value are text. Supported types: ${CIRCUIT_COMPONENT_TYPES.joinToString(", ")}. Call get_circuit_symbols to find symbols and exact pin names/coordinates, especially multi-terminal transformers, relays, converters and logic blocks; do not guess pins. Rotation is clockwise around the component center. Basic two-terminal components have a=(-40,0), b=(40,0) before rotation; diode/LED a=anode,b=cathode; polarized capacitor/battery/voltage_source a=positive. ic, block, connector and converter require pins:[{id,side:left|right|top|bottom,offset?:0,label?,number?,invert?:false,clock?:false,overline?:false}], optional width/height (80..2400) and mark (text up to40). Defaults: ic120x160, block160x120, connector80x160, converter160x120. Up to512 pins; IDs are 1..40 ASCII letters/digits/underscore/plus/minus; number is text up to16 characters. Offsets run along the side from its center; endpoints extend20px outside the box. Each pin must have a distinct side/offset and stay12px from corners. invert draws an active-low bubble, clock draws a clock triangle, overline draws a bar above the pin label. mark annotates a functional block; converter mark is input/output notation such as "~ / =" or "= / ~". Use block/custom IC pins for MCUs, FPGA, memories, sensors, converters or subcircuits absent from the catalog. Wires (0..500) are {from,to,via?:[[x,y],...]}; endpoints are "componentId.pinId", a named node id, or [x,y]. Nodes are {id,x,y,label?}, with a connection dot. Explicit junctions mark connected wire crossings; unmarked crossings are unconnected. Routes are orthogonal: each diagonal segment bends horizontally first, then vertically. Use via waypoints to avoid components/labels and align branches at nodes or junctions. Coordinates must fit within width/height; rendered symbols and labels determine final bounds. Example: {"components":[{"id":"A1","type":"ammeter","x":120,"y":160},{"id":"M1","type":"dc_motor","x":320,"y":160}],"wires":[{"from":"A1.b","to":"M1.a"}]}. No simulation, raw SVG/HTML, code or external resources.
""".trimIndent()

internal fun circuitSymbolsToolDefinition(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
    .put("name", "get_circuit_symbols")
    .put("description", "Discover the offline circuit symbol library for create_chart(engine=circuit). With no arguments, list all types, categories and pin names. Filter by query (English or type name) or category; supply types to obtain exact relative pin coordinates and symbol bounds for 1 to 30 types. Covers IEC-style passive components, motors, measuring instruments, transformers, switches/relays, semiconductors, converters and digital logic. Custom ic/block/connector/converter symbols support up to512 named pins with active-low, clock and overline markers.")
    .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
        .put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("maxLength", 100))
            .put("category", JSONObject().put("type", "string"))
            .put("types", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 30)
                .put("items", JSONObject().put("type", "string"))))))
