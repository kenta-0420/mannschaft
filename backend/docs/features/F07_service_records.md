# F07: サービス履歴

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 7
> **最終更新**: 2026-03-10
> **モジュール種別**: 選択式モジュール #4

---

## 1. 概要

施術記録・来店記録・対応履歴等を汎用的に管理する記録システム。チームごとに保存したい項目をカスタムフィールドで自由に定義でき、美容室の施術内容、整骨院の来院記録、塾の授業記録など業種を問わず活用できる。記録のメンバー個人ダッシュボードへの反映はチーム/組織が許可した場合のみ有効となり、許可時にはメンバーが自分の記録に「いいね！」リアクションを付けられる。また、よく使う記録パターンをテンプレートとして保存し、記録作成時に選択して入力を効率化できる。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全チームのサービス履歴を参照 |
| ADMIN | 記録の作成・編集・削除、カスタムフィールド定義の管理、全メンバーの履歴参照 |
| DEPUTY_ADMIN | `MANAGE_SERVICE_RECORDS` 権限を持つ場合: 記録の作成・編集・削除、全メンバーの履歴参照 |
| MEMBER | 自分のサービス履歴の閲覧（チームがダッシュボード共有を許可している場合のみ）、自分の記録へのリアクション |
| SUPPORTER | 対象外 |
| GUEST | 対象外 |

### 対象レベル
- [x] 組織 (Organization) — テンプレート共有のみ（記録自体はチームスコープ）
- [x] チーム (Team) — 記録管理
- [x] 個人 (Personal) — 自分の履歴閲覧

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `service_records` | サービス履歴レコード本体 | あり |
| `service_record_fields` | チームごとのカスタムフィールド定義 | `is_active` による論理無効化 |
| `service_record_values` | 各レコードのカスタムフィールド値 | なし |
| `service_record_settings` | チーム単位の機能設定（ダッシュボード共有等） | なし |
| `service_record_reactions` | メンバーの記録へのリアクション | なし |
| `service_record_templates` | 記録テンプレート定義 | あり |
| `service_record_template_values` | テンプレートのカスタムフィールド初期値 | なし |

### テーブル定義

#### `service_records`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `member_user_id` | BIGINT UNSIGNED | NO | — | FK → users（サービスを受けたメンバー）|
| `staff_user_id` | BIGINT UNSIGNED | YES | NULL | FK → users（担当スタッフ; SET NULL on delete）|
| `service_date` | DATE | NO | — | サービス実施日 |
| `title` | VARCHAR(200) | NO | — | 記録タイトル（例: カット＋カラー、第5回授業）|
| `note` | TEXT | YES | NULL | 備考・メモ |
| `duration_minutes` | SMALLINT UNSIGNED | YES | NULL | 所要時間（分）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_sr_team_id (team_id)
INDEX idx_sr_member (member_user_id)                     -- メンバーの履歴一覧取得用
INDEX idx_sr_team_member (team_id, member_user_id)       -- チーム内の特定メンバー検索用
INDEX idx_sr_service_date (team_id, service_date DESC)   -- 日付順一覧用
```

**制約・備考**
- 論理削除: `deleted_at DATETIME nullable`（SoftDeletableEntity 適用）
- `member_user_id` は ON DELETE RESTRICT（メンバーの退会時は論理削除で対応）
- `staff_user_id` は ON DELETE SET NULL（スタッフ削除後も記録は残る）

---

#### `service_record_fields`

チームごとにカスタムフィールドを定義する。定義はチーム単位で管理し、全記録に共通で適用される。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `field_name` | VARCHAR(100) | NO | — | フィールド名（例: 施術内容、使用薬剤）|
| `field_type` | ENUM('TEXT', 'NUMBER', 'DATE', 'SELECT', 'CHECKBOX') | NO | — | フィールド型 |
| `options` | JSON | YES | NULL | SELECT 型の選択肢リスト（例: `["カット", "カラー", "パーマ"]`）|
| `is_required` | BOOLEAN | NO | FALSE | 必須フィールドか |
| `sort_order` | INT | NO | 0 | 表示順 |
| `is_active` | BOOLEAN | NO | TRUE | FALSE = 新規記録作成時に非表示（既存値は保持）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_srf_team_sort (team_id, sort_order)   -- チーム内のフィールド一覧取得用
```

**制約・備考**
- 1チームあたりのカスタムフィールド上限: **20件**（`is_active = true` のもののみカウント。アプリ層で検証）
- フィールド定義は物理削除しない。`is_active = FALSE` で論理無効化し、既存の `service_record_values` のデータを保護する。施術記録・来院記録等の過去データ消失を防止
- `options` は `field_type = 'SELECT'` の場合のみ使用。他の型では NULL

---

#### `service_record_values`

各サービス記録に対するカスタムフィールドの値を格納する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `service_record_id` | BIGINT UNSIGNED | NO | — | FK → service_records（ON DELETE CASCADE）|
| `field_id` | BIGINT UNSIGNED | NO | — | FK → service_record_fields（ON DELETE RESTRICT）|
| `value` | TEXT | YES | NULL | フィールド値（全型を TEXT で保存。アプリ層で型変換）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_srv_record_field (service_record_id, field_id)   -- 1記録1フィールドにつき1値
```

**制約・備考**
- `service_record_id` ON DELETE CASCADE: 親レコード論理削除時は物理削除せず残存する（論理削除は `service_records.deleted_at` で制御）。物理削除時にカスケード
- `field_id` ON DELETE RESTRICT: フィールド定義は物理削除しない（`is_active = FALSE` で論理無効化）。RESTRICT により誤った物理削除を防止
- NUMBER 型の値はアプリ層で `BigDecimal` に変換して検証する

---

#### `service_record_settings`

チーム単位のサービス履歴機能設定。チームがこのモジュールを有効化した初回アクセス時にデフォルト値で INSERT する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `is_dashboard_enabled` | BOOLEAN | NO | FALSE | メンバーの個人ダッシュボードにサービス履歴を表示するか |
| `is_reaction_enabled` | BOOLEAN | NO | FALSE | メンバーが自分の記録にリアクションを付けられるか（`is_dashboard_enabled = true` の場合のみ有効）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_srs_team (team_id)   -- チームごとに1レコード
```

**制約・備考**
- `is_reaction_enabled` は `is_dashboard_enabled = true` の場合のみ意味を持つ。アプリ層で `is_dashboard_enabled = false` の場合は `is_reaction_enabled` を無視する
- デフォルトは両方 `FALSE`（明示的に許可するまでメンバーに公開しない）
- ADMIN のみ変更可能

---

#### `service_record_reactions`

メンバーがダッシュボードから自分のサービス記録に対して付けるリアクション。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `service_record_id` | BIGINT UNSIGNED | NO | — | FK → service_records（ON DELETE CASCADE）|
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `reaction_type` | ENUM('LIKE', 'THANKS', 'GREAT') | NO | 'LIKE' | リアクション種別 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_srr_record_user (service_record_id, user_id)   -- 1記録につき1ユーザー1リアクション
INDEX idx_srr_record (service_record_id)                      -- 記録ごとのリアクション数集計用
```

**制約・備考**
- 自分の記録（`service_records.member_user_id = user_id`）にのみリアクション可能（アプリ層で検証）
- リアクション種別は `LIKE`（いいね！）、`THANKS`（ありがとう）、`GREAT`（すごい！）の3種。トグル動作（再度押すと削除）
- `is_dashboard_enabled = true` かつ `is_reaction_enabled = true` の場合のみ API がリアクションを受け付ける
- スタッフ（ADMIN/DEPUTY_ADMIN）はリアクション一覧を管理画面から確認可能（メンバーの反応を把握）

---

#### `service_record_templates`

よく使う記録パターンを保存するテンプレート。チーム/組織ごとに複数のテンプレートを保持できる。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（チーム単位; NULL = 組織単位）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（組織単位; NULL = チーム単位）|
| `name` | VARCHAR(100) | NO | — | テンプレート名（例: カット＋カラー、月次授業報告）|
| `title_template` | VARCHAR(200) | YES | NULL | 記録タイトルの初期値 |
| `note_template` | TEXT | YES | NULL | 備考の初期値 |
| `default_duration_minutes` | SMALLINT UNSIGNED | YES | NULL | デフォルト所要時間（分）|
| `sort_order` | INT | NO | 0 | 表示順 |
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_srt_team_sort (team_id, sort_order)           -- チーム内テンプレート一覧用
INDEX idx_srt_org_sort (organization_id, sort_order)    -- 組織内テンプレート一覧用
```

**制約・備考**
- `team_id` と `organization_id` はどちらか一方のみ非 NULL（XOR）:
  ```sql
  CONSTRAINT chk_srt_scope
    CHECK (
      (team_id IS NOT NULL AND organization_id IS NULL)
      OR (team_id IS NULL AND organization_id IS NOT NULL)
    )
  ```
- **組織レベルテンプレート**: `organization_id` を設定した場合、その組織配下の全チームがテンプレートを共有して利用可能。チーム固有のテンプレートと組織テンプレートが両方表示される（組織テンプレートが先、チームテンプレートが後）
- 1チーム/組織あたりのテンプレート上限: **デフォルト10件**（有料プランで上限引き上げ。具体的な段階・上限数は Phase 8 で確定）。上限値はアプリ層で設定から取得し、ハードコードしない。チームテンプレートと組織テンプレートの上限は独立してカウントする
- 論理削除: `deleted_at DATETIME nullable`（SoftDeletableEntity 適用）
- テンプレートを使って記録を作成しても `service_records` にテンプレート ID は保存しない（テンプレートはあくまで入力補助で、記録とテンプレートの紐付けは不要）
- 組織テンプレートの作成・編集・削除は組織の ADMIN のみ。チームの ADMIN/DEPUTY_ADMIN は閲覧・利用のみ

---

#### `service_record_template_values`

テンプレートに紐付くカスタムフィールドの初期値。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `template_id` | BIGINT UNSIGNED | NO | — | FK → service_record_templates（ON DELETE CASCADE）|
| `field_id` | BIGINT UNSIGNED | NO | — | FK → service_record_fields（ON DELETE RESTRICT）|
| `default_value` | TEXT | YES | NULL | フィールドの初期値 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_srtv_template_field (template_id, field_id)   -- テンプレート×フィールドで一意
```

**制約・備考**
- `template_id` ON DELETE CASCADE: テンプレート削除時に値も自動削除
- `field_id` ON DELETE RESTRICT: フィールド定義は物理削除しない（`is_active = FALSE` で論理無効化）。RESTRICT により誤った物理削除を防止
- すべてのカスタムフィールドに値を設定する必要はない（部分設定可）

### ER図（テキスト形式）
```
teams (1) ──── (N) service_records
teams (1) ──── (N) service_record_fields
teams (1) ──── (1) service_record_settings
teams (1) ──── (N) service_record_templates [team_id]
organizations (1) ──── (N) service_record_templates [organization_id]
users (1) ──── (N) service_records [member_user_id]
users (1) ──── (N) service_records [staff_user_id]

service_records (1) ──── (N) service_record_values
service_records (1) ──── (N) service_record_reactions
service_record_fields (1) ──── (N) service_record_values
service_record_fields (1) ──── (N) service_record_template_values

service_record_templates (1) ──── (N) service_record_template_values
users (1) ──── (N) service_record_reactions [user_id]
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/teams/{teamId}/service-records` | 必要 | チーム内のサービス履歴一覧取得 |
| POST | `/api/v1/teams/{teamId}/service-records` | 必要 | サービス履歴作成 |
| GET | `/api/v1/teams/{teamId}/service-records/{id}` | 必要 | サービス履歴詳細取得 |
| PUT | `/api/v1/teams/{teamId}/service-records/{id}` | 必要 | サービス履歴更新 |
| DELETE | `/api/v1/teams/{teamId}/service-records/{id}` | 必要 | サービス履歴削除（論理削除）|
| GET | `/api/v1/teams/{teamId}/members/{userId}/service-history` | 必要 | 特定メンバーの履歴一覧 |
| GET | `/api/v1/service-records/me` | 必要 | 自分のサービス履歴一覧（全チーム横断）|
| GET | `/api/v1/teams/{teamId}/service-record-fields` | 必要 | カスタムフィールド定義一覧 |
| POST | `/api/v1/teams/{teamId}/service-record-fields` | 必要 | カスタムフィールド作成 |
| PUT | `/api/v1/teams/{teamId}/service-record-fields/{id}` | 必要 | カスタムフィールド更新 |
| DELETE | `/api/v1/teams/{teamId}/service-record-fields/{id}` | 必要 | カスタムフィールド無効化（is_active = false）|
| PATCH | `/api/v1/teams/{teamId}/service-record-fields/sort-order` | 必要 | カスタムフィールド並び替え（一括更新）|
| GET | `/api/v1/teams/{teamId}/service-records/settings` | 必要 | 機能設定取得 |
| PUT | `/api/v1/teams/{teamId}/service-records/settings` | 必要 | 機能設定更新 |
| POST | `/api/v1/teams/{teamId}/service-records/{id}/reactions` | 必要 | リアクション追加（トグル）|
| DELETE | `/api/v1/teams/{teamId}/service-records/{id}/reactions` | 必要 | リアクション削除 |
| GET | `/api/v1/teams/{teamId}/service-records/templates` | 必要 | テンプレート一覧取得（チーム固有 + 組織共有を統合）|
| POST | `/api/v1/teams/{teamId}/service-records/templates` | 必要 | チームテンプレート作成 |
| GET | `/api/v1/teams/{teamId}/service-records/templates/{id}` | 必要 | テンプレート詳細取得 |
| PUT | `/api/v1/teams/{teamId}/service-records/templates/{id}` | 必要 | テンプレート更新 |
| DELETE | `/api/v1/teams/{teamId}/service-records/templates/{id}` | 必要 | テンプレート削除（論理削除）|
| GET | `/api/v1/organizations/{orgId}/service-records/templates` | 必要 | 組織テンプレート一覧取得 |
| POST | `/api/v1/organizations/{orgId}/service-records/templates` | 必要 | 組織テンプレート作成 |
| PUT | `/api/v1/organizations/{orgId}/service-records/templates/{id}` | 必要 | 組織テンプレート更新 |
| DELETE | `/api/v1/organizations/{orgId}/service-records/templates/{id}` | 必要 | 組織テンプレート削除（論理削除）|
| GET | `/api/v1/teams/{teamId}/service-records/export` | 必要 | CSV エクスポート |

### リクエスト／レスポンス仕様

#### `POST /api/v1/teams/{teamId}/service-records`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_SERVICE_RECORDS`）

**リクエストボディ**
```json
{
  "member_user_id": 42,
  "staff_user_id": 5,
  "service_date": "2026-03-10",
  "title": "カット＋カラー",
  "note": "前回より暗めのトーンを希望",
  "duration_minutes": 90,
  "custom_fields": [
    { "field_id": 1, "value": "カラー" },
    { "field_id": 2, "value": "60" }
  ]
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 123,
    "team_id": 10,
    "member_user_id": 42,
    "staff_user_id": 5,
    "service_date": "2026-03-10",
    "title": "カット＋カラー",
    "note": "前回より暗めのトーンを希望",
    "duration_minutes": 90,
    "custom_fields": [
      { "field_id": 1, "field_name": "施術内容", "value": "カラー" },
      { "field_id": 2, "field_name": "所要時間", "value": "60" }
    ],
    "created_at": "2026-03-10T14:30:00"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（必須フィールド不足、日付形式不正等）|
| 403 | 権限不足（MEMBER が作成を試みた場合）|
| 404 | `teamId` / `member_user_id` が存在しない |
| 422 | `member_user_id` がチームに所属していない |

#### `GET /api/v1/teams/{teamId}/service-records`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_SERVICE_RECORDS`）

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `member_user_id` | Long | No | 特定メンバーでフィルタ |
| `staff_user_id` | Long | No | 担当スタッフでフィルタ |
| `service_date_from` | Date | No | 期間開始日 |
| `service_date_to` | Date | No | 期間終了日 |
| `title_like` | String | No | タイトル部分一致検索 |
| `page` | Integer | No | ページ番号（0始まり、デフォルト 0） |
| `size` | Integer | No | 件数（デフォルト 20、最大 100） |
| `sort` | String | No | ソート（デフォルト `serviceDate,desc`） |

**レスポンス（200 OK）**: `PagedResponse<ServiceRecordResponse>`

#### `GET /api/v1/service-records/me`

**認可**: MEMBER 以上

ログインユーザー自身のサービス履歴を全チーム横断で取得する。チームごとにグルーピングされた結果を返す。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | No | 特定チームでフィルタ |
| `page` | Integer | No | ページ番号（0始まり） |
| `size` | Integer | No | 件数（デフォルト 20） |

#### `POST /api/v1/teams/{teamId}/service-record-fields`

**認可**: ADMIN のみ

**リクエストボディ**
```json
{
  "field_name": "施術内容",
  "field_type": "SELECT",
  "options": ["カット", "カラー", "パーマ", "トリートメント"],
  "is_required": true,
  "sort_order": 1
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 1,
    "field_name": "施術内容",
    "field_type": "SELECT",
    "options": ["カット", "カラー", "パーマ", "トリートメント"],
    "is_required": true,
    "sort_order": 1
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 409 | カスタムフィールド上限（20件）超過 |

#### `PUT /api/v1/teams/{teamId}/service-records/settings`

**認可**: ADMIN のみ

**リクエストボディ**
```json
{
  "is_dashboard_enabled": true,
  "is_reaction_enabled": true
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "team_id": 10,
    "is_dashboard_enabled": true,
    "is_reaction_enabled": true
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足 |

#### `POST /api/v1/teams/{teamId}/service-records/{id}/reactions`

**認可**: MEMBER 以上（自分の記録のみ）

メンバーが自分のサービス記録にリアクションを付ける。同じ記録に再度リクエストすると `reaction_type` を更新する。

**リクエストボディ**
```json
{
  "reaction_type": "LIKE"
}
```

**レスポンス（201 Created / 200 OK）**
```json
{
  "data": {
    "service_record_id": 123,
    "reaction_type": "LIKE",
    "created_at": "2026-03-10T18:00:00"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ダッシュボード共有が無効 / リアクション機能が無効 / 他人の記録 |
| 404 | 記録が存在しない |

#### `DELETE /api/v1/teams/{teamId}/service-records/{id}/reactions`

**認可**: MEMBER 以上（自分のリアクションのみ）

**レスポンス（204 No Content）**

#### `PATCH /api/v1/teams/{teamId}/service-record-fields/sort-order`

**認可**: ADMIN のみ

カスタムフィールドの並び順を一括更新する。フロントエンドのドラッグ＆ドロップ並び替え UI と連携。

**リクエストボディ**
```json
{
  "field_orders": [
    { "field_id": 3, "sort_order": 0 },
    { "field_id": 1, "sort_order": 1 },
    { "field_id": 2, "sort_order": 2 }
  ]
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "updated_count": 3
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `field_orders` が空 / `sort_order` に重複がある |
| 403 | 権限不足 |
| 404 | `field_id` がチームに存在しない |

#### `POST /api/v1/teams/{teamId}/service-records/templates`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_SERVICE_RECORDS`）

**リクエストボディ**
```json
{
  "name": "カット＋カラー",
  "title_template": "カット＋カラー",
  "note_template": "カラー: \nトーン: \n備考: ",
  "default_duration_minutes": 90,
  "sort_order": 1,
  "custom_field_values": [
    { "field_id": 1, "default_value": "カラー" }
  ]
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 1,
    "name": "カット＋カラー",
    "title_template": "カット＋カラー",
    "note_template": "カラー: \nトーン: \n備考: ",
    "default_duration_minutes": 90,
    "sort_order": 1,
    "custom_field_values": [
      { "field_id": 1, "field_name": "施術内容", "default_value": "カラー" }
    ]
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 409 | テンプレート上限超過（デフォルト10件。プランにより異なる）|

#### `GET /api/v1/teams/{teamId}/service-records/templates`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_SERVICE_RECORDS`）

チームテンプレートと、そのチームが所属する組織の共有テンプレートを統合して返す。組織テンプレートが先、チームテンプレートが後の順序。

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 10,
      "name": "基本カウンセリング",
      "title_template": "カウンセリング",
      "default_duration_minutes": 30,
      "sort_order": 0,
      "scope": "ORGANIZATION",
      "organization_id": 5
    },
    {
      "id": 1,
      "name": "カット＋カラー",
      "title_template": "カット＋カラー",
      "default_duration_minutes": 90,
      "sort_order": 1,
      "scope": "TEAM",
      "team_id": 10
    },
    {
      "id": 2,
      "name": "パーマ",
      "title_template": "パーマ",
      "default_duration_minutes": 120,
      "sort_order": 2,
      "scope": "TEAM",
      "team_id": 10
    }
  ]
}
```

#### `GET /api/v1/teams/{teamId}/service-records/export`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_SERVICE_RECORDS`）

サービス記録を CSV 形式でエクスポートする。カスタムフィールドは動的にカラムとして展開される。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `member_user_id` | Long | No | 特定メンバーでフィルタ |
| `service_date_from` | Date | No | 期間開始日 |
| `service_date_to` | Date | No | 期間終了日 |

**レスポンス**: `Content-Type: text/csv; charset=UTF-8`（BOM 付き UTF-8。Excel 文字化け防止）

```csv
ID,メンバー名,担当スタッフ,サービス日,タイトル,所要時間(分),施術内容,使用薬剤,備考
123,田中花子,鈴木太郎,2026-03-10,カット＋カラー,90,カラー,○○系8トーン,前回より暗めのトーン
```

**処理方式**
- **1,000件以下**: ストリーミングレスポンスで即時返却
- **1,000件超**: 非同期ジョブとして実行し、完了後にダウンロードリンクをアプリ内通知で送信。レスポンスは `202 Accepted` + ジョブ ID を返す。ダウンロードリンクは S3 Pre-signed URL（有効期限: 24時間）。S3 ライフサイクルルールで 7日後に自動削除

```json
{
  "data": {
    "job_id": "export_abc123",
    "status": "PROCESSING",
    "message": "エクスポートを開始しました。完了後に通知でお知らせします。"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足 |

---

## 5. ビジネスロジック

### 主要フロー

#### サービス記録作成（テンプレート利用可）
```
1. ADMIN/DEPUTY_ADMIN が記録作成画面を表示
2. テンプレート選択ドロップダウンを表示（テンプレートがある場合）
3. テンプレートを選択すると、title / note / duration / カスタムフィールドにデフォルト値をプリフィル
4. スタッフがフォームを編集・確定して送信
5. バックエンドがチーム所属・権限を検証
6. member_user_id のチーム所属を検証
7. カスタムフィールドの必須チェック・型バリデーション
8. service_records + service_record_values をトランザクション内で INSERT
9. ApplicationEvent（ServiceRecordCreatedEvent）を発行
10. is_dashboard_enabled = true の場合、メンバーへ「新しいサービス記録が追加されました」通知を送信
```

#### 個人ダッシュボード連携（許可制）
```
1. メンバーがダッシュボードを表示
2. 各チームの service_record_settings.is_dashboard_enabled を確認
3. 許可されたチームのみ /service-records/me API でサービス履歴を取得
4. チームごとにグルーピングして表示
5. is_reaction_enabled = true のチームでは、各記録にリアクションボタンを表示
```

#### リアクション操作
```
1. メンバーがダッシュボードの記録カードで「いいね！」等のボタンを押下
2. バックエンドが is_dashboard_enabled && is_reaction_enabled を検証
3. service_records.member_user_id = currentUser.id を検証（自分の記録のみ）
4. 既存リアクションがあれば更新、なければ INSERT（トグル削除は DELETE API）
5. ApplicationEvent（ServiceRecordReactedEvent）を発行
6. 担当スタッフ（service_records.staff_user_id）へアプリ内通知を送信
   「○○さんが『カット＋カラー（3/10）』にいいね！しました」
   ※ プッシュ通知は送らない（アプリ内通知のみ。低優先度の情報のためプッシュは過剰）
```

#### テンプレート利用フロー（組織共有テンプレート含む）
```
1. ADMIN/DEPUTY_ADMIN が記録作成画面を表示
2. テンプレート選択ドロップダウンに以下を統合表示:
   - 組織テンプレート（organization_id で取得。ラベル: [組織共通]）
   - チームテンプレート（team_id で取得）
3. テンプレートを選択 → フォームにプリフィル
4. 以降は通常の記録作成フロー
```

### 重要な判定ロジック
- **ダッシュボード表示判定**: `service_record_settings.is_dashboard_enabled = true` のチームのみ、MEMBER の `/service-records/me` API レスポンスに含める。`false` のチームは 空配列ではなく結果から除外（チーム存在自体を秘匿する必要はないが、記録が見えてはならない）
- **リアクション可否判定**: `is_dashboard_enabled = true` かつ `is_reaction_enabled = true` の場合のみ。API は両条件を検証し、不一致なら 403 を返す
- **カスタムフィールド型バリデーション**: NUMBER 型は数値変換可能か、DATE 型は日付形式か、SELECT 型は `options` に含まれるか
- **カスタムフィールド無効化**: フィールド無効化時は確認ダイアログで「このフィールドは新規記録で非表示になります。既存の〇件の記録に保存された値は保持されます」と表示（フロントエンド実装）
- **テンプレート適用**: テンプレート選択はフロントエンド側でプリフィルするのみ。バックエンドの記録作成 API はテンプレートの存在を意識しない（テンプレート ID の送信不要）
- **メンバー閲覧制限**: MEMBER は自分の `member_user_id` に一致するレコードのみ閲覧可能

---

## 6. セキュリティ考慮事項

- **認可チェック**: `ServiceRecordService` の入り口で `teamId` と `currentUser` の所属・ロールを必ず検証する
- **メンバーデータ分離**: MEMBER が他メンバーの履歴を参照できないよう、`/service-records/me` は必ず `currentUser.id` でフィルタする
- **レートリミット**: 記録作成 API に `Bucket4j` で 1分間に30回の制限を適用（大量一括登録防止）
- **XSS 対策**: `title`, `note`, カスタムフィールドの TEXT 型値はレスポンス時にサニタイズする
- **CSV インジェクション対策**: CSV エクスポート時、セル値の先頭が `=`, `+`, `-`, `@` の場合はシングルクォートを先頭に付与する

---

## 7. Flywayマイグレーション

```
V7.001__create_service_records_table.sql
V7.002__create_service_record_fields_table.sql
V7.003__create_service_record_values_table.sql
V7.004__create_service_record_settings_table.sql
V7.005__create_service_record_reactions_table.sql
V7.006__create_service_record_templates_table.sql
V7.007__create_service_record_template_values_table.sql
```

**マイグレーション上の注意点**
- `service_records` は `teams`・`users` テーブルへの FK を持つため、Phase 1〜2 のマイグレーションが先行完了していること
- `service_record_values` は `service_records`・`service_record_fields` への FK を持つため、V7.001・V7.002 の後に実行
- `service_record_template_values` は `service_record_templates`・`service_record_fields` への FK を持つため、V7.002・V7.006 の後に実行

---

## 8. 未解決事項

- [x] ~~カスタムフィールドの `CHECKBOX` 型の値保存形式~~ → `"true"/"false"` に確定（JSON 親和性・可読性・NUMBER 型との混同防止）
- [x] ~~サービス記録の CSV エクスポート機能を Phase 7 で実装するか~~ → Phase 7 で実装。1,000件以下は即時ストリーミング、超過時は非同期ジョブ + 通知
- [x] ~~`service_record_fields` の並び替え API~~ → `PATCH /service-record-fields/sort-order` で一括更新。ドラッグ＆ドロップ UI 対応
- [x] ~~リアクション通知をスタッフに送るか~~ → 担当スタッフにアプリ内通知のみ送信（プッシュ通知は過剰なため不採用）
- [x] ~~テンプレートの組織レベル共有の要否~~ → 対応する。`service_record_templates` に `organization_id`（XOR）を追加。組織 ADMIN が作成し配下の全チームで利用可能

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-10 | 初版作成 |
| 2026-03-10 | ダッシュボード反映をチーム許可制に変更、リアクション機能追加、テンプレート機能追加 |
| 2026-03-11 | フィールド並び替えAPI追加、リアクション通知をアプリ内通知に確定、テンプレート組織レベル共有対応 |
| 2026-03-11 | 精査: XSS 対策を title/note にも拡大、CSV インジェクション対策追加、CSV エクスポート非同期ジョブのダウンロード方法明確化 |
| 2026-03-11 | `service_record_fields` に `is_active` カラム追加。物理削除 → 論理無効化に変更。FK を ON DELETE CASCADE → RESTRICT に変更。既存記録データの保護を優先 |
| 2026-03-11 | 精査②: ページネーションを cursor/limit に統一（README 準拠）、対象レベルに Organization 追記（テンプレート共有）、sort_order 型を INT に統一 |
