package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScape
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphTheme
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphWorld
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The land, scene by scene, against the browser prototype. The reference (src/test/resources/glyph-parity.json) was written by running the
 * prototype's own samplers over a grid of the keyboard in four settings (level, place along the road, appearance, turn of the type):
 * for each layer the ridge it lies on, how many blocks it answers "not here", and sums of what it answers. The same grid is sampled here.
 */
class GlyphPrototypeParityTest {
    private val reference = JSONArray(javaClass.getResourceAsStream("/glyph-parity.json")!!.bufferedReader().readText())
    /** The prototype's keyboard: 393 by 298, the cats' ridge at 150 (the 32 point legend makes the first gap's feet 152), and no floor under the skyline's ridge. */
    private val layout = GlyphLayout(393.0, 298.0, doubleArrayOf(109.0, 163.0, 217.0, 271.0), 32.0, 57.0, 34.0, 46.0, skylineFloor = 0.0)
    private val pools = GlyphPools()

    private fun close(what: String, expected: Double, actual: Double) {
        val tolerance = 0.002 * Math.abs(expected) + 0.01
        assertEquals("$what: expected $expected got $actual", expected, actual, tolerance)
    }

    @Test
    fun everyLayerOfEverySceneMatches() {
        for (c in 0 until reference.length()) {
            val config = reference.getJSONObject(c)
            val tag = "level ${config.getInt("level")} pos ${config.getInt("pos")} dark ${config.getBoolean("dark")}"
            val theme = GlyphTheme(dark = config.getBoolean("dark"), level = config.getInt("level"))
            val world = GlyphWorld(theme)
            world.pos = config.getInt("pos"); world.target = world.pos
            world.epoch = config.getInt("epoch"); world.tick = 100
            val scenes = config.getJSONArray("scenes")
            for (s in 0 until scenes.length()) {
                val expected = scenes.getJSONObject(s)
                val scene = expected.getInt("sc")
                val where = "$tag scene $scene"
                val scape = GlyphScape(world, pools)
                scape.wear(scene, layout)
                scape.prepare()
                assertEquals("$where groundY", expected.getDouble("groundY"), scape.groundTop, 1e-9)
                val (discX, discY) = scape.discPlace
                if (expected.isNull("discX")) assertEquals("$where disc", null, discX) else {
                    assertEquals("$where discX", expected.getDouble("discX"), discX!!, 1e-6)
                    // The prototype lets a disc hang over the top of the picture; here it is kept inside (see GlyphScape.wear).
                    if (expected.getDouble("discY") >= 0) assertEquals("$where discY", expected.getDouble("discY"), discY, 1e-3)
                }
                val layers = expected.getJSONArray("layers")
                assertEquals("$where layer count", layers.length(), scape.layers.size)
                for (k in 0 until layers.length()) {
                    val l = layers.getJSONObject(k)
                    val mine = scape.layers[k]
                    assertEquals("$where layer $k n", l.getInt("n"), mine.n)
                    assertEquals("$where layer $k cell", l.getInt("cell"), mine.cell)
                    assertEquals("$where layer $k offI", l.getInt("offI"), mine.offI)
                    var cnt = 0; var neg = 0; var sum = 0.0; var wsum = 0.0; var rsum = 0.0
                    var i = 0
                    while (i < mine.n) {
                        var py = 6
                        while (py < 298) {
                            val d = scape.sampleLayer(mine, i, py.toDouble()); cnt++
                            if (d < 0) neg++ else { sum += d; wsum += d * (((i * 7 + py) % 13) + 1) }
                            py += 12
                        }
                        i += 3
                    }
                    for (i2 in 0 until mine.n) rsum += mine.ridge[i2]
                    assertEquals("$where layer $k cnt", l.getInt("cnt"), cnt)
                    assertEquals("$where layer $k ridge", l.getDouble("rsum"), rsum, 1e-6 + 1e-9 * l.getDouble("rsum"))
                    assertEquals("$where layer $k neg", l.getInt("neg").toDouble(), neg.toDouble(), 3.0)
                    close("$where layer $k sum", l.getDouble("sum"), sum)
                    close("$where layer $k wsum", l.getDouble("wsum"), wsum)
                }
                val sky = expected.getJSONObject("sky")
                assertEquals("$where sky offI", sky.getInt("offI"), scape.skyOffset)
                var sum = 0.0; var wsum = 0.0
                var i = 0
                while (i < scape.skyLayer.n) {
                    var py = 6
                    while (py < 298) { val d = scape.sampleSky(i, py.toDouble()); sum += d; wsum += d * (((i * 7 + py) % 13) + 1); py += 12 }
                    i += 3
                }
                close("$where sky sum", sky.getDouble("sum"), sum)
                close("$where sky wsum", sky.getDouble("wsum"), wsum)
                if (expected.has("ground")) {
                    val g = expected.getJSONObject("ground")
                    var gs = 0.0; var gw = 0.0
                    var ux = -300
                    while (ux <= 300) {
                        for (row in 0 until 30) {
                            val d = scape.sampleGround(ux.toDouble(), row, row / 30.0, ux + 200.0)
                            gs += d; gw += d * (Math.floorMod(row * 7 + ux, 13) + 1)
                        }
                        ux += 25
                    }
                    close("$where ground sum", g.getDouble("sum"), gs)
                    close("$where ground wsum", g.getDouble("wsum"), gw)
                }
            }
        }
    }
}
