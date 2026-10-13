package com.yukisoffd.lyracode.charts

import com.yukisoffd.lyracode.ai.chartToolDefinition
import com.yukisoffd.lyracode.ai.circuitSymbolsToolDefinition
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChartDocumentTest {
    @Test fun toolResultRoundTripsThroughTheMarkdownDocument() {
        val args = JSONObject().put("engine", "mermaid").put("title", "中文流程图")
            .put("source", "flowchart LR\n A[开始] --> B[结束]")
        val result = JSONObject(chartToolResult(args))
        assertEquals("prepared", result.getString("status"))
        val markdown = result.getString("markdown")
        val document = ChartDocument.fromFence("chart", markdown.substringAfter('\n').substringBeforeLast('\n'))
        assertEquals(args.getString("title"), document.title)
        assertEquals(args.getString("source"), document.source)
    }

    @Test fun maliciousLabelsCannotEscapeTheHtmlPayload() {
        val source = JSONObject().put("series", org.json.JSONArray().put(JSONObject().put("type", "pie")
            .put("data", org.json.JSONArray().put(JSONObject().put("name", "</script><script>window.pwned=true</script>\u2028&")
                .put("value", 3)))))
        val doc = ChartDocument.create("echarts", source.toString())
        val json = chartPayloadJson(doc, false)
        assertFalse(json.contains("</script>"))
        assertFalse(json.contains('<'))
        assertFalse(json.contains('\u2028'))
        assertEquals(doc.source, JSONObject(json).getString("source"))
        assertEquals(3, Regex("</script>").findAll(chartHtml(doc, false)).count())
    }

    @Test fun labelsContainingBackticksCannotCloseTheChartFence() {
        val document = ChartDocument.create("mermaid", "flowchart LR\n A[\"````\"] --> B[OK]")
        val markdown = document.markdown()
        assertTrue(markdown.startsWith("`````chart\n"))
        assertEquals(document, ChartDocument.fromFence("chart", markdown.substringAfter('\n').substringBeforeLast('\n')))
    }

    @Test fun invalidSourcesAndConfigurationOverridesAreRejected() {
        listOf(
            { ChartDocument.create("html", "<script>bad()</script>") },
            { ChartDocument.create("mermaid", "flowchart LR\n" + "x".repeat(MAX_CHART_SOURCE_LENGTH)) },
            { ChartDocument.create("mermaid", "%%{init: {securityLevel: 'loose'}}%%\nflowchart LR\nA-->B") },
            { ChartDocument.create("mermaid", "---\nconfig:\n securityLevel: loose\n---\nflowchart LR") },
            { ChartDocument.create("echarts", "{\"series\":[{\"type\":\"custom\"}]}") },
            { ChartDocument.create("echarts", "{\"series\":[{\"type\":\"bar\"}],\"__proto__\":{}}") },
            { ChartDocument.create("echarts", "{\"series\":[{\"type\":\"line\",\"data\":()=>evil()}]}") },
        ).forEach { invalid -> assertTrue("Invalid chart accepted", runCatching { invalid() }.isFailure) }
    }

    @Test fun schemaRequiresDataAndOffersAllRenderingEngines() {
        val function = chartToolDefinition().getJSONObject("function")
        assertEquals("create_chart", function.getString("name"))
        val parameters = function.getJSONObject("parameters")
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertEquals(listOf("engine", "source"), parameters.getJSONArray("required").let { (0 until it.length()).map(it::getString) })
        val engines = parameters.getJSONObject("properties").getJSONObject("engine").getJSONArray("enum")
        assertEquals(listOf("mermaid", "echarts", "circuit"), (0 until engines.length()).map(engines::getString))
        val sourceHelp = parameters.getJSONObject("properties").getJSONObject("source").getString("description")
        assertTrue(sourceHelp.contains("componentId.pinId"))
        CIRCUIT_COMPONENT_TYPES.forEach { assertTrue("Undocumented component: $it", sourceHelp.contains(it)) }
    }

    @Test fun supportedDiagramHeadersAndSeriesTypesAcceptChineseData() {
        listOf("flowchart LR", "sequenceDiagram", "gantt", "mindmap", "architecture-beta", "erDiagram", "classDiagram")
            .forEach { assertEquals("mermaid", ChartDocument.create("Mermaid", it).engine) }
        listOf("line", "bar", "pie", "graph", "tree").forEach { type ->
            assertEquals("echarts", ChartDocument.create("echarts", "{\"series\":[{\"type\":\"$type\",\"data\":[1,2]}]}").engine)
        }
    }

    @Test fun circuitReferenceRoundTripsThroughToolHistoryAndDirectFences() {
        val source = File("src/androidTest/assets/charts/ne555-cd4017.json").readText()
        val args = JSONObject().put("engine", "circuit").put("source", source).put("title", "NE555 / CD4017 电路图")
        val markdown = JSONObject(chartToolResult(args)).getString("markdown")
        val document = ChartDocument.fromFence("chart", markdown.substringAfter('\n').substringBeforeLast('\n'))
        assertEquals("circuit", document.engine)
        assertEquals(args.getString("title"), document.title)
        assertEquals(document.copy(title = ""), ChartDocument.fromFence("CiRcUiT", source))
        assertTrue(isChartLanguage("CIRCUIT"))
        val html = chartHtml(document, false)
        assertTrue(html.contains("src=\"circuit.js\""))
        assertFalse(html.contains("circuit.min.js"))
    }

    @Test fun allCircuitSymbolsAndRotationsAreAccepted() {
        CIRCUIT_COMPONENT_TYPES.forEach { type ->
            listOf(0, 90, 180, 270).forEach { rotation ->
                val component = JSONObject().put("id", "X1").put("type", type).put("x", 200).put("y", 200).put("rotation", rotation)
                if (CircuitSymbols.dynamic(type)) component.put("pins", org.json.JSONArray().put(JSONObject().put("id", "1").put("side", "left")))
                assertEquals("circuit", ChartDocument.create("circuit", JSONObject().put("components", org.json.JSONArray().put(component)).toString()).engine)
            }
        }
    }

    @Test fun invalidCircuitGeometryReferencesAndExecutableContentAreRejected() {
        val valid = """{"components":[{"id":"R1","type":"resistor","x":120,"y":160}],"wires":[{"from":"R1.a","to":[200,160]}]}"""
        listOf(
            valid.replace("resistor", "custom"),
            valid.replace("R1.a", "R1.unknown"),
            valid.replace("120", "\"120\""),
            valid.replace("120", "-1"),
            valid.replace("120", "5000"),
            valid.replace("120", "null"),
            valid.replace("\"type\":", "\"rotation\":45,\"type\":"),
            valid.replace("\"type\":", "\"onclick\":\"evil()\",\"type\":"),
            valid.replace("\"components\":", "\"__proto__\":{},\"components\":"),
            """{"components":[]}""",
            """{"components":[{"id":"U1","type":"ic","x":200,"y":200,"pins":[{"id":"1","side":"left","offset":1000}]}]}""",
            """{"components":[{"id":"U1","type":"ic","x":200,"y":200,"pins":[{"id":"1","side":"left"},{"id":"2","side":"left"}]}]}""",
            """{"components":[{"id":"R1","type":"resistor","x":120,"y":160}],"nodes":[{"id":"R1","x":100,"y":100}]}""",
            """{"components":[{"id":"R1","type":"resistor","x":120,"y":160}],"junctions":[[100]]}""",
        ).forEach { source -> assertTrue("Invalid circuit accepted: $source", runCatching { ChartDocument.create("circuit", source) }.isFailure) }
    }

    @Test fun circuitLabelsStayDataInHtmlAndMarkdown() {
        val label = "</script><script>evil()</script>````\u2028&中文"
        val component = JSONObject().put("id", "R1").put("type", "resistor").put("x", 120).put("y", 160).put("label", label)
        val document = ChartDocument.create("circuit", JSONObject().put("components", org.json.JSONArray().put(component)).toString())
        assertFalse(chartPayloadJson(document, false).contains('<'))
        assertTrue(document.markdown().startsWith("`````chart"))
        assertEquals(document.source, JSONObject(chartPayloadJson(document, true)).getString("source"))
    }

    @Test fun symbolDiscoveryReportsExactTransformerAndInstrumentPinsWithoutSvgCode() {
        assertEquals("get_circuit_symbols", circuitSymbolsToolDefinition().getJSONObject("function").getString("name"))
        val all = JSONObject(circuitSymbolsToolResult(JSONObject()))
        assertTrue(all.getInt("symbol_count") >= 120)
        assertEquals(CIRCUIT_COMPONENT_TYPES.size, all.getJSONArray("matches").length())
        val selected = JSONObject(circuitSymbolsToolResult(JSONObject().put("types", org.json.JSONArray(listOf("transformer", "ammeter", "three_phase_motor")))))
            .getJSONArray("matches")
        assertEquals(setOf("p1", "p2", "s1", "s2"), selected.getJSONObject(0).getJSONObject("pins").keys().asSequence().toSet())
        assertEquals(-40, selected.getJSONObject(1).getJSONObject("pins").getJSONArray("a").getInt(0))
        assertTrue(selected.getJSONObject(2).getJSONObject("pins").has("pe"))
        assertFalse(selected.toString().contains("body"))
        val chinese = JSONObject(circuitSymbolsToolResult(JSONObject().put("query", "变压器"))).getJSONArray("matches")
        assertTrue(chinese.length() >= 4)
        assertTrue(runCatching { circuitSymbolsToolResult(JSONObject().put("types", org.json.JSONArray(listOf("not_real")))) }.isFailure)
    }

    @Test fun fullCatalogGalleriesAndLargeCustomModulesPassTheSameDataContract() {
        listOf("power", "logic").forEach { family ->
            ChartDocument.create("circuit", File("src/androidTest/assets/charts/circuit-library-$family.json").readText())
        }
        val pins = org.json.JSONArray()
        repeat(512) { index -> pins.put(JSONObject().put("id", "P$index")
            .put("side", listOf("left", "right", "top", "bottom")[index / 128]).put("offset", -508 + index % 128 * 8)
            .put("clock", index == 0).put("invert", index == 1).put("overline", index == 1)) }
        val component = JSONObject().put("id", "U1").put("type", "ic").put("x", 1200).put("y", 1200)
            .put("width", 1200).put("height", 1200).put("pins", pins)
        val doc = JSONObject().put("width", 2400).put("height", 2400).put("components", org.json.JSONArray().put(component))
        ChartDocument.create("circuit", doc.toString())
        pins.getJSONObject(0).put("clock", "true")
        assertTrue(runCatching { ChartDocument.create("circuit", doc.toString()) }.isFailure)
    }
}
