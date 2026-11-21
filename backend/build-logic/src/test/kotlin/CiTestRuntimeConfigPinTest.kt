import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CMP-261111-0508 追撃① 試練 AC-4: 構成キー削減の前後で CI のテスト実行構成値を変えないことの固定。
 *
 * 構成キーの削減（第一陣 A・第二陣 C2）は「コンテキストの種類を減らす」ことで再ロードを減らす施策であり、
 * キャッシュ上限・fork・並列数・shard 数・時間上限を同時に動かすと効果が測れなくなる（AC-6 の比較条件）。
 * 値を変える場合はこのテストと規約 31 を同じ PR で更新し、殿の判断を経ること。
 */
class CiTestRuntimeConfigPinTest {

    private val script = File("src/main/kotlin/mannschaft.backend-conventions.gradle.kts").readText()
    private val workflows = File("../../.github/workflows")

    private fun workflow(name: String): String = File(workflows, name).readText()

    @Test
    fun `AC-4 spring test context cache の既定 maxSize は 10`() {
        assertTrue(
            script.contains("contextCacheMaxSizeOverride ?: \"10\""),
            "build-logic の spring.test.context.cache.maxSize 既定値は 10 のまま",
        )
    }

    @Test
    fun `AC-4 forkEvery は 180 で maxParallelForks は 2`() {
        assertTrue(script.contains("?.toLong() ?: 180L)"), "forkEvery の既定値は 180 のまま")
        assertTrue(script.contains("?.toInt() ?: 2"), "maxParallelForks の既定値は 2 のまま")
    }

    @Test
    fun `AC-4 3 workflow の shard 数は 8 で CI が渡す context cache 上限は 5 のまま`() {
        for (name in listOf("backend-ci.yml", "backend-nightly-full.yml", "backend-deploy.yml")) {
            val text = workflow(name)
            assertTrue(text.contains("  SHARD_TOTAL: \"8\""), "$name の SHARD_TOTAL は 8")
            assertTrue(text.contains("shard: [0, 1, 2, 3, 4, 5, 6, 7]"), "$name の matrix は 0..7")
            assertEquals(
                1, Regex("""-Pspring\.test\.context\.cache\.maxSize=5\b""").findAll(text).count(),
                "$name のテスト step が渡す context cache 上限は 5 のまま（1 箇所）",
            )
        }
    }

    @Test
    fun `AC-4 workflow ごとのテスト timeout を維持する`() {
        val conditional = mapOf(
            "backend-ci.yml" to listOf(
                "timeout-minutes: \${{ needs.detect.outputs.migrations_changed == 'true' && 120 || 110 }}",
                "timeout-minutes: \${{ needs.detect.outputs.migrations_changed == 'true' && 110 || 95 }}",
            ),
            "backend-deploy.yml" to listOf(
                "timeout-minutes: \${{ needs.detect.outputs.migrations_changed == 'true' && 120 || 100 }}",
                "timeout-minutes: \${{ needs.detect.outputs.migrations_changed == 'true' && 105 || 85 }}",
            ),
        )
        for ((name, expected) in conditional) {
            val text = workflow(name)
            expected.forEach { assertTrue(text.contains(it), "$name に $it が残っていること") }
        }
        val nightly = workflow("backend-nightly-full.yml")
        assertTrue(nightly.contains("    timeout-minutes: 120"), "nightly の job 上限は 120")
        assertTrue(nightly.contains("        timeout-minutes: 105"), "nightly の step 上限は 105")
    }
}
