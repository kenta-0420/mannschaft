# F05: スケジュール・出欠管理

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 3
> **最終更新**: 2026-02-21

---

## 1. 概要

チーム・組織の練習・試合・イベント等のスケジュールを管理し、出欠確認・アンケートを一体化した出席管理機能を提供する。繰り返しスケジュール（週次練習等）をサポートし、「この回のみ変更」「この回以降を変更」「全回変更」の3段階編集に対応する。MEMBER はデフォルトで `MANAGE_SCHEDULES` 権限を持ちスケジュールの作成・編集が可能。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全スケジュールの参照・強制削除 |
| ADMIN | スケジュールの作成・編集・削除・キャンセル。出欠一覧・集計の参照 |
| DEPUTY_ADMIN | `MANAGE_SCHEDULES` 権限を持つ場合: スケジュール作成・編集・削除 |
| MEMBER | デフォルトで `MANAGE_SCHEDULES` を保持（作成・編集）。他者のスケジュール削除は `DELETE_OTHERS_CONTENT` 権限が必要。自分の出欠を回答 |
| SUPPORTER | スケジュール閲覧のみ（visibility に従う）。出欠回答は不可 |
| GUEST | 閲覧のみ（`visibility = PUBLIC` のスケジュールのみ）|

### 対象レベル
- [x] 組織 (Organization)
- [x] チーム (Team)
- [ ] 個人 (Personal)

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `schedules` | スケジュール/イベントマスター（繰り返し含む）| あり |
| `schedule_attendances` | メンバーの出欠回答 | なし（UPDATE で管理）|
| `event_surveys` | スケジュールに付随する追加アンケート設問 | なし |
| `event_survey_responses` | アンケート設問への個人回答 | なし |

### テーブル定義

#### `schedules`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（チームスコープ; NULL = 組織スコープ）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（組織スコープ; NULL = チームスコープ）|
| `title` | VARCHAR(200) | NO | — | タイトル |
| `description` | TEXT | YES | NULL | 詳細説明 |
| `location` | VARCHAR(300) | YES | NULL | 場所・会場名 |
| `start_at` | DATETIME | NO | — | 開始日時 |
| `end_at` | DATETIME | YES | NULL | 終了日時（NULL = 終了時間未定）|
| `all_day` | BOOLEAN | NO | FALSE | 終日フラグ（TRUE の場合、時刻は無効とし start_at を日付のみで扱う）|
| `event_type` | ENUM('PRACTICE', 'MATCH', 'EVENT', 'MEETING', 'OTHER') | NO | 'OTHER' | イベント種別（カレンダー表示のカテゴリ分け用）|
| `visibility` | ENUM('MEMBERS_ONLY', 'ORGANIZATION', 'PUBLIC') | NO | 'MEMBERS_ONLY' | 公開範囲 |
| `status` | ENUM('SCHEDULED', 'CANCELLED', 'COMPLETED') | NO | 'SCHEDULED' | 開催状態 |
| `attendance_required` | BOOLEAN | NO | FALSE | 出欠確認が必要か |
| `attendance_deadline` | DATETIME | YES | NULL | 出欠回答期限（NULL = 無期限）|
| `parent_schedule_id` | BIGINT UNSIGNED | YES | NULL | 繰り返しスケジュールの親 ID（自己参照 FK; NULL = 単発または繰り返しの親自身）|
| `recurrence_rule` | JSON | YES | NULL | 繰り返しルール（親スケジュールのみ設定。下記形式参照）|
| `is_exception` | BOOLEAN | NO | FALSE | 繰り返しのうち個別変更された回（TRUE = 親から独立した内容を持つ）|
| `google_calendar_event_id` | VARCHAR(255) | YES | NULL | Google Calendar イベント ID（同期設定時に記録）|
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_sch_team_start (team_id, start_at)            -- チーム別カレンダー取得用
INDEX idx_sch_org_start (organization_id, start_at)     -- 組織別カレンダー取得用
INDEX idx_sch_parent (parent_schedule_id)               -- 繰り返し子スケジュール取得用
INDEX idx_sch_google_calendar (google_calendar_event_id)
```

**`recurrence_rule` JSON 形式**
```json
{
  "type": "WEEKLY",
  "interval": 1,
  "days_of_week": ["TUE", "THU"],
  "end_type": "DATE",
  "end_date": "2026-12-31"
}
```

| フィールド | 型 | 説明 |
|-----------|---|------|
| `type` | String | `DAILY` / `WEEKLY` / `MONTHLY` / `YEARLY` |
| `interval` | Int | 繰り返し間隔（例: `2` で隔週）|
| `days_of_week` | String[] | WEEKLY のみ: `MON` / `TUE` / `WED` / `THU` / `FRI` / `SAT` / `SUN` |
| `end_type` | String | `DATE`（終了日指定）/ `COUNT`（回数指定）/ `NEVER`（無期限）|
| `end_date` | String | end_type = DATE の場合の終了日（ISO 8601 date）|
| `count` | Int | end_type = COUNT の場合の繰り返し回数 |

**制約・備考**
- `team_id` と `organization_id` はどちらか一方のみ非 NULL（XOR; アプリ層でバリデーション）
- `parent_schedule_id IS NOT NULL`（子）の場合、`recurrence_rule` は NULL とする
- `all_day = TRUE` の場合、`start_at` の時刻は `00:00:00` に固定し `end_at` は NULL または `23:59:59` で統一する
- 論理削除: `deleted_at DATETIME nullable`

---

#### `schedule_attendances`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → schedules（ON DELETE CASCADE）|
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `status` | ENUM('ATTENDING', 'ABSENT', 'UNDECIDED') | NO | 'UNDECIDED' | 出欠ステータス |
| `reason` | VARCHAR(500) | YES | NULL | 理由・コメント |
| `responded_at` | DATETIME | YES | NULL | 初回回答日時（初めて UNDECIDED 以外に変更した時点で設定・以降更新しない）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_sa_schedule_user (schedule_id, user_id)
INDEX idx_sa_user_id (user_id)                 -- 自分の出欠一覧用
INDEX idx_sa_status (schedule_id, status)      -- 出欠集計用
```

**制約・備考**
- 出欠は随時変更可能（回数・内容に制限なし）
- `responded_at` は最初の回答時のみ記録し、以降の変更では更新しない（初回回答日時の保持）

---

#### `event_surveys`

スケジュールの出欠回答時に表示する追加アンケート設問。Phase 5 の独立したアンケート機能（`surveys`）とは別テーブルとして管理する（README §コンテンツ管理 参照）。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → schedules（ON DELETE CASCADE）|
| `question` | VARCHAR(500) | NO | — | 設問文（例: 「バス乗り合いに参加しますか？」）|
| `question_type` | ENUM('TEXT', 'BOOLEAN', 'SELECT', 'MULTI_SELECT') | NO | 'BOOLEAN' | 回答形式 |
| `options` | JSON | YES | NULL | 選択肢（SELECT / MULTI_SELECT のみ）例: `["はい", "いいえ", "未定"]` |
| `is_required` | BOOLEAN | NO | TRUE | 必須回答かどうか |
| `sort_order` | TINYINT UNSIGNED | NO | 0 | 設問の表示順 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_es_schedule_id (schedule_id)
```

**制約・備考**
- 1スケジュールにつき設問は最大10件（アプリ層でバリデーション）
- `attendance_required = FALSE` のスケジュールには event_surveys を設定できない（アプリ層でバリデーション）

---

#### `event_survey_responses`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `event_survey_id` | BIGINT UNSIGNED | NO | — | FK → event_surveys（ON DELETE CASCADE）|
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `answer_text` | TEXT | YES | NULL | TEXT / BOOLEAN 型の回答（BOOLEAN は `"true"` / `"false"` の文字列で格納）|
| `answer_options` | JSON | YES | NULL | SELECT / MULTI_SELECT 型の選択結果 例: `["はい"]` |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_esr_survey_user (event_survey_id, user_id)
INDEX idx_esr_user_id (user_id)
```

---

### ER図（テキスト形式）
```
teams / organizations (1) ──── (N) schedules
schedules (1) ──── (N) schedules              ※ parent_schedule_id（自己参照・繰り返し）
schedules (1) ──── (N) schedule_attendances
users (1) ──── (N) schedule_attendances
schedules (1) ──── (N) event_surveys
event_surveys (1) ──── (N) event_survey_responses
users (1) ──── (N) event_survey_responses
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/teams/{id}/schedules` | 必要（MEMBER+）| スケジュール一覧（期間指定）|
| POST | `/api/v1/teams/{id}/schedules` | 必要（MANAGE_SCHEDULES）| スケジュール作成（繰り返し含む）|
| GET | `/api/v1/teams/{id}/schedules/{scheduleId}` | 必要（MEMBER+）| スケジュール詳細 |
| PATCH | `/api/v1/teams/{id}/schedules/{scheduleId}` | 必要（MANAGE_SCHEDULES）| スケジュール更新（update_scope 指定）|
| DELETE | `/api/v1/teams/{id}/schedules/{scheduleId}` | 必要（ADMIN または DELETE_OTHERS_CONTENT）| スケジュール論理削除 |
| POST | `/api/v1/teams/{id}/schedules/{scheduleId}/cancel` | 必要（ADMIN）| キャンセル（status = CANCELLED）|
| GET | `/api/v1/teams/{id}/schedules/{scheduleId}/attendances` | 必要（ADMIN）| 出欠一覧・集計（ADMIN 用）|
| POST | `/api/v1/teams/{id}/schedules/{scheduleId}/attendance` | 必要（MEMBER）| 出欠を回答・変更 |
| GET | `/api/v1/organizations/{id}/schedules` | 必要（MEMBER+）| 組織スケジュール一覧 |
| POST | `/api/v1/organizations/{id}/schedules` | 必要（MANAGE_SCHEDULES）| 組織スケジュール作成 |
| GET | `/api/v1/organizations/{id}/schedules/{scheduleId}` | 必要（MEMBER+）| 組織スケジュール詳細 |
| PATCH | `/api/v1/organizations/{id}/schedules/{scheduleId}` | 必要（MANAGE_SCHEDULES）| 組織スケジュール更新 |
| DELETE | `/api/v1/organizations/{id}/schedules/{scheduleId}` | 必要（ADMIN）| 組織スケジュール削除 |
| POST | `/api/v1/organizations/{id}/schedules/{scheduleId}/cancel` | 必要（ADMIN）| 組織スケジュールキャンセル |
| GET | `/api/v1/me/schedules` | 必要 | 全チーム・組織横断のスケジュール一覧 |

### リクエスト／レスポンス仕様

#### `POST /api/v1/teams/{id}/schedules`

**リクエストボディ（単発）**
```json
{
  "title": "vs 東京FC 練習試合",
  "description": "ホームゲーム。ユニフォーム持参。",
  "location": "駒沢オリンピック公園陸上競技場",
  "start_at": "2026-04-05T13:00:00",
  "end_at": "2026-04-05T17:00:00",
  "all_day": false,
  "event_type": "MATCH",
  "visibility": "MEMBERS_ONLY",
  "attendance_required": true,
  "attendance_deadline": "2026-04-03T23:59:59",
  "surveys": [
    {
      "question": "バス乗り合いに参加しますか？",
      "question_type": "BOOLEAN",
      "is_required": true,
      "sort_order": 1
    }
  ]
}
```

**リクエストボディ（繰り返し）**
```json
{
  "title": "火木練習",
  "start_at": "2026-04-01T19:00:00",
  "end_at": "2026-04-01T21:00:00",
  "all_day": false,
  "event_type": "PRACTICE",
  "visibility": "MEMBERS_ONLY",
  "attendance_required": false,
  "recurrence_rule": {
    "type": "WEEKLY",
    "interval": 1,
    "days_of_week": ["TUE", "THU"],
    "end_type": "DATE",
    "end_date": "2026-12-31"
  }
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 1,
    "title": "火木練習",
    "start_at": "2026-04-01T19:00:00",
    "end_at": "2026-04-01T21:00:00",
    "event_type": "PRACTICE",
    "status": "SCHEDULED",
    "attendance_required": false,
    "recurrence_rule": {
      "type": "WEEKLY",
      "interval": 1,
      "days_of_week": ["TUE", "THU"],
      "end_type": "DATE",
      "end_date": "2026-12-31"
    },
    "generated_count": 74,
    "created_at": "2026-03-01T10:00:00Z"
  }
}
```

> `generated_count`: 繰り返しスケジュールで展開された子スケジュールの件数。単発の場合は含めない。

---

#### `GET /api/v1/teams/{id}/schedules`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 今月1日 | 取得開始日（inclusive）|
| `to` | ISO 8601 date | 今月末日 | 取得終了日（inclusive）|
| `event_type` | String | — | 種別フィルタ（PRACTICE / MATCH / EVENT / MEETING / OTHER）|
| `status` | String | — | 状態フィルタ（SCHEDULED / CANCELLED / COMPLETED）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 10,
      "title": "火木練習",
      "start_at": "2026-04-01T19:00:00",
      "end_at": "2026-04-01T21:00:00",
      "all_day": false,
      "event_type": "PRACTICE",
      "location": null,
      "status": "SCHEDULED",
      "attendance_required": false,
      "attendance_deadline": null,
      "my_attendance": null,
      "attendance_summary": null,
      "parent_schedule_id": 1,
      "is_exception": false
    },
    {
      "id": 20,
      "title": "vs 東京FC 練習試合",
      "start_at": "2026-04-05T13:00:00",
      "end_at": "2026-04-05T17:00:00",
      "all_day": false,
      "event_type": "MATCH",
      "location": "駒沢オリンピック公園陸上競技場",
      "status": "SCHEDULED",
      "attendance_required": true,
      "attendance_deadline": "2026-04-03T23:59:59",
      "my_attendance": "UNDECIDED",
      "attendance_summary": {
        "attending": 12,
        "absent": 3,
        "undecided": 9
      },
      "parent_schedule_id": null,
      "is_exception": false
    }
  ]
}
```

> - `my_attendance`: リクエスト者自身の出欠ステータス（`attendance_required = false` の場合は null）
> - `attendance_summary`: ADMIN のみ返す。MEMBER / DEPUTY_ADMIN には null（プライバシー保護）

---

#### `PATCH /api/v1/teams/{id}/schedules/{scheduleId}`

繰り返しスケジュールを更新する際、`update_scope` で変更範囲を指定する。

**リクエストボディ**
```json
{
  "title": "火木練習（会場変更）",
  "location": "第2グラウンド",
  "update_scope": "THIS_ONLY"
}
```

**`update_scope` の値と挙動**

| 値 | 挙動 |
|----|------|
| `THIS_ONLY` | この回のみ変更（`is_exception = TRUE` に設定）。単発スケジュールのデフォルト動作 |
| `THIS_AND_FOLLOWING` | この回以降の全回を変更（親を分割し、この回から新しい親を生成）|
| `ALL` | 繰り返しの全回を一括変更（`is_exception = TRUE` の回は除外）|

> `update_scope` は `parent_schedule_id IS NOT NULL`（繰り返しの子）の場合にのみ意味を持つ。単発スケジュールには不要。

---

#### `POST /api/v1/teams/{id}/schedules/{scheduleId}/attendance`

**リクエストボディ**
```json
{
  "status": "ATTENDING",
  "reason": null,
  "survey_responses": [
    {
      "event_survey_id": 5,
      "answer_text": "true"
    }
  ]
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "schedule_id": 20,
    "status": "ATTENDING",
    "reason": null,
    "responded_at": "2026-04-01T10:00:00Z"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー・`is_required` の設問に未回答 |
| 403 | SUPPORTER / GUEST は出欠回答不可 |
| 409 | `attendance_deadline` を過ぎている |
| 422 | `attendance_required = false` のスケジュールへの回答 / `status = CANCELLED` のスケジュール |

---

#### `GET /api/v1/me/schedules`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 今日 | 取得開始日 |
| `to` | ISO 8601 date | 30日後 | 取得終了日 |
| `attendance_required_only` | Boolean | false | 出欠確認が必要なもののみ |
| `undecided_only` | Boolean | false | 自分が未回答のもののみ |

**エラーレスポンス（共通）**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（from > to 等）|
| 401 | 未認証 |
| 403 | 権限不足（MANAGE_SCHEDULES 未保持での作成・編集等）|
| 404 | リソース不存在 |

---

## 5. ビジネスロジック

### スケジュール作成フロー（単発）

```
1. POST /api/v1/teams/{id}/schedules を受付
2. MANAGE_SCHEDULES 権限を確認
3. バリデーション（start_at < end_at、title 必須 等）
4. schedules に INSERT（recurrence_rule = NULL、parent_schedule_id = NULL）
5. surveys が存在する場合: event_surveys を全件 INSERT
6. attendance_required = TRUE の場合:
   a. 当該チームの全 MEMBER・ADMIN・DEPUTY_ADMIN の user_id を取得
   b. schedule_attendances を (status='UNDECIDED') で一括 INSERT
   c. プッシュ通知「出欠確認が届きました」を送信（通知機能実装後）
7. audit_logs に SCHEDULE_CREATED を記録
8. 201 Created を返す
```

---

### スケジュール作成フロー（繰り返し）

```
1. recurrence_rule を含むリクエストを受付
2. MANAGE_SCHEDULES 権限を確認
3. recurrence_rule の形式をバリデーション
4. 親スケジュールを schedules に INSERT（recurrence_rule を保存、parent_schedule_id = NULL）
5. recurrence_rule に従って子スケジュールを展開:
   a. 展開範囲: 作成日から最大12ヶ月先（または end_date / count に達した方）
   b. 上限: 200件。超過する場合は end_date を自動調整してユーザーに通知
   c. 各子スケジュールを schedules に INSERT（parent_schedule_id = 親 ID、recurrence_rule = NULL）
6. attendance_required = TRUE の場合: 全子スケジュールに対して単発フロー step 6 を適用
7. audit_logs に SCHEDULE_CREATED を記録
   metadata: {"recurrence_type": "WEEKLY", "generated_count": 74}
8. 201 Created を返す
```

---

### 出欠回答フロー

```
1. POST /api/v1/teams/{id}/schedules/{scheduleId}/attendance を受付
2. スケジュールが存在・未削除・status = SCHEDULED か確認（CANCELLED / COMPLETED は 422）
3. attendance_required = TRUE か確認 → FALSE は 422
4. リクエスト者が当該チームの MEMBER 以上か確認（SUPPORTER / GUEST は 403）
5. attendance_deadline が設定されている場合: deadline < NOW() であれば 409
6. schedule_attendances を UPSERT（UNIQUE KEY: schedule_id + user_id）
7. survey_responses が含まれる場合:
   a. is_required = TRUE の全設問に回答があるか確認 → 未回答は 400
   b. event_survey_responses を UPSERT
8. responded_at が NULL の場合（初回回答）: NOW() を設定
9. 200 OK を返す
```

---

### 繰り返しスケジュール更新フロー

```
[THIS_ONLY]
1. 当該スケジュールのみ UPDATE
2. is_exception = TRUE に設定
3. audit_logs に SCHEDULE_UPDATED を記録（metadata: {"update_scope": "THIS_ONLY"}）

[THIS_AND_FOLLOWING]
1. 当該スケジュールの start_at 以降の全子スケジュール（is_exception = FALSE）を論理削除
2. 親スケジュールの recurrence_rule.end_date を「当該スケジュールの start_at の前日」に更新（分割）
3. 変更内容で新しい親スケジュールを INSERT
4. 新しい親の recurrence_rule に従って子スケジュールを再展開
5. audit_logs に SCHEDULE_UPDATED を記録（metadata: {"update_scope": "THIS_AND_FOLLOWING"}）

[ALL]
1. 親スケジュールを UPDATE（変更フィールドのみ）
2. is_exception = FALSE の全子スケジュールを同一フィールドで UPDATE
3. audit_logs に SCHEDULE_UPDATED を記録（metadata: {"update_scope": "ALL"}）
```

---

### スケジュールキャンセルフロー

```
1. POST /api/v1/teams/{id}/schedules/{scheduleId}/cancel を受付
2. ADMIN か確認
3. status が SCHEDULED か確認（CANCELLED / COMPLETED は 422）
4. schedules.status = CANCELLED に UPDATE
5. attendance_required = TRUE の場合:
   プッシュ通知「〇〇がキャンセルされました」を出欠登録済みメンバーへ送信（通知機能実装後）
6. audit_logs に SCHEDULE_CANCELLED を記録
7. 200 OK を返す
```

---

### 繰り返しスケジュール自動展開バッチ

`end_type = NEVER` または遠い将来の `end_date` を持つ繰り返しスケジュールは、初回作成時には最大12ヶ月分のみ展開する。バッチが定期実行（例: 毎週月曜 AM2:00）され、残り30日以内に展開済み子スケジュールが尽きる親スケジュールを検出し、次の12ヶ月分を追加展開する。

```
バッチフロー:
1. parent_schedule_id = NULL かつ recurrence_rule IS NOT NULL の全スケジュールを取得
2. 各親スケジュールについて: 最後の子スケジュールの start_at から30日以内になっているか確認
3. 条件を満たす親スケジュールの子を追加展開（次の12ヶ月分）
4. audit_logs に SCHEDULE_RECURRENCE_EXPANDED を記録
```

---

## 6. セキュリティ考慮事項

- **権限チェック**: スケジュール作成・編集には `MANAGE_SCHEDULES` 権限を F03 の権限解決ロジック経由で確認する
- **他者スケジュールの削除**: MEMBER が自分以外の作成スケジュールを削除するには `DELETE_OTHERS_CONTENT` 権限が必要。ADMIN は無条件で削除可能
- **出欠情報のプライバシー**: `attendance_summary`（数値集計）は ADMIN のみ返す。MEMBER は自分のステータスのみ参照可能（他メンバーの個別出欠は非公開）
- **可視性制御**: `visibility = 'MEMBERS_ONLY'` のスケジュールは SUPPORTER / GUEST・未ログインユーザーに返さない
- **繰り返し展開の上限**: 一括 INSERT 200件の制限でリソース枯渇を防ぐ。展開は非同期（Spring `@Async`）で実行し、作成 API のレスポンスをブロックしない
- **スコープ越境防止**: team_id / organization_id がリクエスト者の所属スコープと一致するかを全エンドポイントで確認する

---

## 7. Flywayマイグレーション

```
V3.007__create_schedules_table.sql
V3.008__create_schedule_attendances_table.sql
V3.009__create_event_surveys_table.sql
V3.010__create_event_survey_responses_table.sql
```

**マイグレーション上の注意点**
- V3.007 は V2.001（organizations）/ V2.002（teams）完了後に実行
- V3.008 は V3.007（schedules）および V1.005（users）完了後
- V3.009 は V3.007（schedules）完了後
- V3.010 は V3.009（event_surveys）および V1.005（users）完了後

---

## 8. 未解決事項

- [ ] 繰り返しスケジュール自動展開バッチのスケジュール（毎週 or 毎日・実行時刻）を確定する
- [ ] Google Calendar 同期の詳細設計は Google 連携 feature doc で別途実施（`google_calendar_event_id` カラムで紐付けのみ担保）
- [ ] `event_type` の選択肢はテンプレート（SPORTS / SCHOOL 等）に応じて表示切替するかを確定する（テンプレート管理 feature doc で検討）
- [ ] 出欠集計（attending / absent / undecided の件数）を MEMBER にも件数のみ開示するかを確定する（現設計は ADMIN のみ）
- [ ] 大規模チーム（1000人規模）への一括 `schedule_attendances` INSERT 時のパフォーマンス対策（バッチ INSERT 分割・バックグラウンド処理化等）
- [ ] `visibility = 'PUBLIC'` のスケジュールを未ログインユーザーに公開するかを確定する（チームの `visibility` との連動ルールを整理）

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-02-21 | 初版作成 |
