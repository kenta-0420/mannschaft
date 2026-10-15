package f088

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * F08.8 Phase 6 負荷試験 — シナリオ 1: simulate API 100 req/s × 5 min
 *
 * 対象エンドポイント:
 *   POST /api/v1/{scopeType}/{scopeId}/repair-plan/scenarios/simulate
 *
 * 試験内容:
 *   - 1〜100 req/s へ 60 秒かけて ramp アップ後、100 req/s で 5 分間維持
 *   - 合計リクエスト数: 約 36,000 件（ramp 3,000 + steady 30,000）
 *
 * 合否基準（設計書 §11）:
 *   - P95 < 500ms
 *   - エラー率 < 1%（HTTP 429 はレートリミット正常動作のため成功扱い）
 *
 * Bucket4j レートリミット設定（設計書 §5.5）:
 *   - 20 req/min/user（個人上限）
 *   - 100 req/min/scope（スコープ上限）
 *   - 100 req/s は scope 上限をはるかに超えるため、多数の 429 が返ることが期待動作
 *
 * 実行方法:
 *   $GATLING_HOME/bin/gatling.sh \
 *     --simulations-folder docs/load-test/f088/simulations \
 *     --simulation f088.RepairPlanSimulateLoadTest \
 *     -Dtoken=<JWT_TOKEN> \
 *     -DbaseUrl=http://localhost:8080
 */
class RepairPlanSimulateLoadTest extends Simulation {

  /** ベース URL。システムプロパティ baseUrl で上書き可能。 */
  val baseUrl: String = System.getProperty("baseUrl", "http://localhost:8080")

  /** 認証トークン（Bearer）。システムプロパティ token で指定すること。 */
  val token: String = System.getProperty("token", "changeme")

  /** スコープ設定。organization_id=1 の TEAM スコープ id=1 を対象とする。 */
  val scopeType: String = System.getProperty("scopeType", "TEAM")
  val scopeId: String   = System.getProperty("scopeId", "1")
  val orgId: String     = System.getProperty("orgId", "1")

  val httpProtocol = http
    .baseUrl(baseUrl)
    .header("Content-Type", "application/json")
    .header("Accept", "application/json")
    .header("X-Organization-Id", orgId)
    .header("Authorization", s"Bearer $token")

  /**
   * simulate リクエスト本文。
   * baselineAt はテスト時点の固定値（2026-01-01T00:00:00）を使用。
   * 入力値はシミュレーター境界値の中央値に設定（極端値でエンジン処理を偏らせない）。
   */
  val simulateBody: String =
    s"""{
       |  "monthlyFee": 15000,
       |  "dwellingUnits": 50,
       |  "reserveInflationRate": 0.015,
       |  "cpiInflationRate": 0.010,
       |  "deferralYears": 0,
       |  "loanPrincipal": 0,
       |  "loanInterestRate": 0.0,
       |  "loanTermYears": 0,
       |  "fixedManagementCostYearly": 1200000,
       |  "scenarioHorizonYears": 30,
       |  "baselineAt": "2026-01-01T00:00:00"
       |}""".stripMargin

  val simulateScenario = scenario("simulate 100req/s × 5min")
    .exec(
      http("POST /simulate")
        .post(s"/api/v1/$scopeType/$scopeId/repair-plan/scenarios/simulate")
        .body(StringBody(simulateBody))
        // 200 = 正常応答
        // 429 = Bucket4j レートリミット（正常動作）
        // 上記以外（4xx/5xx）はエラーカウント対象
        .check(status.in(200, 429))
    )

  setUp(
    simulateScenario.inject(
      // 60 秒かけて 1 req/s → 100 req/s へ ramp アップ
      rampUsersPerSec(1).to(100).during(60.seconds),
      // 100 req/s で 5 分間維持
      constantUsersPerSec(100).during(5.minutes)
    ).protocols(httpProtocol)
  ).assertions(
    // P95 が 500ms 未満であること（設計書 §11）
    global.responseTime.percentile(95).lt(500),
    // エラー率が 1% 未満であること（429 は成功扱いのためエラーにカウントされない）
    global.failedRequests.percent.lt(1)
  )
}
