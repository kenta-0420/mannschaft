# F08.7 月次締め障害対応 Runbook（Phase 10-β）

## 1. 目的

F08.7 シフト予算の月次締めバッチ（`MonthlyShiftBudgetCloseBatchJob` / API `POST /api/v1/shift-budget/monthly-close`）で
組織単位の失敗が発生した場合の復旧手順を定める。

Phase 9-δ までは 1 組織失敗で全体停止していたが、Phase 10-β 以降は **closeAll 経路** で
1 組織失敗を他組織から隔離して続行 + `shift_budget_failed_events` テーブルに失敗イベントを永続化する。

---

## 2. 対象システム

- バックエンド: `MonthlyShiftBudgetCloseService`、`MonthlyShiftBudgetCloseBatchJob`、`ShiftBudgetFailedEventService`
- DB テーブル: `shift_budget_failed_events`、`shift_budget_allocations`、`shift_budget_consumptions`、`budget_transactions`
- フィーチャーフラグ: `feature.shift-budget.enabled` / `monthly-close-cron-enabled` / `retry-batch-enabled`

---

## 3. 検知

### 3.1 cron バッチ起動後のログ

毎月 1 日 02:00 JST（`monthly-close-cron-enabled=true` 時）に起動。完了時に下記ログが出る:

```
F08.7 月次締め cron 完了: 対象月=YYYY-MM, processedOrgs=N, failedOrgs=M, ...
```

`failedOrgs > 0` であれば対応が必要。

### 3.2 API #11 のレスポンス

手動起動した場合、レスポンスの `failed_organization_ids` 配列が空でなければ復旧対象。

```json
{
  "data": {
    "year_month": "2026-04",
    "closed_allocations": 12,
    "failed_organization_ids": [42, 99],
    "processed_organization_ids": [1, 2, 3, ...]
  }
}
```

### 3.3 `shift_budget_failed_events` テーブル

```sql
SELECT id, organization_id, event_type, source_id, error_message, retry_count, status, last_retried_at
  FROM shift_budget_failed_events
 WHERE status IN ('PENDING', 'RETRYING', 'EXHAUSTED')
   AND event_type = 'CONSUMPTION_RECORD'   -- 月次締めは CONSUMPTION_RECORD 枠で記録（payload.operation = 'MONTHLY_CLOSE'）
 ORDER BY created_at DESC;
```

`payload->>'$.operation' = 'MONTHLY_CLOSE'` で月次締め失敗のみに絞り込み可能。

### 3.4 管理 API

```
GET /api/v1/shift-budget/failed-events?status=EXHAUSTED
X-Organization-Id: <org_id>
```

権限: `BUDGET_VIEW`

---

## 4. 復旧手順

### 4.1 個別組織の月次締めを再実行する

最も標準的な復旧手段。失敗組織 ID を指定して API #11 を再叩きする。

```
POST /api/v1/shift-budget/monthly-close
X-Organization-Id: <org_id>
Authorization: Bearer <BUDGET_ADMIN token>

{
  "organization_id": 42,
  "year_month": "2026-04"
}
```

**期待レスポンス**:
- 成功: `{ "closed_allocations": >0, "processed_organization_ids": [42] }`
- 既に締め済: HTTP 409 `MONTHLY_ALREADY_CLOSED`（このエラーが出る場合は復旧完了 — 別経路で締めが走った可能性）
- 再失敗: `failed_organization_ids: [42]` + ログを確認して原因切り分け

### 4.2 失敗イベントの個別再実行（リトライバッチ自動分以外）

リトライバッチが PENDING/RETRYING を 15 分毎に拾うが、即時対応したい場合は管理 API で個別起動。

```
POST /api/v1/shift-budget/failed-events/<failed_event_id>/retry
X-Organization-Id: <org_id>
Authorization: Bearer <BUDGET_ADMIN token>
```

**注意**:
- `event_type = CONSUMPTION_RECORD` / `CONSUMPTION_CANCEL` の自動リトライは禁止されている（重複 INSERT リスクのため即 EXHAUSTED 化）
- 月次締め失敗イベントは `event_type = CONSUMPTION_RECORD` で記録されているため、本 API では再実行されず常に EXHAUSTED 化する
- → 月次締め失敗の復旧は §4.1 の **API #11 再起動** を使うこと。本 API は `THRESHOLD_ALERT` / `WORKFLOW_START` / `NOTIFICATION_SEND` 系の個別再実行用

### 4.3 手動補正済マーク

DB 直接修正やオフライン対応で問題を解消した後、失敗イベントを終端状態 (MANUAL_RESOLVED) にしてバッチ対象から外す。

```
POST /api/v1/shift-budget/failed-events/<failed_event_id>/resolve
X-Organization-Id: <org_id>
Authorization: Bearer <BUDGET_ADMIN token>
```

監査ログ `FAILED_EVENT_RESOLVED` が記録される。

---

## 5. 原因別の対応

### 5.1 `MONTHLY_ALREADY_CLOSED` (409)

別経路（手動 API + cron 重複など）で既に締め済。`failed_event` を `MANUAL_RESOLVED` してクローズ。

### 5.2 `BUDGET_ADMIN_REQUIRED` (403)

cron バッチは SecurityContext 不在で動くため発生しないが、API #11 で発生した場合は呼出ユーザーの権限を確認。

### 5.3 DB 接続喪失 / トランザクション競合

リトライバッチで自動回復する想定。15 分待って `status` を確認。3 回失敗で EXHAUSTED 化したらインフラ側調査。

### 5.4 `budget_transactions` への INSERT 失敗

F08.6 側のスキーマ変更や FK 違反が原因のことが多い。Spring Boot ログで該当例外を特定 → DDL 修正後 §4.1 で再起動。

### 5.5 `shift_budget_consumptions` のロック競合

並行する CRUD と衝突した可能性。リトライで解消する場合が多いが、頻発する場合は `closeOneAllocation` の `REQUIRES_NEW` トランザクション境界を見直す。

---

## 6. 確認用 SQL

### 6.1 当月の締め状況サマリ

```sql
SELECT
    a.organization_id,
    a.id AS allocation_id,
    a.period_start,
    a.allocated_amount,
    a.consumed_amount,
    a.confirmed_amount,
    EXISTS (SELECT 1 FROM budget_transactions t
             WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY'
               AND t.source_id = a.id) AS is_closed
  FROM shift_budget_allocations a
 WHERE a.deleted_at IS NULL
   AND a.period_start >= '2026-04-01'
   AND a.period_start <  '2026-05-01';
```

### 6.2 失敗イベントの統計

```sql
SELECT status, event_type, COUNT(*) AS cnt
  FROM shift_budget_failed_events
 GROUP BY status, event_type
 ORDER BY status, event_type;
```

### 6.3 EXHAUSTED 滞留の検出

```sql
SELECT id, organization_id, event_type, source_id,
       LEFT(error_message, 200) AS error_msg, retry_count, last_retried_at
  FROM shift_budget_failed_events
 WHERE status = 'EXHAUSTED'
 ORDER BY updated_at DESC
 LIMIT 50;
```

---

## 7. エスカレーション基準

- `failed_event` が同月内で 10 件以上同時発生した場合 → インフラ側障害の可能性、SRE へエスカレーション
- 同一 organization_id で 3 ヶ月連続失敗 → 該当組織のデータ整合性チェック（`shift_budget_consumptions` の orphan 検出）
- 全組織で `failedOrgs == organizations.count` → 共通基盤障害（DB / Spring Boot / Flyway）、緊急対応

---

## 8. 関連ドキュメント

- 設計書: `docs/features/F08.7_shift_budget_integration.md` v1.3 (Phase 10-β 追補)
- バックエンドコード:
  - `app/shiftbudget/service/MonthlyShiftBudgetCloseService.java`
  - `app/shiftbudget/service/ShiftBudgetFailedEventService.java`
  - `app/shiftbudget/batch/MonthlyShiftBudgetCloseBatchJob.java`
  - `app/shiftbudget/batch/ShiftBudgetRetryBatchJob.java`
  - `app/shiftbudget/controller/ShiftBudgetFailedEventController.java`
- 監査ログイベント: `FAILED_EVENT_RETRIED` / `FAILED_EVENT_RESOLVED` / `SHIFT_BUDGET_MONTHLY_CLOSED`
