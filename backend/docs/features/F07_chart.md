# F07: カルテ

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 7
> **最終更新**: 2026-03-11
> **モジュール種別**: 選択式モジュール #11（**有料プラン必須**）

---

## 1. 概要

来店ごとにカルテを作成・蓄積し、顧客の施術履歴を体系的に管理する機能。美容室のカラーレシピ、整骨院の身体チャート、エステのビフォーアフター写真など業種ごとに必要なセクションを ON/OFF で切り替えて使用できる。問診票・同意書は電子印鑑（デフォルト機能 #22）と連携し、施術前の確認フローをデジタル化する。カルテ写真は医療・美容記録のため CloudFront 署名付き URL でのみアクセス可能とし、公開 URL は使用しない。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全チームのカルテデータを参照 |
| ADMIN | カルテの作成・編集・削除、セクション設定、カスタムフィールド定義、全顧客のカルテ閲覧、顧客への共有設定 |
| DEPUTY_ADMIN | `MANAGE_CHARTS` 権限を持つ場合: カルテの作成・編集・閲覧、顧客への共有設定 |
| MEMBER | 自分のカルテのうち施術者が共有設定したもののみ閲覧 |
| SUPPORTER | 対象外 |
| GUEST | 対象外 |

### 対象レベル
- [ ] 組織 (Organization)
- [x] チーム (Team) — カルテ作成・管理
- [x] 個人 (Personal) — 自分のカルテのうち共有されたものを閲覧

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `chart_records` | カルテ本体 | あり |
| `chart_intake_forms` | 問診票・同意書 | なし |
| `chart_intake_form_templates` | チームごとの問診票テンプレート定義 | なし |
| `chart_photos` | ビフォーアフター写真 | なし |
| `chart_body_marks` | 身体チャートのマーク情報（整骨院向け）| なし |
| `chart_formulas` | カラー・薬剤レシピ（美容室向け）| なし |
| `chart_section_settings` | チームごとのセクション ON/OFF 設定 | なし |
| `chart_custom_fields` | カスタム項目定義 | なし |
| `chart_custom_values` | カスタム項目の値 | なし |

### テーブル定義

#### `chart_records`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `customer_user_id` | BIGINT UNSIGNED | NO | — | FK → users（顧客 = 施術を受けたメンバー）|
| `staff_user_id` | BIGINT UNSIGNED | YES | NULL | FK → users（担当スタッフ; SET NULL on delete）|
| `visit_date` | DATE | NO | — | 来店日 |
| `chief_complaint` | TEXT | YES | NULL | 主訴・要望 |
| `treatment_note` | TEXT | YES | NULL | 施術内容・メモ |
| `next_recommendation` | TEXT | YES | NULL | 次回推奨メモ |
| `allergy_info` | TEXT | YES | NULL | アレルギー・禁忌情報 |
| `is_shared_to_customer` | BOOLEAN | NO | FALSE | 顧客へ共有するか |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_cr_team_id (team_id)
INDEX idx_cr_customer (customer_user_id)                     -- 顧客のカルテ一覧
INDEX idx_cr_team_customer (team_id, customer_user_id)       -- チーム内の特定顧客
INDEX idx_cr_visit_date (team_id, visit_date DESC)           -- 日付順一覧
INDEX idx_cr_staff (staff_user_id)                           -- スタッフ別一覧
```

**制約・備考**
- 論理削除: `deleted_at DATETIME nullable`（SoftDeletableEntity 適用）
- `customer_user_id` ON DELETE RESTRICT（顧客データの保全）
- `allergy_info` は `is_enabled = true`（ALLERGY セクション）の場合のみ使用。有効時はカルテ作成画面の最上部に赤枠で目立つ警告を表示する（フロントエンド実装）
- `is_shared_to_customer = true` の場合、顧客はダッシュボードから閲覧可能。`false` の場合はスタッフのみ閲覧可能

---

#### `chart_intake_forms`

問診票・同意書。電子印鑑と連携して署名を記録する。問診票の質問項目はチームごとにカスタマイズ可能（`chart_intake_form_templates` で定義）。業種ごとに必要な質問が異なるため（美容室: 髪質・アレルギー歴、整骨院: 既往歴・服薬情報 等）、各チームが JSON で独自の質問テンプレートを定義する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `chart_record_id` | BIGINT UNSIGNED | NO | — | FK → chart_records（ON DELETE CASCADE）|
| `form_type` | ENUM('INTAKE', 'CONSENT') | NO | — | 種別（問診票 / 同意書）|
| `content` | JSON | NO | — | フォーム内容（質問と回答の構造化データ）|
| `electronic_seal_id` | BIGINT UNSIGNED | YES | NULL | FK → electronic_seals（電子印鑑 ID; 署名時に設定）|
| `signed_at` | DATETIME | YES | NULL | 署名日時 |
| `is_initial` | BOOLEAN | NO | TRUE | 初回問診か（FALSE = 毎回更新型）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_cif_chart (chart_record_id)
INDEX idx_cif_type (chart_record_id, form_type)
```

**制約・備考**
- `content` は JSON 形式で質問・回答を保存。質問項目は `chart_intake_form_templates.template_json` から取得し、回答を付与して保存する。スキーマ例:
  ```json
  {
    "questions": [
      { "q": "現在服用中の薬はありますか？", "type": "TEXT", "a": "なし" },
      { "q": "過去にアレルギー反応が出た薬剤はありますか？", "type": "TEXT", "a": "○○系の薬剤" },
      { "q": "施術部位の希望", "type": "SELECT", "options": ["全体", "部分"], "a": "全体" }
    ]
  }
  ```
- `electronic_seal_id` は `electronic_seals` テーブル（デフォルト機能 #22: 電子印鑑）への FK。Phase 1 で作成済み前提
- 初回問診（`is_initial = true`）は顧客の最初のカルテに1回のみ。以降のカルテでは `is_initial = false` の更新型問診のみ

---

#### `chart_intake_form_templates`

チームごとの問診票質問テンプレート定義。各チームが業種に合わせた質問項目を JSON で定義する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `form_type` | ENUM('INTAKE', 'CONSENT') | NO | — | 種別（問診票 / 同意書）|
| `template_name` | VARCHAR(100) | NO | — | テンプレート名（例: 初回カウンセリングシート）|
| `template_json` | JSON | NO | — | 質問定義（下記スキーマ参照）|
| `is_default` | BOOLEAN | NO | FALSE | チームのデフォルトテンプレートか |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_cift_team (team_id)
INDEX idx_cift_team_type (team_id, form_type)
```

**制約・備考**
- `template_json` のスキーマ例:
  ```json
  {
    "questions": [
      { "q": "現在服用中の薬はありますか？", "type": "TEXT", "required": true },
      { "q": "過去にアレルギー反応が出た薬剤はありますか？", "type": "TEXT", "required": true },
      { "q": "施術部位の希望", "type": "SELECT", "options": ["全体", "部分"], "required": false }
    ]
  }
  ```
- `type` は `TEXT`（自由記述）、`SELECT`（単一選択）、`CHECKBOX`（複数選択）、`YES_NO`（はい/いいえ）の4種
- `is_default = true` はチーム×`form_type` あたり最大1件（アプリ層で検証）。カルテ作成時に `is_default = true` のテンプレートが自動選択される
- 1チームあたりのテンプレート上限: **10件**（アプリ層で検証）
- テンプレート未作成の場合、問診票セクションは空のフリーテキスト入力にフォールバック
- テンプレートの削除: 物理削除。既存の `chart_intake_forms.content` にはテンプレート適用後の質問＋回答がスナップショットとして保存済みのため、テンプレート削除の影響を受けない

---

#### `chart_photos`

ビフォーアフター写真。S3 に保存し CloudFront 署名付き URL でのみアクセス可能。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `chart_record_id` | BIGINT UNSIGNED | NO | — | FK → chart_records（ON DELETE CASCADE）|
| `photo_type` | ENUM('BEFORE', 'AFTER') | NO | — | 写真種別 |
| `s3_key` | VARCHAR(500) | NO | — | S3 オブジェクトキー |
| `original_filename` | VARCHAR(300) | NO | — | アップロード時のファイル名 |
| `file_size_bytes` | INT UNSIGNED | NO | — | ファイルサイズ（バイト）|
| `content_type` | VARCHAR(50) | NO | — | MIME タイプ（image/jpeg 等）|
| `sort_order` | INT | NO | 0 | 表示順 |
| `is_shared_to_customer` | BOOLEAN | NO | FALSE | 顧客に個別共有するか |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_cp_chart (chart_record_id)
INDEX idx_cp_chart_type (chart_record_id, photo_type)
```

**制約・備考**
- 1カルテあたり最大 **20枚**（推奨上限。アプリ層で検証）
- 写真は S3 に保存し、CloudFront **署名付き URL（Signed URL）** でのみアクセス可能にする（医療・美容記録のため公開 URL は使用しない）
- 署名付き URL の有効期限: **15分**（API レスポンス時に都度生成）
- カルテ論理削除時、写真の S3 オブジェクトは即座に削除しない。物理削除バッチで `chart_records` 物理削除時に S3 オブジェクトも併せて削除する
- `is_shared_to_customer`: カルテ全体の `is_shared_to_customer` とは独立。写真単位で共有を制御（例: AFTER のみ顧客に公開、BEFORE は非公開）

---

#### `chart_body_marks`

身体チャートのマーク情報。整骨院・整体院向けに、人体図上の施術箇所をマーキングする。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `chart_record_id` | BIGINT UNSIGNED | NO | — | FK → chart_records（ON DELETE CASCADE）|
| `body_part` | ENUM('FRONT', 'BACK', 'LEFT', 'RIGHT', 'HEAD', 'HAND_LEFT', 'HAND_RIGHT', 'FOOT_LEFT', 'FOOT_RIGHT') | NO | — | 身体面 |
| `x_position` | DECIMAL(5,2) | NO | — | X 座標（0.00〜100.00、人体図上の相対位置 %）|
| `y_position` | DECIMAL(5,2) | NO | — | Y 座標（0.00〜100.00）|
| `mark_type` | ENUM('PAIN', 'NUMBNESS', 'STIFFNESS', 'SWELLING', 'OTHER') | NO | — | マーク種別 |
| `severity` | TINYINT UNSIGNED | NO | — | 重症度（1〜5）|
| `note` | VARCHAR(300) | YES | NULL | マーク個別メモ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_cbm_chart (chart_record_id)
```

**制約・備考**
- 座標は人体図の相対位置（%）で保存。フロントエンドで人体シルエット画像にオーバーレイ表示
- `severity` CHECK 制約: `severity BETWEEN 1 AND 5`
- 1カルテあたりのマーク上限: **50件**（アプリ層で検証）

---

#### `chart_formulas`

カラー・薬剤レシピ。美容室向けに、使用した薬剤の配合情報を記録する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `chart_record_id` | BIGINT UNSIGNED | NO | — | FK → chart_records（ON DELETE CASCADE）|
| `product_name` | VARCHAR(200) | NO | — | 薬剤・製品名 |
| `ratio` | VARCHAR(100) | YES | NULL | 配合比率（例: 1:1.5）|
| `processing_time_minutes` | SMALLINT UNSIGNED | YES | NULL | 放置時間（分）|
| `temperature` | VARCHAR(50) | YES | NULL | 加温条件（例: 自然放置、40℃加温）|
| `patch_test_date` | DATE | YES | NULL | パッチテスト実施日 |
| `patch_test_result` | ENUM('POSITIVE', 'NEGATIVE', 'NOT_DONE') | YES | NULL | パッチテスト結果 |
| `note` | VARCHAR(500) | YES | NULL | 備考 |
| `sort_order` | INT | NO | 0 | 表示順 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_cf_chart (chart_record_id)
```

**制約・備考**
- パッチテスト結果が `POSITIVE` の場合、次回以降のカルテ作成時にフロントエンドで警告表示する（`chart_records.allergy_info` と連携）
- 1カルテあたりの薬剤レシピ上限: **20件**（アプリ層で検証）

---

#### `chart_section_settings`

チームごとのカルテセクション ON/OFF 設定。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `section_type` | ENUM('INTAKE_FORM', 'ALLERGY', 'PHOTOS', 'STAFF', 'BODY_CHART', 'FORMULA', 'PATCH_TEST', 'PROGRESS_GRAPH', 'NEXT_MEMO') | NO | — | セクション種別 |
| `is_enabled` | BOOLEAN | NO | TRUE | 有効/無効 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_css_team_section (team_id, section_type)   -- チーム×セクションで一意
```

**制約・備考**
- チーム初回設定時に全セクションのレコードを一括 INSERT する（デフォルトは全て `is_enabled = true`）
- ADMIN のみ変更可能
- セクションを無効にしても既存データは保持される（表示のみ非表示化）

---

#### `chart_custom_fields`

チームごとのカスタム項目定義。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `field_name` | VARCHAR(100) | NO | — | フィールド名 |
| `field_type` | ENUM('TEXT', 'NUMBER', 'DATE', 'SELECT', 'CHECKBOX') | NO | — | フィールド型 |
| `options` | JSON | YES | NULL | SELECT 型の選択肢リスト |
| `sort_order` | INT | NO | 0 | 表示順 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_ccf_team_sort (team_id, sort_order)
```

**制約・備考**
- 1チームにつき最大 **5件**（README.md の仕様に準拠。アプリ層で検証）
- フィールド削除時: `chart_custom_values` の対応レコードもカスケード削除

---

#### `chart_custom_values`

各カルテのカスタム項目値。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `chart_record_id` | BIGINT UNSIGNED | NO | — | FK → chart_records（ON DELETE CASCADE）|
| `field_id` | BIGINT UNSIGNED | NO | — | FK → chart_custom_fields（ON DELETE CASCADE）|
| `value` | TEXT | YES | NULL | フィールド値 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_ccv_chart_field (chart_record_id, field_id)
```

### ER図（テキスト形式）
```
teams (1) ──── (N) chart_records
teams (1) ──── (N) chart_intake_form_templates
teams (1) ──── (N) chart_section_settings
teams (1) ──── (N) chart_custom_fields
users (1) ──── (N) chart_records [customer_user_id]
users (1) ──── (N) chart_records [staff_user_id]

chart_records (1) ──── (N) chart_intake_forms
chart_records (1) ──── (N) chart_photos
chart_records (1) ──── (N) chart_body_marks
chart_records (1) ──── (N) chart_formulas
chart_records (1) ──── (N) chart_custom_values

chart_custom_fields (1) ──── (N) chart_custom_values

electronic_seals (1) ──── (N) chart_intake_forms [electronic_seal_id]
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/teams/{teamId}/charts` | 必要 | カルテ一覧取得 |
| POST | `/api/v1/teams/{teamId}/charts` | 必要 | カルテ作成 |
| GET | `/api/v1/teams/{teamId}/charts/{id}` | 必要 | カルテ詳細取得 |
| PUT | `/api/v1/teams/{teamId}/charts/{id}` | 必要 | カルテ更新 |
| DELETE | `/api/v1/teams/{teamId}/charts/{id}` | 必要 | カルテ削除（論理削除）|
| POST | `/api/v1/teams/{teamId}/charts/{id}/photos` | 必要 | 写真アップロード |
| DELETE | `/api/v1/teams/{teamId}/charts/photos/{photoId}` | 必要 | 写真削除 |
| GET | `/api/v1/teams/{teamId}/charts/{id}/intake-form` | 必要 | 問診票取得 |
| PUT | `/api/v1/teams/{teamId}/charts/{id}/intake-form` | 必要 | 問診票更新 |
| PUT | `/api/v1/teams/{teamId}/charts/{id}/body-marks` | 必要 | 身体チャート一括更新 |
| GET | `/api/v1/teams/{teamId}/charts/{id}/formulas` | 必要 | 薬剤レシピ一覧取得 |
| POST | `/api/v1/teams/{teamId}/charts/{id}/formulas` | 必要 | 薬剤レシピ追加 |
| PUT | `/api/v1/teams/{teamId}/charts/formulas/{formulaId}` | 必要 | 薬剤レシピ更新 |
| DELETE | `/api/v1/teams/{teamId}/charts/formulas/{formulaId}` | 必要 | 薬剤レシピ削除 |
| POST | `/api/v1/teams/{teamId}/charts/{id}/copy` | 必要 | カルテコピー（前回ベースで新規作成）|
| GET | `/api/v1/teams/{teamId}/charts/{id}/pdf` | 必要 | カルテ PDF エクスポート |
| PATCH | `/api/v1/teams/{teamId}/charts/{id}/share` | 必要 | 顧客共有設定の変更 |
| GET | `/api/v1/teams/{teamId}/charts/customer/{userId}` | 必要 | 特定顧客の全カルテ一覧 |
| GET | `/api/v1/charts/me` | 必要 | 自分のカルテ（共有されたもの）|
| GET | `/api/v1/teams/{teamId}/charts/settings/sections` | 必要 | セクション設定取得 |
| PUT | `/api/v1/teams/{teamId}/charts/settings/sections` | 必要 | セクション設定更新 |
| GET | `/api/v1/teams/{teamId}/charts/settings/custom-fields` | 必要 | カスタムフィールド一覧 |
| POST | `/api/v1/teams/{teamId}/charts/settings/custom-fields` | 必要 | カスタムフィールド作成 |
| PUT | `/api/v1/teams/{teamId}/charts/settings/custom-fields/{id}` | 必要 | カスタムフィールド更新 |
| DELETE | `/api/v1/teams/{teamId}/charts/settings/custom-fields/{id}` | 必要 | カスタムフィールド削除 |

### リクエスト／レスポンス仕様

#### `GET /api/v1/teams/{teamId}/charts`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_CHARTS`）

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `customer_user_id` | Long | No | 特定顧客でフィルタ |
| `staff_user_id` | Long | No | 担当スタッフでフィルタ |
| `visit_date_from` | Date | No | 期間開始日 |
| `visit_date_to` | Date | No | 期間終了日 |
| `is_shared_to_customer` | Boolean | No | 顧客共有状態でフィルタ |
| `page` | Integer | No | ページ番号（0始まり、デフォルト 0） |
| `size` | Integer | No | 件数（デフォルト 20、最大 100） |
| `sort` | String | No | ソート（デフォルト `visitDate,desc`） |

**レスポンス（200 OK）**: `PagedResponse<ChartRecordSummaryResponse>`

#### `POST /api/v1/teams/{teamId}/charts`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_CHARTS`）

**リクエストボディ**
```json
{
  "customer_user_id": 42,
  "staff_user_id": 5,
  "visit_date": "2026-03-10",
  "chief_complaint": "毛先の傷みが気になる。カラーは前回と同じトーン希望",
  "treatment_note": "カット＋カラー。毛先5cm カット、カラー 8トーン アッシュブラウン",
  "next_recommendation": "次回は3週間後を推奨。トリートメント追加を提案",
  "allergy_info": null,
  "is_shared_to_customer": false,
  "custom_fields": [
    { "field_id": 1, "value": "カット＋カラー" }
  ]
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 456,
    "team_id": 10,
    "customer_user_id": 42,
    "customer_display_name": "田中花子",
    "staff_user_id": 5,
    "staff_display_name": "鈴木太郎",
    "visit_date": "2026-03-10",
    "chief_complaint": "毛先の傷みが気になる。カラーは前回と同じトーン希望",
    "treatment_note": "カット＋カラー。毛先5cm カット、カラー 8トーン アッシュブラウン",
    "next_recommendation": "次回は3週間後を推奨。トリートメント追加を提案",
    "allergy_info": null,
    "is_shared_to_customer": false,
    "sections_enabled": {
      "INTAKE_FORM": true,
      "ALLERGY": true,
      "PHOTOS": true,
      "STAFF": true,
      "BODY_CHART": false,
      "FORMULA": true,
      "PATCH_TEST": true,
      "PROGRESS_GRAPH": false,
      "NEXT_MEMO": true
    },
    "custom_fields": [
      { "field_id": 1, "field_name": "施術カテゴリ", "value": "カット＋カラー" }
    ],
    "photos": [],
    "formulas": [],
    "body_marks": [],
    "created_at": "2026-03-10T14:30:00"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 404 | `teamId` / `customer_user_id` が存在しない |
| 422 | `customer_user_id` がチームに所属していない |

#### `POST /api/v1/teams/{teamId}/charts/{id}/photos`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_CHARTS`）

**リクエスト**: `multipart/form-data`
| フィールド | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `file` | File | Yes | 画像ファイル（JPEG/PNG/WebP、最大 10MB）|
| `photo_type` | String | Yes | `BEFORE` or `AFTER` |
| `is_shared_to_customer` | Boolean | No | 顧客共有（デフォルト false）|

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 789,
    "photo_type": "BEFORE",
    "signed_url": "https://cdn.example.com/charts/...",
    "signed_url_expires_at": "2026-03-10T14:45:00",
    "original_filename": "before_shot.jpg",
    "file_size_bytes": 2048576,
    "is_shared_to_customer": false
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | ファイル形式不正・サイズ超過 |
| 403 | 権限不足 |
| 404 | カルテが存在しない |
| 409 | 写真枚数上限（20枚）超過 |

#### `PATCH /api/v1/teams/{teamId}/charts/{id}/share`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_CHARTS`）

**リクエストボディ**
```json
{
  "is_shared_to_customer": true
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 456,
    "is_shared_to_customer": true
  }
}
```

#### `GET /api/v1/teams/{teamId}/charts/{id}/pdf`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_CHARTS`）

カルテの内容を PDF としてエクスポートする。有効なセクションのみ含む。

**レスポンス**: `Content-Type: application/pdf`（バイナリストリーム）

#### `PUT /api/v1/teams/{teamId}/charts/{id}/body-marks`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_CHARTS`）

身体チャートのマークを一括更新（全件置換）する。

**リクエストボディ**
```json
{
  "marks": [
    {
      "body_part": "BACK",
      "x_position": 50.00,
      "y_position": 30.50,
      "mark_type": "PAIN",
      "severity": 4,
      "note": "腰痛（L4-L5 付近）"
    },
    {
      "body_part": "FRONT",
      "x_position": 45.00,
      "y_position": 60.00,
      "mark_type": "STIFFNESS",
      "severity": 2,
      "note": "右膝の張り"
    }
  ]
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "chart_record_id": 456,
    "marks_count": 2,
    "marks": [...]
  }
}
```

#### `POST /api/v1/teams/{teamId}/charts/{id}/copy`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_CHARTS`）

指定カルテをベースに新規カルテを作成する。前回の施術情報をデフォルト値として引き継ぎ、入力の手間を削減する。

**コピー対象フィールド**
- `allergy_info`（アレルギー情報）
- `chart_formulas`（薬剤レシピ全件）
- `chart_custom_values`（カスタムフィールド値全件）
- `chief_complaint`（主訴 — 参考情報としてコピー。編集前提）

**コピーしないフィールド**
- `visit_date`（リクエストで指定、デフォルトは当日）
- `treatment_note` / `next_recommendation`（新規入力）
- `chart_photos` / `chart_body_marks` / `chart_intake_forms`（施術固有のため引き継がない）
- `is_shared_to_customer`（デフォルト `false` でリセット）

**リクエストボディ**
```json
{
  "visit_date": "2026-03-11",
  "staff_user_id": 5
}
```

**備考**
- `customer_user_id` はコピー元カルテから自動継承（リクエストで指定不要）
- コピー元とコピー先は同一チーム・同一顧客であること（異なる場合は 400）

**レスポンス（201 Created）**: カルテ作成と同一のレスポンス形式（`ChartRecordResponse`）。コピー元のデフォルト値が設定された状態で返却。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足 |
| 404 | コピー元カルテが存在しない |

---

#### `GET /api/v1/charts/me`

**認可**: MEMBER 以上

自分に共有されたカルテを全チーム横断で取得する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | No | 特定チームでフィルタ |
| `page` | Integer | No | ページ番号（0始まり） |
| `size` | Integer | No | 件数（デフォルト 20） |

**レスポンス（200 OK）**: `PagedResponse<ChartRecordSummaryResponse>`
- `is_shared_to_customer = true` のレコードのみ返却
- 写真は `is_shared_to_customer = true` のもののみ含む

#### `GET /api/v1/teams/{teamId}/charts/customer/{userId}`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_CHARTS`）

特定顧客の全カルテを一覧取得する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `page` | Integer | No | ページ番号（0始まり、デフォルト 0） |
| `size` | Integer | No | 件数（デフォルト 20、最大 100） |

**レスポンス（200 OK）**: `PagedResponse<ChartRecordSummaryResponse>`

#### `PUT /api/v1/teams/{teamId}/charts/settings/sections`

**認可**: ADMIN のみ

**リクエストボディ**
```json
{
  "sections": [
    { "section_type": "INTAKE_FORM", "is_enabled": true },
    { "section_type": "ALLERGY", "is_enabled": true },
    { "section_type": "PHOTOS", "is_enabled": true },
    { "section_type": "STAFF", "is_enabled": true },
    { "section_type": "BODY_CHART", "is_enabled": false },
    { "section_type": "FORMULA", "is_enabled": true },
    { "section_type": "PATCH_TEST", "is_enabled": true },
    { "section_type": "PROGRESS_GRAPH", "is_enabled": false },
    { "section_type": "NEXT_MEMO", "is_enabled": true }
  ]
}
```

---

## 5. ビジネスロジック

### 主要フロー

#### カルテ作成
```
1. ADMIN/DEPUTY_ADMIN がカルテ作成フォームを入力
2. バックエンドがチーム所属・権限・モジュール有効化状態を検証
3. customer_user_id のチーム所属を検証
4. チームのセクション設定を取得し、無効セクションのデータが含まれていないか検証
5. chart_records + chart_custom_values をトランザクション内で INSERT
6. ApplicationEvent（ChartCreatedEvent）を発行
```

#### 写真アップロード
```
1. スタッフが写真を選択してアップロード
2. ファイルバリデーション（形式・サイズ・枚数上限）
3. EXIF メタデータ処理（サーバーサイド）:
   - GPS 情報（位置情報）を自動ストリップ（プライバシー保護）
   - 撮影日時（DateTimeOriginal）は保持する（タイムライン表示に有用）
   - Apache Commons Imaging または metadata-extractor ライブラリを使用
4. S3 にアップロード（キー: charts/{teamId}/{chartId}/{uuid}.{ext}）
5. chart_photos に INSERT
6. レスポンスに CloudFront 署名付き URL を含めて返却
```

#### 顧客カルテ閲覧
```
1. MEMBER がダッシュボードの「マイカルテ」を表示
2. /charts/me API で is_shared_to_customer = true のカルテを取得
3. 写真は is_shared_to_customer = true のもののみ表示
4. 署名付き URL を都度生成（TTL: 15分）
```

#### PDF エクスポート
```
1. スタッフが PDF エクスポートボタンを押下
2. バックエンドがカルテの全セクションデータを取得
3. 有効セクションのみ PDF に含める
4. 写真は S3 から一時取得して PDF に埋め込み
5. PDF バイナリストリームをレスポンスとして返却
```

#### カルテコピー
```
1. ADMIN/DEPUTY_ADMIN がコピー元カルテを選択してコピーを実行
2. コピー元カルテの存在・権限・チーム所属を検証
3. 新規 chart_records を INSERT（visit_date はリクエスト値、customer_user_id はコピー元を引き継ぎ）
4. コピー対象: allergy_info, chief_complaint をコピー元から設定
5. chart_formulas を全件コピー（新しい chart_record_id で INSERT）
6. chart_custom_values を全件コピー（新しい chart_record_id で INSERT）
7. トランザクション内で一括処理
8. ApplicationEvent（ChartCreatedEvent）を発行
```

#### 経過グラフ（PROGRESS_GRAPH）
```
1. PROGRESS_GRAPH セクションが有効なチームで、カルテ詳細画面に経過グラフを表示
2. X軸: 来店日（visit_date）、Y軸: NUMBER 型カスタムフィールドの値
3. 対象データ: 同一顧客の全カルテから NUMBER 型の chart_custom_values を時系列で取得
4. グラフライブラリ: Chart.js（フロントエンド描画）
5. 表示例: 体重推移、可動域角度の変化、痛みスコア（severity）の推移 等
6. 複数の NUMBER 型フィールドがある場合、フィールドごとに折れ線を重ねて表示（凡例付き）
7. バックエンドAPI: GET /api/v1/teams/{teamId}/charts/customer/{userId} のレスポンスに
   custom_fields の値を含めて返却。グラフ描画はフロントエンドで実施
```

### 重要な判定ロジック
- **アレルギー警告**: `allergy_info` が非 NULL かつ `ALLERGY` セクションが有効な場合、カルテ一覧・作成画面で赤枠の警告バナーを表示
- **パッチテスト警告**: 過去のカルテに `patch_test_result = 'POSITIVE'` のレコードがある場合、カルテ作成時に薬剤レシピセクションで警告表示
- **セクション制御**: 無効セクションの API エンドポイントは `404` ではなく `403`（`section_disabled`）を返す
- **顧客共有フィルタ**: MEMBER ロールのリクエストでは、DB クエリに `is_shared_to_customer = true` 条件を必ず付与する

---

## 6. セキュリティ考慮事項

- **認可チェック**: `ChartService` の入り口で `teamId` と `currentUser` の所属・ロール・モジュール有効化を検証
- **写真アクセス制御**: 写真の URL は CloudFront 署名付き URL（TTL: 15分）でのみ提供。S3 バケットのパブリックアクセスは完全にブロック
- **顧客データ分離**: MEMBER が他の顧客のカルテにアクセスできないよう、`/charts/me` は `currentUser.id == customer_user_id` かつ `is_shared_to_customer = true` で厳密にフィルタ
- **医療データの暗号化**: `allergy_info` は機微情報のため、Phase 10-11（セキュリティ監査フェーズ）で AES-256 カラム暗号化を導入予定。Phase 7 では平文保存とする（詳細は FUTURE_CONSIDERATIONS.md「Phase 10-11 開始前」セクションに記載）
- **EXIF GPS 自動ストリップ**: 写真アップロード時にサーバーサイドで GPS 情報を自動除去。撮影日時（DateTimeOriginal）はタイムライン用途のため保持する。クライアント側での除去に依存しない（サーバーで確実に処理）
- **レートリミット**: 写真アップロード API に `Bucket4j` で 1分間に20回の制限を適用
- **ファイルバリデーション**: アップロード画像は Content-Type ヘッダーだけでなく、Magic Bytes（ファイルシグネチャ）も検証して偽装を防止
- **PDF エクスポートの認可**: PDF にはカルテの全情報（共有設定に関わらず）が含まれるため、ADMIN / DEPUTY_ADMIN のみアクセス可能
- **XSS 対策**: `chief_complaint`, `treatment_note`, `next_recommendation`, `allergy_info`, カスタムフィールド TEXT 型値はレスポンス時にサニタイズする
- **PDF 生成のタイムアウト**: 写真が多い場合（最大20枚の S3 取得）を考慮し、PDF 生成のタイムアウトを30秒に設定。超過時は写真なし版を生成してエラーメッセージを付与する

---

## 7. Flywayマイグレーション

```
V7.012__create_chart_records_table.sql
V7.013__create_chart_intake_forms_table.sql
V7.014__create_chart_photos_table.sql
V7.015__create_chart_body_marks_table.sql
V7.016__create_chart_formulas_table.sql
V7.017__create_chart_section_settings_table.sql
V7.018__create_chart_custom_fields_table.sql
V7.019__create_chart_custom_values_table.sql
V7.020__create_chart_intake_form_templates_table.sql
```

**マイグレーション上の注意点**
- `chart_records` は `teams`・`users` テーブルへの FK を持つ
- `chart_intake_forms` は `electronic_seals` テーブル（Phase 1 で作成済み）への FK を持つ
- `chart_section_settings` のシードデータ: チーム初回利用時にアプリ層で INSERT するため、マイグレーションでのシードは不要
- `chart_custom_fields` / `chart_custom_values` は `service_record_fields` / `service_record_values` と同じ EAV パターンだが、カルテ専用として独立テーブルを維持（テーブル結合の複雑化を防止）
- `chart_intake_form_templates` は `teams` テーブルへの FK を持つ

---

## 8. 未解決事項

- [x] ~~`allergy_info` のカラム暗号化を Phase 7 で実施するか、後続フェーズに回すか~~ → Phase 10-11（セキュリティ監査フェーズ）に延期。Phase 7 では平文保存。FUTURE_CONSIDERATIONS.md に記載済み
- [x] ~~PROGRESS_GRAPH セクションの具体的な実装仕様（Chart.js でどの指標を経時グラフ化するか）~~ → Chart.js 折れ線グラフ。X軸=来店日、Y軸=NUMBER 型カスタムフィールド値（体重、可動域角度等）。ビジネスロジックセクションに詳細記載
- [x] ~~問診票のフォーム項目をチームごとにカスタマイズ可能にするか、固定フォームにするか~~ → チームごとにカスタマイズ可能。`chart_intake_form_templates` テーブルで JSON ベースの質問テンプレートを定義
- [x] ~~写真の EXIF 情報（撮影日時・GPS）の取り扱い方針（GPS は自動ストリップが望ましい）~~ → サーバーサイドで GPS 情報を自動ストリップ。撮影日時（DateTimeOriginal）はタイムライン用途のため保持
- [x] ~~カルテのコピー機能（前回のカルテをベースに新規作成）の要否~~ → 実装する。`POST /charts/{id}/copy` で薬剤レシピ・アレルギー情報・カスタムフィールド値をデフォルトとしてコピー
- [x] ~~`chart_body_marks` の `body_part` ENUM に HAND/FOOT 等を追加するか~~ → `HAND_LEFT`, `HAND_RIGHT`, `FOOT_LEFT`, `FOOT_RIGHT` の4値を追加

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-10 | 初版作成 |
| 2026-03-11 | 未解決事項 6件を全件解決: allergy_info 暗号化を Phase 10-11 に延期、PROGRESS_GRAPH 仕様確定（Chart.js 折れ線）、問診票テンプレートのチーム別カスタマイズ対応（`chart_intake_form_templates` 追加）、EXIF GPS 自動ストリップ、カルテコピー機能追加（`POST /charts/{id}/copy`）、body_part ENUM に HAND/FOOT 追加 |
| 2026-03-11 | 精査: electronic_seals 参照先を「デフォルト機能 #22」に修正、XSS 対策明記、PDF 生成タイムアウト考慮追加、カルテコピーの customer_user_id 自動継承を明記 |
| 2026-03-11 | 精査②: GET /charts 一覧 API のクエリパラメータ追加、ページネーションを cursor/limit に統一、sort_order 型を INT に統一、問診票テンプレート削除挙動明記 |
