# F07: パフォーマンス管理

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 7
> **最終更新**: 2026-03-11
> **モジュール種別**: デフォルト機能 #19

---

## 1. 概要

チームや個人の活動パフォーマンスをカスタム指標で記録・可視化する機能。得点、出場時間、走行距離等の指標をチームが自由に定義し、メンバーごとのデータを蓄積する。記録されたデータはチーム統計ダッシュボードと個人専用ダッシュボードの両方に自動反映され、チャート・グラフ（Chart.js / vue-chartjs）で視覚的に表示する。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全チームのパフォーマンスデータを参照 |
| ADMIN | 指標定義の作成・編集・削除、全メンバーの記録入力・編集・削除、チーム統計閲覧 |
| DEPUTY_ADMIN | `MANAGE_PERFORMANCE` 権限を持つ場合: 記録入力・編集・削除、チーム統計閲覧 |
| MEMBER | 自分のパフォーマンスデータ閲覧、チーム統計閲覧（ADMIN が許可した場合）|
| SUPPORTER | 対象外 |
| GUEST | 対象外 |

### 対象レベル
- [ ] 組織 (Organization)
- [x] チーム (Team) — 指標定義・記録管理・チーム統計
- [x] 個人 (Personal) — 自分のパフォーマンス閲覧

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `performance_metrics` | チームごとのパフォーマンス指標定義 | なし |
| `performance_records` | メンバーごとの指標値の記録 | なし |

### テーブル定義

#### `performance_metrics`

チームが追跡したいパフォーマンス指標を定義する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `name` | VARCHAR(100) | NO | — | 指標名（例: 得点、出場時間、走行距離）|
| `unit` | VARCHAR(30) | YES | NULL | 単位（例: 点、分、km）|
| `data_type` | ENUM('INTEGER', 'DECIMAL', 'TIME') | NO | 'DECIMAL' | データ型 |
| `aggregation_type` | ENUM('SUM', 'AVG', 'MAX', 'MIN', 'LATEST') | NO | 'SUM' | 統計集計方法 |
| `description` | VARCHAR(500) | YES | NULL | 指標の説明 |
| `sort_order` | INT | NO | 0 | 表示順 |
| `is_visible_to_members` | BOOLEAN | NO | TRUE | MEMBER にチーム統計を公開するか |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_pm_team_sort (team_id, sort_order)   -- チーム内の指標一覧取得用
```

**制約・備考**
- 1チームあたりの指標上限: **30件**（アプリ層で検証）
- `data_type = 'TIME'` の場合、値は分単位の整数で保存する（例: 90分 → `90`）。表示時にフロントエンドで `HH:MM` 形式に変換
- 指標削除時: `performance_records` の対応レコードもカスケード削除する
- `aggregation_type` はチーム統計ダッシュボードでの集計に使用（例: SUM = 累計得点、AVG = 平均出場時間）

---

#### `performance_records`

個々のメンバーのパフォーマンス記録。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `metric_id` | BIGINT UNSIGNED | NO | — | FK → performance_metrics（ON DELETE CASCADE）|
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE RESTRICT）|
| `recorded_date` | DATE | NO | — | 記録日 |
| `value` | DECIMAL(15,4) | NO | — | 記録値（INTEGER/DECIMAL/TIME すべて数値として保存）|
| `note` | VARCHAR(300) | YES | NULL | メモ（例: 練習試合 vs ○○チーム）|
| `recorded_by` | BIGINT UNSIGNED | YES | NULL | FK → users（記録者; SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_pr_metric_user (metric_id, user_id)              -- 特定指標×特定ユーザーの時系列取得
INDEX idx_pr_metric_date (metric_id, recorded_date DESC)   -- 指標ごとの日付降順一覧
INDEX idx_pr_user_date (user_id, recorded_date DESC)       -- ユーザーの全指標日付降順一覧
```

**制約・備考**
- 同一ユーザー・同一指標・同一日に複数レコードを許可する（例: 1日に2試合出場した場合）
- `user_id` ON DELETE RESTRICT: パフォーマンスデータの保全。ユーザー退会は論理削除で対応
- `value` の `DECIMAL(15,4)` は整数値から小数値まで対応（TIME 型は分単位の整数値で保存）

### ER図（テキスト形式）
```
teams (1) ──── (N) performance_metrics
performance_metrics (1) ──── (N) performance_records
users (1) ──── (N) performance_records [user_id]
users (1) ──── (N) performance_records [recorded_by]
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/teams/{teamId}/performance/metrics` | 必要 | 指標定義一覧取得 |
| POST | `/api/v1/teams/{teamId}/performance/metrics` | 必要 | 指標定義作成 |
| PUT | `/api/v1/teams/{teamId}/performance/metrics/{id}` | 必要 | 指標定義更新 |
| DELETE | `/api/v1/teams/{teamId}/performance/metrics/{id}` | 必要 | 指標定義削除（関連レコードもカスケード削除）|
| POST | `/api/v1/teams/{teamId}/performance/records` | 必要 | パフォーマンス記録入力 |
| PUT | `/api/v1/teams/{teamId}/performance/records/{id}` | 必要 | パフォーマンス記録更新 |
| DELETE | `/api/v1/teams/{teamId}/performance/records/{id}` | 必要 | パフォーマンス記録削除 |
| POST | `/api/v1/teams/{teamId}/performance/records/bulk` | 必要 | 一括記録入力（複数メンバー×複数指標）|
| GET | `/api/v1/teams/{teamId}/performance/stats` | 必要 | チーム統計ダッシュボード |
| GET | `/api/v1/teams/{teamId}/members/{userId}/performance` | 必要 | 特定メンバーのパフォーマンス |
| GET | `/api/v1/teams/{teamId}/performance/records/export` | 必要 | パフォーマンス記録CSVエクスポート |
| GET | `/api/v1/performance/me` | 必要 | 自分のパフォーマンス（全チーム横断）|

### リクエスト／レスポンス仕様

#### `POST /api/v1/teams/{teamId}/performance/metrics`

**認可**: ADMIN のみ

**リクエストボディ**
```json
{
  "name": "得点",
  "unit": "点",
  "data_type": "INTEGER",
  "aggregation_type": "SUM",
  "description": "試合ごとの得点",
  "sort_order": 1,
  "is_visible_to_members": true
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 1,
    "name": "得点",
    "unit": "点",
    "data_type": "INTEGER",
    "aggregation_type": "SUM",
    "description": "試合ごとの得点",
    "sort_order": 1,
    "is_visible_to_members": true
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 409 | 指標上限（30件）超過 |

#### `POST /api/v1/teams/{teamId}/performance/records/bulk`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_PERFORMANCE`）

試合後などに複数メンバーの複数指標を一括入力する。

**リクエストボディ**
```json
{
  "recorded_date": "2026-03-10",
  "note": "練習試合 vs ○○チーム",
  "entries": [
    { "user_id": 42, "metric_id": 1, "value": 2 },
    { "user_id": 42, "metric_id": 2, "value": 75 },
    { "user_id": 43, "metric_id": 1, "value": 1 },
    { "user_id": 43, "metric_id": 2, "value": 90 }
  ]
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "created_count": 4
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（entries が空、value の型不一致等）|
| 403 | 権限不足 |
| 404 | `metric_id` / `user_id` が存在しない |
| 422 | `user_id` がチームに所属していない |

#### `GET /api/v1/teams/{teamId}/performance/records/export`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_PERFORMANCE`）

パフォーマンス記録を CSV 形式でエクスポートする。対象件数が 1,000 件以下の場合はストリーミングレスポンスで即時返却し、1,000 件超の場合は非同期ジョブを起動して完了後にダウンロード URL を通知する（サービス記録エクスポートと同一パターン）。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `metric_id` | Long | No | 特定指標でフィルタ |
| `user_id` | Long | No | 特定ユーザーでフィルタ |
| `date_from` | Date | No | 期間開始日 |
| `date_to` | Date | No | 期間終了日 |

**レスポンス（200 OK / ≤1,000 件）**
- `Content-Type: text/csv; charset=UTF-8`（BOM 付き UTF-8。Excel 文字化け防止）
- `Content-Disposition: attachment; filename="performance_records_{teamId}_{yyyyMMdd}.csv"`
- CSV カラム: `recorded_date, user_display_name, metric_name, value, unit, note`

**レスポンス（202 Accepted / >1,000 件）**
```json
{
  "data": {
    "job_id": "export-perf-abc123",
    "status": "PROCESSING",
    "message": "エクスポートを開始しました。完了後に通知します。"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足 |
| 404 | チームが存在しない |

**備考**
- CSV インポート機能は後続フェーズで対応予定
- CSV インジェクション対策: セル値の先頭が `=`, `+`, `-`, `@` の場合はシングルクォートを先頭に付与

---

#### `GET /api/v1/teams/{teamId}/performance/stats`

**認可**: ADMIN / DEPUTY_ADMIN / MEMBER（`is_visible_to_members = true` の指標のみ）

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `metric_id` | Long | No | 特定指標でフィルタ |
| `date_from` | Date | No | 期間開始日 |
| `date_to` | Date | No | 期間終了日 |

**レスポンス（200 OK）**
```json
{
  "data": {
    "metrics": [
      {
        "metric_id": 1,
        "name": "得点",
        "unit": "点",
        "aggregation_type": "SUM",
        "team_total": 45,
        "team_avg": 2.25,
        "ranking": [
          { "rank": 1, "user_id": 42, "display_name": "田中太郎", "value": 12 },
          { "rank": 1, "user_id": 44, "display_name": "佐藤花子", "value": 12 },
          { "rank": 3, "user_id": 43, "display_name": "山田次郎", "value": 8 }
        ]
      }
    ],
    "period": {
      "from": "2026-01-01",
      "to": "2026-03-10"
    }
  }
}
```

#### `GET /api/v1/performance/me`

**認可**: MEMBER 以上

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | No | 特定チームでフィルタ |
| `date_from` | Date | No | 期間開始日 |
| `date_to` | Date | No | 期間終了日 |
| `cursor` | Long | No | カーソル（前ページ最後のチーム ID） |
| `limit` | Int | No | 取得件数（デフォルト 20） |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "team_id": 10,
      "team_name": "チームA",
      "metrics": [
        {
          "metric_id": 1,
          "name": "得点",
          "unit": "点",
          "aggregation_type": "SUM",
          "total": 12,
          "record_count": 18,
          "latest_record": {
            "recorded_date": "2026-03-10",
            "value": 2,
            "note": "練習試合 vs ○○チーム"
          }
        }
      ]
    }
  ]
}
```

#### `GET /api/v1/teams/{teamId}/members/{userId}/performance`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_PERFORMANCE`）。自分のデータは MEMBER 以上

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `date_from` | Date | No | 期間開始日 |
| `date_to` | Date | No | 期間終了日 |

**レスポンス（200 OK）**
```json
{
  "data": {
    "user_id": 42,
    "display_name": "田中太郎",
    "period": { "from": "2026-01-01", "to": "2026-03-10" },
    "metrics": [
      {
        "metric_id": 1,
        "name": "得点",
        "unit": "点",
        "aggregation_type": "SUM",
        "total": 12,
        "avg": 0.67,
        "max": 3,
        "min": 0,
        "record_count": 18,
        "trend": [
          { "month": "2026-01", "value": 5 },
          { "month": "2026-02", "value": 4 },
          { "month": "2026-03", "value": 3 }
        ]
      }
    ]
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足（MEMBER が他メンバーのデータを参照）|
| 404 | ユーザーがチームに所属していない |

---

## 5. ビジネスロジック

### 主要フロー

#### パフォーマンス記録入力
```
1. ADMIN/DEPUTY_ADMIN が記録フォーム（個別 or 一括）を入力
2. バックエンドがチーム所属・権限を検証
3. 各 user_id のチーム所属を検証
4. metric_id の存在・data_type に基づく値バリデーション
5. performance_records を INSERT
6. ApplicationEvent（PerformanceRecordedEvent）を発行
7. ダッシュボードの統計キャッシュ（Redis）を無効化
```

#### 統計ダッシュボード表示
```
1. ユーザーがチームの統計ダッシュボードを表示
2. Redis キャッシュを確認（TTL: 5分）
3. キャッシュミスの場合、performance_records を aggregation_type に基づいて集計
4. 集計結果をキャッシュに保存してレスポンス
```

### 重要な判定ロジック
- **集計方法の切り替え**: `aggregation_type` に基づいて SQL 集計関数を動的に選択（SUM/AVG/MAX/MIN）。LATEST は `recorded_date DESC` の先頭レコードの値
- **MEMBER の閲覧制限**: `is_visible_to_members = false` の指標はチーム統計に含めない。個人ダッシュボードでは自分の記録のみ表示
- **INTEGER 型バリデーション**: `data_type = 'INTEGER'` の場合、`value` が整数であることを検証（小数点以下が 0 でない場合は 400 エラー）
- **ランキング順位**: 競技式ランキング（competition ranking）を採用。同値のメンバーは同順位を付与し、次の順位をスキップする（例: 1位, 1位, 3位）
- **チャート表示期間**: 個人ダッシュボードのチャート表示期間のデフォルトは **直近3ヶ月**。ユーザーは 6ヶ月 / 1年 に切り替え可能

---

## 6. セキュリティ考慮事項

- **認可チェック**: `PerformanceService` の入り口で `teamId` と `currentUser` の所属・ロールを必ず検証
- **データ公開制御**: MEMBER が他メンバーの個別記録を直接参照するエンドポイントは提供しない。チーム統計は `is_visible_to_members` で制御
- **一括入力のサイズ制限**: `bulk` エンドポイントは 1リクエストあたり最大 **200 entries**（アプリ層で検証）
- **レートリミット**: 記録作成 API に `Bucket4j` で 1分間に60回の制限を適用
- **CSV インジェクション対策**: CSV エクスポート時、セル値の先頭が `=`, `+`, `-`, `@` の場合はシングルクォートを先頭に付与する

---

## 7. Flywayマイグレーション

```
V7.008__create_performance_metrics_table.sql
V7.009__create_performance_records_table.sql
```

**マイグレーション上の注意点**
- `performance_metrics` は `teams` テーブルへの FK を持つ
- `performance_records` は `performance_metrics`・`users` テーブルへの FK を持つ

---

## 8. 未解決事項

- ~~CSV インポート / エクスポート機能のスコープ確認~~ → CSVエクスポートを追加（≤1,000件: ストリーミング、>1,000件: 非同期ジョブ）。CSVインポートは後続フェーズで対応
- ~~チーム統計の Redis キャッシュ TTL を 5分で固定するか、設定可能にするか~~ → TTL 5分で固定
- ~~ランキング表示の同率順位の扱い~~ → 競技式ランキング採用（同値=同順位、次の順位スキップ。例: 1位, 1位, 3位）
- ~~個人ダッシュボードでのチャート表示期間のデフォルト値~~ → デフォルト3ヶ月、ユーザーが6ヶ月/1年に切り替え可能

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-10 | 初版作成 |
| 2026-03-11 | 未解決事項4件を解決: CSVエクスポートAPI追加、Redis TTL 5分固定、競技式ランキング採用、チャートデフォルト3ヶ月 |
| 2026-03-11 | 精査: /performance/me レスポンス JSON 追加、/members/{userId}/performance 詳細仕様追加、CSV インジェクション対策追加 |
| 2026-03-11 | 精査②: CSV エクスポートに BOM 付き UTF-8 明記、/performance/me にページネーションパラメータ追加、sort_order 型を INT に統一 |
