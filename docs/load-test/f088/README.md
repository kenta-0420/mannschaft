# F08.8 修繕計画ダッシュボード 負荷試験

## 概要

F08.8（マンション修繕長期計画ダッシュボード）の以下 3 シナリオを対象とした Gatling 手動負荷試験。
設計書 §11「負荷試験」要件に対応する。

| シナリオ | クラス | 概要 |
|---|---|---|
| 1 | `RepairPlanSimulateLoadTest` | simulate API 100 req/s × 5 min — Bucket4j レートリミット・Bulkhead 検証 |
| 2 | `RepairPlanTimelineLoadTest` | 地層タイムライン集計 30 万件シード環境での応答時間 |
| 3 | `RepairPlanPdfBulkheadTest` | PDF 申し送りパック 3 並列同時リクエスト — Bulkhead 上限動作確認 |

---

## 実行前提

### インフラ要件

- Docker + Docker Compose 起動済み（ローカル環境またはステージング環境）
- Spring Boot アプリが `http://localhost:8080` で起動済み
- MySQL 8.0（コンテナ名: `mannschaft-mysql`）が起動済み
- Valkey（Redis 互換）が起動済み（レートリミット用 Bucket4j バックエンド）
- Gatling 3.9.x インストール済み（`$GATLING_HOME` 環境変数設定済み）

### Gatling インストール

```bash
# Homebrew (macOS)
brew install gatling

# 手動インストール
curl -L https://repo1.maven.org/maven2/io/gatling/highcharts/gatling-charts-highcharts-bundle/3.9.5/gatling-charts-highcharts-bundle-3.9.5-bundle.zip -o gatling.zip
unzip gatling.zip
export GATLING_HOME=$(pwd)/gatling-charts-highcharts-bundle-3.9.5
```

### シードデータ適用

負荷試験は 30 万件の `repair_plan_items` が投入された状態で実施すること。

```bash
# シードSQL 適用（要件: organization_id=1, scope_type=TEAM, scope_id=1 が存在すること）
docker exec -i mannschaft-mysql mysql -u root -ppassword mannschaft < docs/load-test/f088/seed/seed_30m_repair_items.sql
```

適用後の件数確認:

```sql
SELECT COUNT(*) FROM repair_plan_items
WHERE organization_id = 1 AND scope_type = 'TEAM' AND scope_id = 1;
-- 期待値: 300000
```

### 認証トークン準備

シナリオ 1・2・3 は `Authorization: Bearer ${token}` を使用する。
事前に管理者（ADMIN ロール）の JWT を取得して環境変数に設定する。

```bash
# テスト用トークン取得（curl 例）
export REPAIR_TEST_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"test-admin@example.com","password":"TestPassword1!"}' \
  | jq -r '.data.access_token')
```

PDF Bulkhead テスト（シナリオ 3）では複数ユーザーで同時リクエストを行う。
`boardHandoverPackId` の UUID は事前に申し送りパックを作成して取得すること。

---

## 実行方法

### シナリオ 1: simulate 100 req/s × 5 min

```bash
$GATLING_HOME/bin/gatling.sh \
  --simulations-folder docs/load-test/f088/simulations \
  --simulation f088.RepairPlanSimulateLoadTest \
  -Dtoken=${REPAIR_TEST_TOKEN} \
  -DbaseUrl=http://localhost:8080
```

### シナリオ 2: タイムライン集計（30 万件シード）

```bash
$GATLING_HOME/bin/gatling.sh \
  --simulations-folder docs/load-test/f088/simulations \
  --simulation f088.RepairPlanTimelineLoadTest \
  -Dtoken=${REPAIR_TEST_TOKEN} \
  -DbaseUrl=http://localhost:8080
```

### シナリオ 3: PDF Bulkhead（3 並列同時リクエスト）

```bash
# 事前: 申し送りパックを作成して UUID を取得
export PACK_ID=<board_handover_pack_uuid>

$GATLING_HOME/bin/gatling.sh \
  --simulations-folder docs/load-test/f088/simulations \
  --simulation f088.RepairPlanPdfBulkheadTest \
  -Dtoken=${REPAIR_TEST_TOKEN} \
  -DbaseUrl=http://localhost:8080 \
  -DpackId=${PACK_ID}
```

---

## 合否判定基準

設計書 §11 の負荷試験要件に基づく。

| シナリオ | 試験内容 | 合格基準 |
|---|---|---|
| 1 (simulate) | 100 req/s × 5 min、ramp 1 min | P95 < 500ms、エラー率 < 1%（429 は成功扱い） |
| 2 (timeline) | 並列 20 ユーザー、ramp 60s、30 万件シード | P95 < 500ms |
| 3 (pdfBulkhead) | 4 並列同時 PDF 生成リクエスト | 4 並列目以降で 503 が返ること（Bulkhead 上限 = 3） |

> **429 の扱い**: simulate API は Bucket4j で 20 req/min/user・100 req/min/scope の二重レートリミットが実装されている。
> 429 はレートリミットの正常動作であるため、シナリオ 1 では成功扱い（`status.in(200, 429)`）とする。

> **503 の扱い**: PDF 生成は `@Async Bulkhead` で同時実行数 3 に制限されている。
> 3 を超えた並列リクエストは 503 で拒否されることが期待動作。

---

## 結果レポート

Gatling 実行後は `target/gatling/results/` 配下に HTML レポートが生成される。
主要指標:

- `global.responseTime.percentile(95)` — P95 レスポンスタイム
- `global.failedRequests.percent` — エラー率
- `global.responseTime.max` — 最大レスポンスタイム

---

## 前提条件（organization/team セットアップ）

シードSQL を正しく適用するためには、以下のレコードが DB に存在する必要がある:

```sql
-- organization_id=1 の組織が存在すること
SELECT id FROM organizations WHERE id = 1;

-- scope_id=1 の TEAM が organization_id=1 に属すること
SELECT id FROM teams WHERE id = 1 AND organization_id = 1;

-- repair_longterm_plan モジュールが organization_id=1 で有効であること
SELECT * FROM module_activations
WHERE organization_id = 1 AND module_key = 'repair_longterm_plan' AND is_active = TRUE;
```

開発環境の Flyway シードデータ（`V1.xxx` 系）でこれらは投入済みの前提。
該当レコードが存在しない場合は先に作成すること。

---

## 注意事項

- 本スクリプトは **CI には組み込まない**。開発者が手動で実行する。
- 負荷試験はステージング環境または専用の負荷試験環境で実施すること。**本番環境での実行は禁止**。
- シードSQL（30 万件）の適用は既存データを破壊しないが、`repair_plan_items` のサイズが大幅に増加する。
  試験後は以下で削除すること:
  ```sql
  DELETE FROM repair_plan_items
  WHERE organization_id = 1 AND scope_type = 'TEAM' AND scope_id = 1
    AND created_at >= (SELECT MIN(created_at) FROM repair_plan_items_seed_marker);
  -- または別途 seed 専用 organization_id を使用する
  ```
- Gatling レポートは `docs/load-test/f088/results/` に手動で保管することを推奨（git 管理外）。
