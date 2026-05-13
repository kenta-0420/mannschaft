package f088

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * F08.8 Phase 6 負荷試験 — シナリオ 2: タイムライン集計（30 万件シード環境）
 *
 * 対象エンドポイント:
 *   GET /api/v1/{scope}/{scopeId}/repair-plan/timeline
 *
 * 試験内容:
 *   - 30 万件の repair_plan_items が投入された状態で並列 20 ユーザーが集計クエリを実行
 *   - 60 秒かけて 1〜20 ユーザーに ramp アップ後、最大 20 並列で 3 分間維持
 *   - yearFrom/yearTo を変化させることでクエリキャッシュに依存しない試験とする
 *
 * 合否基準（設計書 §11）:
 *   - P95 < 500ms（20 年分タイムライン集計、30 万トランザクション想定）
 *   - エラー率 < 1%
 *
 * 前提:
 *   - seed_30m_repair_items.sql が適用済みであること（planned_year: 2010〜2049 の 30 万件）
 *
 * 実行方法:
 *   $GATLING_HOME/bin/gatling.sh \
 *     --simulations-folder docs/load-test/f088/simulations \
 *     --simulation f088.RepairPlanTimelineLoadTest \
 *     -Dtoken=<JWT_TOKEN> \
 *     -DbaseUrl=http://localhost:8080
 */
class RepairPlanTimelineLoadTest extends Simulation {

  /** ベース URL。システムプロパティ baseUrl で上書き可能。 */
  val baseUrl: String = System.getProperty("baseUrl", "http://localhost:8080")

  /** 認証トークン（Bearer）。システムプロパティ token で指定すること。 */
  val token: String = System.getProperty("token", "changeme")

  /** スコープ設定。organization_id=1 の TEAM スコープ id=1 を対象とする。 */
  val scope: String  = System.getProperty("scope", "teams")
  val scopeId: String = System.getProperty("scopeId", "1")
  val orgId: String   = System.getProperty("orgId", "1")

  val httpProtocol = http
    .baseUrl(baseUrl)
    .header("Accept", "application/json")
    .header("X-Organization-Id", orgId)
    .header("Authorization", s"Bearer $token")

  /**
   * 年度範囲フィーダー。
   * yearFrom/yearTo を変化させてキャッシュに依存しない集計クエリを実行する。
   * シードデータの範囲（2010〜2049）を 20 年ウィンドウで分割して使用する。
   */
  val yearRangeFeeder = Iterator.continually(
    Seq(
      Map("yearFrom" -> 2010, "yearTo" -> 2030),
      Map("yearFrom" -> 2015, "yearTo" -> 2035),
      Map("yearFrom" -> 2020, "yearTo" -> 2040),
      Map("yearFrom" -> 2025, "yearTo" -> 2045),
      Map("yearFrom" -> 2029, "yearTo" -> 2049)
    )
  ).flatten

  val timelineScenario = scenario("タイムライン集計 30万件シード")
    .feed(yearRangeFeeder)
    .exec(
      http("GET /timeline")
        .get(s"/api/v1/$scope/$scopeId/repair-plan/timeline")
        .queryParam("yearFrom", "#{yearFrom}")
        .queryParam("yearTo", "#{yearTo}")
        .check(status.is(200))
    )

  setUp(
    timelineScenario.inject(
      // 60 秒かけて 1〜20 ユーザーに ramp アップ
      rampUsers(20).during(60.seconds),
      // 3 分間 20 並列を維持（constantConcurrentUsers は Gatling 3.9+ で利用可能）
      constantConcurrentUsers(20).during(3.minutes)
    ).protocols(httpProtocol)
  ).assertions(
    // P95 が 500ms 未満であること（設計書 §11「20年タイムライン集計 500ms 以内」）
    global.responseTime.percentile(95).lt(500),
    // エラー率が 1% 未満であること
    global.failedRequests.percent.lt(1)
  )
}
