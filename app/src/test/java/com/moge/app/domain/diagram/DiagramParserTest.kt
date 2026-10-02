package com.moge.app.domain.diagram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 框图协议校验：坏数据必须被挡在渲染之前，且**只丢坏的那一张**。
 *
 * 重点覆盖用户明确要求的三类风控：重复 id、未知端点、超长文字；
 * 以及「单张坏图不影响其余图与正文」。
 */
class DiagramParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun diagram(nodes: String, edges: String = "[]", title: String = "T") =
        JsonObject(
            mapOf(
                "title" to JsonPrimitive(title),
                "nodes" to json.parseToJsonElement(nodes),
                "edges" to json.parseToJsonElement(edges),
            ),
        )

    private fun parse(vararg diagrams: JsonObject): Pair<List<DiagramSpec>, List<String>> {
        val warnings = mutableListOf<String>()
        val list = DiagramParser.parseArray(JsonArray(diagrams.toList()), warnings)
        return list to warnings
    }

    /** 图 4 的标准相干解调：乘法器 + 低通 + 下方载波支路。 */
    @Test fun coherentDemodulatorParsesWithCarrierBelow() {
        val (list, warnings) = parse(
            diagram(
                nodes = """[
                    {"id":"in","label":"接收信号","shape":"io"},
                    {"id":"mix","label":"乘法器","shape":"mixer","glyph":"×"},
                    {"id":"lpf","label":"低通滤波器","shape":"block"},
                    {"id":"out","label":"输出","shape":"io"},
                    {"id":"car","label":"本地载波","shape":"io","row":1}
                ]""",
                edges = """[
                    {"from":"in","to":"mix"},
                    {"from":"mix","to":"lpf"},
                    {"from":"lpf","to":"out"},
                    {"from":"car","to":"mix","toPort":"bottom"}
                ]""",
            ),
        )
        assertEquals(1, list.size)
        assertTrue("不应有警告: $warnings", warnings.isEmpty())
        val spec = list.single()
        assertEquals(5, spec.nodes.size)
        assertEquals(4, spec.edges.size)
        assertEquals(DiagramNodeShape.MIXER, spec.nodes.first { it.id == "mix" }.shape)
        assertEquals(DiagramPort.BOTTOM, spec.edges.last().toPort)
        assertEquals(1, spec.nodes.first { it.id == "car" }.row)
    }

    /** AM 包络检波：带通 → 包络检波器 → 隔直 → 输出。 */
    @Test fun amEnvelopeDetectorChainParses() {
        val (list) = parse(
            diagram(
                nodes = """[
                    {"id":"bp","label":"带通滤波","shape":"block","subLabel":"可选"},
                    {"id":"env","label":"包络检波器","shape":"block","subLabel":"二极管＋RC"},
                    {"id":"dc","label":"隔直电容","shape":"block"},
                    {"id":"out","label":"输出","shape":"io"}
                ]""",
                edges = """[
                    {"from":"bp","to":"env"},{"from":"env","to":"dc"},{"from":"dc","to":"out"}
                ]""",
            ),
        )
        assertEquals(1, list.size)
        assertEquals("二极管＋RC", list.single().nodes.first { it.id == "env" }.subLabel)
    }

    @Test fun literalEscapedNewlinesInLabelsBecomeLineBreaksWithoutChangingLatex() {
        val (list, warnings) = parse(
            diagram(
                nodes = """[
                    {"id":"block","label":"12路SSB调制器\\n(载波 $\\nu$)","shape":"block",
                     "subLabel":"参数\\r\\n$\\neq 0$"}
                ]""",
            ),
        )
        assertTrue("不应有警告: $warnings", warnings.isEmpty())
        val node = list.single().nodes.single()
        assertEquals("12路SSB调制器\n(载波 $\\nu$)", node.label)
        assertEquals("参数\n$\\neq 0$", node.subLabel)
    }

    /** 重复 id：无法唯一寻址，整张拒收。 */
    @Test fun duplicateNodeIdRejectsThatDiagramOnly() {
        val (list, warnings) = parse(
            diagram(nodes = """[{"id":"a","label":"A"},{"id":"a","label":"B"}]"""),
            diagram(nodes = """[{"id":"x","label":"X"}]"""),
        )
        assertEquals("坏图应被拒收", 1, list.size)
        assertEquals("另一张图必须保留", "x", list.single().nodes.single().id)
        assertTrue("应给出警告: $warnings", warnings.any { it.contains("框图") })
    }

    /** 连线端点不存在：画出来是断线，整张拒收。 */
    @Test fun unknownEdgeEndpointRejectsDiagram() {
        val (list, warnings) = parse(
            diagram(
                nodes = """[{"id":"a","label":"A"}]""",
                edges = """[{"from":"a","to":"ghost"}]""",
            ),
        )
        assertTrue("未知端点应拒收", list.isEmpty())
        assertTrue("应说明原因: $warnings", warnings.any { it.contains("不存在") })
    }

    @Test fun unknownEdgeSourceRejectsDiagram() {
        val (list) = parse(
            diagram(
                nodes = """[{"id":"a","label":"A"}]""",
                edges = """[{"from":"ghost","to":"a"}]""",
            ),
        )
        assertTrue(list.isEmpty())
    }

    /** 超长文字拒收，避免截断公式。 */
    @Test fun overlongLabelIsRejectedWithoutChangingMath() {
        val long = "低通滤波器".repeat(30)
        val (list) = parse(diagram(nodes = """[{"id":"a","label":"$long"}]"""))
        assertTrue(list.isEmpty())
    }

    @Test fun overlongTitleIsRejected() {
        val long = "标题".repeat(40)
        val (list) = parse(diagram(nodes = """[{"id":"a","label":"A"}]""", title = long))
        assertTrue(list.isEmpty())
    }

    /** 规模上限：节点过多整张拒收，而不是画成一团。 */
    @Test fun tooManyNodesRejectsDiagram() {
        val many = (1..(DiagramLimits.MAX_NODES + 1)).joinToString(",") {
            """{"id":"n$it","label":"N$it"}"""
        }
        val (list, warnings) = parse(diagram(nodes = "[$many]"))
        assertTrue("超规模应拒收", list.isEmpty())
        assertTrue("应给出规模提示: $warnings", warnings.isNotEmpty())
    }

    /** 缺字段 / 类型不对：拒收但不抛异常到调用方。 */
    @Test fun malformedShapesAreRejectedWithoutThrowing() {
        val warnings = mutableListOf<String>()
        val out = DiagramParser.parseArray(
            JsonArray(
                listOf(
                    JsonObject(mapOf("title" to JsonPrimitive("x"))), // 缺 nodes
                    JsonPrimitive("not an object"),
                    diagram(nodes = """[{"id":"a"}]"""), // 缺 label
                    diagram(nodes = """[{"label":"no id"}]"""), // 缺 id
                ),
            ),
            warnings,
        )
        assertTrue(out.isEmpty())
        assertEquals(4, warnings.size)
    }

    /** 分支与合流：同一 from 多条 = 分支，多条指向同一 to = 合流。 */
    @Test fun branchAndMergeTopologyIsPreserved() {
        val (list) = parse(
            diagram(
                nodes = """[
                    {"id":"src","label":"源","shape":"io"},
                    {"id":"b1","label":"支路一","shape":"block"},
                    {"id":"b2","label":"支路二","shape":"block"},
                    {"id":"sum","label":"合流","shape":"sum","glyph":"+"}
                ]""",
                edges = """[
                    {"from":"src","to":"b1"},{"from":"src","to":"b2"},
                    {"from":"b1","to":"sum"},{"from":"b2","to":"sum"}
                ]""",
            ),
        )
        val spec = list.single()
        assertEquals(2, spec.edges.count { it.from == "src" })
        assertEquals(2, spec.edges.count { it.to == "sum" })
        assertEquals(DiagramNodeShape.SUM, spec.nodes.first { it.id == "sum" }.shape)
    }

    @Test fun commonDiagramShapeAndPortAliasesAreAccepted() {
        val element = diagram(
            nodes = """
                [{"id":"in","label":"输入","shape":"input"},
                 {"id":"mul","label":"乘法器","shape":"multiplier"},
                 {"id":"sum","label":"求和","shape":"summation"},
                 {"id":"out","label":"输出","shape":"output"}]
            """.trimIndent(),
            edges = """
                [{"from":"in","to":"mul","toPort":"west"},
                 {"from":"mul","to":"sum","toPort":"north"},
                 {"from":"sum","to":"out","toPort":"east"}]
            """.trimIndent(),
        )
        val spec = DiagramParser.parseOne(element)
        assertEquals(DiagramNodeShape.IO, spec.nodes.first { it.id == "in" }.shape)
        assertEquals(DiagramNodeShape.MIXER, spec.nodes.first { it.id == "mul" }.shape)
        assertEquals(DiagramNodeShape.SUM, spec.nodes.first { it.id == "sum" }.shape)
        assertEquals(DiagramPort.LEFT, spec.edges.first().toPort)
        assertEquals(DiagramPort.TOP, spec.edges[1].toPort)
        assertEquals(DiagramPort.RIGHT, spec.edges[2].toPort)
    }

    /** 未知端口拒收该图。 */
    @Test fun summingInputPolarityIsPreserved() {
        val (list) = parse(
            diagram(
                nodes = """[
                    {"id":"a","label":"A","shape":"io"},
                    {"id":"b","label":"B","shape":"io"},
                    {"id":"sum","label":"Σ","shape":"sum"}
                ]""",
                edges = """[
                    {"from":"a","to":"sum","toPort":"top"},
                    {"from":"b","to":"sum","toPort":"bottom","polarity":"-"}
                ]""",
            ),
        )
        assertEquals("−", list.single().edges.last().polarity)
    }

    @Test fun unknownPortIsRejected() {
        val (list) = parse(
            diagram(
                nodes = """[{"id":"a","label":"A"},{"id":"b","label":"B"}]""",
                edges = """[{"from":"a","to":"b","fromPort":"middle"}]""",
            ),
        )
        assertTrue(list.isEmpty())
    }

    /** 单张坏图不影响其余图：这正是「坏图不能阻止文字回复」的结构保证。 */
    @Test fun oneBrokenDiagramDoesNotAffectOthers() {
        val (list) = parse(
            diagram(nodes = """[{"id":"a","label":"A"}]""", edges = """[{"from":"a","to":"ghost"}]"""),
            diagram(nodes = """[{"id":"z","label":"Z"}]"""),
        )
        assertEquals(1, list.size)
        assertNotNull(list.single().nodes.firstOrNull { it.id == "z" })
    }

    /** 空数组 / null：安静返回空，不产生警告噪音。 */
    @Test fun emptyOrMissingArrayYieldsNothing() {
        val warnings = mutableListOf<String>()
        assertTrue(DiagramParser.parseArray(null, warnings).isEmpty())
        assertTrue(DiagramParser.parseArray(JsonArray(emptyList()), warnings).isEmpty())
        assertTrue(warnings.isEmpty())
        assertNull(null)
    }

    @Test fun textbookProfileAndSemanticRoleAreParsed() {
        val (list, warnings) = parse(
            JsonObject(
                mapOf(
                    "title" to JsonPrimitive("SSB"),
                    "profile" to JsonPrimitive("textbook_dual_branch"),
                    "nodes" to json.parseToJsonElement(
                        """[{"id":"mix","label":"乘法器","shape":"mixer","role":"upper_mixer"}]""",
                    ),
                    "edges" to JsonArray(emptyList()),
                ),
            ),
        )
        assertTrue("不应有警告: $warnings", warnings.isEmpty())
        assertEquals(DiagramLayoutProfile.TEXTBOOK_DUAL_BRANCH, list.single().profile)
        assertEquals("upper_mixer", list.single().nodes.single().role)
    }

    @Test fun unknownProfileKeepsLegacyGenericLayout() {
        val (list, warnings) = parse(
            JsonObject(
                mapOf(
                    "title" to JsonPrimitive("legacy"),
                    "profile" to JsonPrimitive("future_profile"),
                    "nodes" to json.parseToJsonElement("""[{"id":"a","label":"A"}]"""),
                    "edges" to JsonArray(emptyList()),
                ),
            ),
        )
        assertTrue("不应有警告: $warnings", warnings.isEmpty())
        assertEquals(DiagramLayoutProfile.GENERIC, list.single().profile)
    }
}
