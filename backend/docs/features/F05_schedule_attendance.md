# F05: スケジュール・出欠管理

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 3
> **最終更新**: 2026-02-21

---

## 1. 概要

チーム・組織の練習・試合・イベント等のスケジュールを管理し、出欠確認・アンケートを一体化した出席管理機能を提供する。繰り返しスケジュール（週次練習等）をサポートし、「この回のみ変更」「この回以降を変更」「全回変更」の3段階編集に対応する。MEMBER はデフォルトで `MANAGE_SCHEDULES` 権限を持ちスケジュールの作成・編集が可能。ユーザーは任意でチーム・組織のスケジュールを個人の Google カレンダーへ自動同期できる。試合スケジュールは相手チーム・組織を招待してカレンダーを共有できる（クロスチームスケジュール）。出欠アンケートにはリマインダーを設定でき、未回答メンバーに期日前通知を送れる。組織スケジュールの出欠は所属チーム別に集計して参照できる。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全スケジュールの参照・強制削除 |
| ADMIN | スケジュールの作成・編集・削除・キャンセル。出欠一覧・集計の参照。クロスチーム招待の送受信 |
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
| `schedule_attendance_reminders` | 出欠リマインダー設定（未回答者への期日前通知）| なし |
| `schedule_cross_refs` | クロスチーム・組織スケジュール招待（試合マッチング等）| なし |
| `user_google_calendar_connections` | ユーザーの Google Calendar OAuth 連携情報 | なし |
| `user_calendar_sync_settings` | ユーザーが Google カレンダーに同期するチーム・組織の設定 | なし |
| `user_schedule_google_events` | ユーザーごとのスケジュール↔Google Calendar イベント ID マッピング | なし |

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
| `comment_option` | ENUM('HIDDEN', 'OPTIONAL', 'REQUIRED') | NO | 'OPTIONAL' | 補足コメント欄の表示設定（`attendance_required = FALSE` の場合は無視）|
| `parent_schedule_id` | BIGINT UNSIGNED | YES | NULL | 繰り返しスケジュールの親 ID（自己参照 FK; NULL = 単発または繰り返しの親自身）|
| `recurrence_rule` | JSON | YES | NULL | 繰り返しルール（親スケジュールのみ設定。下記形式参照）|
| `is_exception` | BOOLEAN | NO | FALSE | 繰り返しのうち個別変更された回（TRUE = 親から独立した内容を持つ）|
| `google_calendar_event_id` | VARCHAR(255) | YES | NULL | チーム・組織の共有 Google Calendar イベント ID（F09: 管理者レベルの Google 連携で使用）|
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
- `comment_option` の挙動:
  - `HIDDEN`: コメント欄を非表示。送信された `reason` 値はバックエンドで無視し保存しない
  - `OPTIONAL`: コメント欄を任意表示（デフォルト）
  - `REQUIRED`: コメント欄を必須表示。`reason` が空の場合は出欠回答を 400 で拒否
- `comment_option` 省略時: 同一スコープ（team_id / organization_id）の直近スケジュールの設定を自動適用。前回設定が存在しない場合は `OPTIONAL`
- 論理削除: `deleted_at DATETIME nullable`

---

#### `schedule_attendances`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → schedules（ON DELETE CASCADE）|
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `status` | ENUM('ATTENDING', 'PARTIAL', 'ABSENT', 'UNDECIDED') | NO | 'UNDECIDED' | 出欠ステータス（PARTIAL = 遅刻・早退）|
| `reason` | VARCHAR(500) | YES | NULL | 理由・補足コメント（例: 「15分遅刻」「17時に早退予定」）。全ステータスで任意入力可 |
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

#### `schedule_attendance_reminders`

出欠確認が必要なスケジュールに対し、未回答メンバーへの通知を指定日時に送るためのリマインダー設定テーブル。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → schedules（ON DELETE CASCADE）|
| `remind_at` | DATETIME | NO | — | リマインダー送信予定日時（`attendance_deadline` より前に設定）|
| `is_sent` | BOOLEAN | NO | FALSE | 送信済みフラグ |
| `sent_at` | DATETIME | YES | NULL | 実際の送信完了日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_sar_schedule (schedule_id)
INDEX idx_sar_batch (is_sent, remind_at)    -- バッチ: 未送信かつ期日到来のリマインダーを検索
```

**制約・備考**
- 1スケジュールにつき最大5件（アプリ層でバリデーション）
- `attendance_required = FALSE` のスケジュールへの設定は不可（アプリ層でバリデーション）
- `remind_at` は `attendance_deadline` より前でなければならない（deadline が NULL の場合は `start_at` より前）

---

#### `schedule_cross_refs`

チーム・組織間の試合スケジュール招待テーブル。招待元（例: Team A）が相手（例: Team B）を招待し、承認されると相手チームのカレンダーにもスケジュールが作成される。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `source_schedule_id` | BIGINT UNSIGNED | NO | — | FK → schedules（招待元スケジュール）|
| `target_type` | ENUM('TEAM', 'ORGANIZATION') | NO | — | 招待先の種別 |
| `target_id` | BIGINT UNSIGNED | NO | — | 招待先のチーム / 組織 ID |
| `target_schedule_id` | BIGINT UNSIGNED | YES | NULL | 承認後に作成された招待先のスケジュール ID（FK → schedules SET NULL on delete）|
| `invited_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete）|
| `status` | ENUM('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED') | NO | 'PENDING' | 招待状態 |
| `message` | VARCHAR(500) | YES | NULL | 招待メッセージ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `responded_at` | DATETIME | YES | NULL | 承認 / 拒否 / キャンセルが行われた日時 |

**インデックス**
```sql
INDEX idx_scr_source (source_schedule_id)
INDEX idx_scr_target (target_type, target_id, status)    -- 招待先からの受信招待一覧
INDEX idx_scr_target_schedule (target_schedule_id)
UNIQUE KEY uq_scr_source_target (source_schedule_id, target_type, target_id)   -- 重複招待防止
```

**制約・備考**
- 承認後の `target_schedule_id` スケジュールは独立管理（招待元の変更は自動連動しない）
- 招待元スケジュールがキャンセルされた場合、招待先スケジュールには通知のみ（自動キャンセルはしない）
- `status = 'CANCELLED'` は招待元 ADMIN が承認前に取り消した場合

---

#### `user_google_calendar_connections`

ユーザーが個人 Google カレンダーへの自動同期を利用するために必要な OAuth 2.0 認可情報を管理するテーブル。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `google_account_email` | VARCHAR(255) | NO | — | 連携した Google アカウントのメールアドレス |
| `google_calendar_id` | VARCHAR(255) | NO | 'primary' | 同期先カレンダー ID（デフォルト: ユーザーのメインカレンダー）|
| `access_token` | TEXT | NO | — | Google Calendar API アクセストークン（AES-256-GCM 暗号化）|
| `refresh_token` | TEXT | NO | — | リフレッシュトークン（AES-256-GCM 暗号化）|
| `token_expires_at` | DATETIME | NO | — | アクセストークンの有効期限 |
| `is_active` | BOOLEAN | NO | TRUE | 連携が有効かどうか（トークン失効・手動解除で FALSE）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_ugcc_user_id (user_id)    -- 1ユーザー1 Google アカウント連携（Phase 3 制限）
```

**制約・備考**
- `access_token` / `refresh_token` は **平文保存禁止**（AES-256-GCM 等で暗号化必須）
- 1ユーザーにつき1 Google アカウント連携（複数アカウント対応は Phase 4+ で検討）
- ユーザー退会時は `ON DELETE CASCADE` で物理削除し、Google 側の revoke も実施する

---

#### `user_calendar_sync_settings`

ユーザーが Google カレンダーへの自動同期を有効化したチーム・組織のリスト。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `scope_type` | ENUM('TEAM', 'ORGANIZATION') | NO | — | 同期対象の種別 |
| `scope_id` | BIGINT UNSIGNED | NO | — | 同期対象のチーム / 組織 ID |
| `is_enabled` | BOOLEAN | NO | TRUE | 同期が有効かどうか |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_ucss_user_scope (user_id, scope_type, scope_id)
INDEX idx_ucss_scope (scope_type, scope_id, is_enabled)    -- スケジュール変更時に同期対象ユーザーを検索
```

**制約・備考**
- `user_google_calendar_connections.is_active = TRUE` でないと同期は実行されない（アプリ層でチェック）
- ユーザーがチーム・組織を退会しても設定レコードは削除しない（再加入時の自動復元のため）

---

#### `user_schedule_google_events`

ユーザーごとの「スケジュール → Google Calendar イベント ID」マッピングテーブル。Google Calendar API の更新・削除操作に使用する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → schedules（ON DELETE CASCADE）|
| `google_event_id` | VARCHAR(255) | NO | — | Google Calendar のイベント ID |
| `last_synced_at` | DATETIME | NO | — | 最終同期日時 |

**インデックス**
```sql
UNIQUE KEY uq_usge_user_schedule (user_id, schedule_id)   -- PK 代替（AUTO_INCREMENT PK なし）
INDEX idx_usge_schedule_id (schedule_id)                  -- スケジュール更新時に全同期ユーザーを検索
```

**制約・備考**
- 同期エラー（API エラー・トークン期限切れ等）が発生した場合はバッチで最大3回再試行

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
schedules (1) ──── (N) schedule_attendance_reminders
schedules (1) ──── (N) schedule_cross_refs         ※ source_schedule_id
schedules (0..1) ──── (N) schedule_cross_refs      ※ target_schedule_id
users (1) ──── (1) user_google_calendar_connections
users (1) ──── (N) user_calendar_sync_settings
users (1) ──── (N) user_schedule_google_events
schedules (1) ──── (N) user_schedule_google_events
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
| POST | `/api/v1/teams/{id}/schedules/{scheduleId}/cross-invite` | 必要（ADMIN）| 他チーム・組織へのスケジュール招待送信 |
| DELETE | `/api/v1/teams/{id}/schedules/{scheduleId}/cross-invite/{invitationId}` | 必要（ADMIN）| 送信済み招待のキャンセル |
| GET | `/api/v1/teams/{id}/schedule-invitations` | 必要（ADMIN）| 受信したスケジュール招待一覧 |
| POST | `/api/v1/teams/{id}/schedule-invitations/{invitationId}/accept` | 必要（ADMIN）| スケジュール招待を承認 |
| POST | `/api/v1/teams/{id}/schedule-invitations/{invitationId}/reject` | 必要（ADMIN）| スケジュール招待を拒否 |
| GET | `/api/v1/organizations/{id}/schedules` | 必要（MEMBER+）| 組織スケジュール一覧 |
| POST | `/api/v1/organizations/{id}/schedules` | 必要（MANAGE_SCHEDULES）| 組織スケジュール作成 |
| GET | `/api/v1/organizations/{id}/schedules/{scheduleId}` | 必要（MEMBER+）| 組織スケジュール詳細 |
| PATCH | `/api/v1/organizations/{id}/schedules/{scheduleId}` | 必要（MANAGE_SCHEDULES）| 組織スケジュール更新 |
| DELETE | `/api/v1/organizations/{id}/schedules/{scheduleId}` | 必要（ADMIN）| 組織スケジュール削除 |
| POST | `/api/v1/organizations/{id}/schedules/{scheduleId}/cancel` | 必要（ADMIN）| 組織スケジュールキャンセル |
| GET | `/api/v1/organizations/{id}/schedules/{scheduleId}/attendances` | 必要（ADMIN）| チーム別出欠集計（個人情報なし）|
| GET | `/api/v1/me/schedules` | 必要 | 全チーム・組織横断のスケジュール一覧 |
| GET | `/api/v1/me/google-calendar/status` | 必要 | Google カレンダー連携状態確認 |
| POST | `/api/v1/me/google-calendar/connect` | 必要 | Google Calendar OAuth 連携（認可コード受取）|
| DELETE | `/api/v1/me/google-calendar/disconnect` | 必要 | Google Calendar 連携解除 |
| GET | `/api/v1/me/calendar-sync-settings` | 必要 | チーム・組織別の同期設定一覧 |
| PUT | `/api/v1/me/teams/{id}/calendar-sync` | 必要 | チームの Google カレンダー同期 ON/OFF |
| PUT | `/api/v1/me/organizations/{id}/calendar-sync` | 必要 | 組織の Google カレンダー同期 ON/OFF |
| GET | `/api/v1/teams/{id}/attendance-stats` | 必要（ADMIN）| チームメンバー全員の出席率（ダッシュボード用）|
| GET | `/api/v1/me/attendance-stats` | 必要 | 自分の出席率（チーム・組織別・全体）|

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
  "comment_option": "OPTIONAL",
  "surveys": [
    {
      "question": "バス乗り合いに参加しますか？",
      "question_type": "BOOLEAN",
      "is_required": true,
      "sort_order": 1
    }
  ],
  "reminders": [
    { "remind_at": "2026-03-29T09:00:00" },
    { "remind_at": "2026-04-02T09:00:00" }
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
        "partial": 2,
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

> `update_scope` は `parent_schedule_id IS NOT NULL`（繰り返しの子）の場合にのみ意味を持つ。

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
| 400 | バリデーションエラー・`is_required` の設問に未回答・`comment_option = REQUIRED` で `reason` が空 |
| 403 | SUPPORTER / GUEST は出欠回答不可 |
| 409 | `attendance_deadline` を過ぎている |
| 422 | `attendance_required = false` のスケジュールへの回答 / `status = CANCELLED` のスケジュール |

---

#### `GET /api/v1/organizations/{id}/schedules/{scheduleId}/attendances`

組織スケジュールの出欠をチーム別に集計して返す。個別メンバーの出欠情報は含まない。

**レスポンス（200 OK）**
```json
{
  "data": {
    "schedule_id": 30,
    "total": {
      "attending": 45,
      "absent": 10,
      "undecided": 15
    },
    "by_team": [
      {
        "team_id": 1,
        "team_name": "Aチーム",
        "attending": 20,
        "absent": 4,
        "undecided": 6
      },
      {
        "team_id": 2,
        "team_name": "Bチーム",
        "attending": 25,
        "absent": 6,
        "undecided": 9
      },
      {
        "team_id": null,
        "team_name": "チーム未所属",
        "attending": 0,
        "absent": 0,
        "undecided": 0
      }
    ]
  }
}
```

> - 個人レベルの出欠詳細（user_id・名前・個別ステータス）は含まない
> - `team_id: null` は組織に直接所属しているがいずれのチームにも属さないメンバーの集計

---

#### `POST /api/v1/teams/{id}/schedules/{scheduleId}/cross-invite`

他のチームまたは組織をスケジュールに招待する（試合相手としてマッチング）。

**リクエストボディ**
```json
{
  "target_type": "TEAM",
  "target_id": 42,
  "message": "4/5 の練習試合のご招待です。ご検討よろしくお願いします。"
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "invitation_id": 100,
    "source_schedule_id": 20,
    "target_type": "TEAM",
    "target_id": 42,
    "status": "PENDING",
    "created_at": "2026-03-01T10:00:00Z"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 404 | `target_id` のチーム / 組織が存在しない |
| 409 | 同一 source + target への重複招待（PENDING または ACCEPTED）|
| 422 | `status = CANCELLED` のスケジュールへの招待 |

---

#### `GET /api/v1/teams/{id}/schedule-invitations`

受信したスケジュール招待一覧（他チームから招待された一覧）。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `status` | String | `PENDING` | PENDING / ACCEPTED / REJECTED / CANCELLED |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "invitation_id": 100,
      "source_team_id": 5,
      "source_team_name": "FC Tokyo Youth",
      "source_schedule": {
        "id": 20,
        "title": "vs 〇〇 練習試合",
        "start_at": "2026-04-05T13:00:00",
        "location": "駒沢オリンピック公園陸上競技場"
      },
      "message": "4/5 の練習試合のご招待です。",
      "status": "PENDING",
      "created_at": "2026-03-01T10:00:00Z"
    }
  ]
}
```

---

#### `POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/accept`

招待を承認する。承認時に自チームのカレンダーにミラースケジュールが作成される。

**レスポンス（201 Created）**
```json
{
  "data": {
    "invitation_id": 100,
    "status": "ACCEPTED",
    "created_schedule_id": 55
  }
}
```

> `created_schedule_id`: 自チームのカレンダーに新規作成されたミラースケジュールの ID

---

#### `GET /api/v1/me/google-calendar/status`

**レスポンス（200 OK）**
```json
{
  "data": {
    "is_connected": true,
    "google_account_email": "user@gmail.com",
    "google_calendar_id": "primary",
    "is_active": true
  }
}
```

---

#### `POST /api/v1/me/google-calendar/connect`

Google OAuth 2.0 の認可コードを受け取り、アクセストークン・リフレッシュトークンを取得して保存する。

**リクエストボディ**
```json
{
  "code": "4/P7q7W91a-oMsCeLvIaQm6bTrgtp...",
  "redirect_uri": "https://app.mannschaft.example.com/oauth/google-calendar/callback"
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "google_account_email": "user@gmail.com",
    "is_active": true
  }
}
```

---

#### `PUT /api/v1/me/teams/{id}/calendar-sync`

チームの Google カレンダー同期を ON/OFF 切り替える。初回 ON 時に過去の未同期スケジュール（今日以降の未来分）を一括同期する。

**リクエストボディ**
```json
{
  "is_enabled": true
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "team_id": 1,
    "is_enabled": true,
    "backfill_count": 12
  }
}
```

> `backfill_count`: 初回 ON 時に遡り同期したスケジュール件数。既に同期済みまたは OFF 切替時は 0

**エラーレスポンス（共通）**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（from > to 等）|
| 401 | 未認証 |
| 403 | 権限不足 |
| 404 | リソース不存在 |
| 422 | Google Calendar 未連携のまま同期 ON を要求（`/me/google-calendar/connect` が未完了）|

---

#### `GET /api/v1/me/schedules`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 今日 | 取得開始日 |
| `to` | ISO 8601 date | 30日後 | 取得終了日 |
| `attendance_required_only` | Boolean | false | 出欠確認が必要なもののみ |
| `undecided_only` | Boolean | false | 自分が未回答のもののみ |

---

#### `GET /api/v1/teams/{id}/attendance-stats`

チームメンバー全員の出席率をダッシュボード用に返す。ADMIN のみ参照可能。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 3ヶ月前 | 集計開始日（inclusive）|
| `to` | ISO 8601 date | 今日 | 集計終了日（inclusive）|
| `user_id` | Long | — | 特定メンバーに絞り込み（省略時は全員）|

**レスポンス（200 OK）**
```json
{
  "data": {
    "period": { "from": "2025-11-21", "to": "2026-02-21" },
    "team_summary": {
      "total_schedules": 24,
      "avg_attendance_rate": 82.5,
      "avg_strict_rate": 66.7
    },
    "members": [
      {
        "user_id": 1,
        "display_name": "田中 太郎",
        "total_required": 24,
        "attending": 16,
        "partial": 4,
        "absent": 4,
        "undecided": 0,
        "attendance_rate": 83.3,
        "strict_rate": 66.7
      },
      {
        "user_id": 2,
        "display_name": "鈴木 花子",
        "total_required": 24,
        "attending": 18,
        "partial": 2,
        "absent": 3,
        "undecided": 1,
        "attendance_rate": 87.0,
        "strict_rate": 78.3
      }
    ]
  }
}
```

> - `attendance_rate` = (ATTENDING + PARTIAL) / (ATTENDING + PARTIAL + ABSENT) × 100（応答済みのみで計算）
> - `strict_rate` = ATTENDING / (ATTENDING + PARTIAL + ABSENT) × 100（完全出席のみをカウント）
> - `undecided` は分母に含めない
> - `total_required` は `attendance_required = TRUE` かつ `status != CANCELLED` のスケジュール数

---

#### `GET /api/v1/me/attendance-stats`

自分の出席率をチーム・組織別および全体でまとめて返す。個人ダッシュボード用。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 3ヶ月前 | 集計開始日（inclusive）|
| `to` | ISO 8601 date | 今日 | 集計終了日（inclusive）|

**レスポンス（200 OK）**
```json
{
  "data": {
    "period": { "from": "2025-11-21", "to": "2026-02-21" },
    "overall": {
      "total_required": 30,
      "attending": 20,
      "partial": 6,
      "absent": 4,
      "undecided": 0,
      "attendance_rate": 86.7,
      "strict_rate": 66.7
    },
    "by_scope": [
      {
        "scope_type": "TEAM",
        "scope_id": 1,
        "scope_name": "FC Tokyo Youth",
        "total_required": 20,
        "attending": 14,
        "partial": 4,
        "absent": 2,
        "undecided": 0,
        "attendance_rate": 90.0,
        "strict_rate": 70.0
      },
      {
        "scope_type": "ORGANIZATION",
        "scope_id": 3,
        "scope_name": "東京FC",
        "total_required": 10,
        "attending": 6,
        "partial": 2,
        "absent": 2,
        "undecided": 0,
        "attendance_rate": 80.0,
        "strict_rate": 60.0
      }
    ]
  }
}
```

> `attendance_rate` / `strict_rate` の計算式は `GET /teams/{id}/attendance-stats` と同様

---

## 5. ビジネスロジック

### 出席率計算定義

出席率は以下の定義に基づき、スケジュール一覧・ダッシュボードの全集計で統一して使用する。

| 指標 | 計算式 | 用途 |
|------|--------|------|
| **出席率** | (ATTENDING + PARTIAL) / (ATTENDING + PARTIAL + ABSENT) × 100 | 「参加した」割合。ダッシュボードのメイン指標 |
| **完全出席率** | ATTENDING / (ATTENDING + PARTIAL + ABSENT) × 100 | 遅刻・早退なしで参加した割合。補助指標 |

**集計の前提条件（分母に含めるスケジュール）**
- `attendance_required = TRUE`
- `status != CANCELLED`
- `deleted_at IS NULL`
- `start_at` が集計期間内

**UNDECIDED の扱い**
- 分母・分子ともに含めない（未回答は欠席と同一視しない）
- `total_required` はリクエスト者が対象のスケジュール総数（UNDECIDED 含む）
- `undecided` は残カウントとして返し、フロントエンドで「未回答あり」の警告表示に使用する

### スケジュール作成フロー（単発）

```
1. POST /api/v1/teams/{id}/schedules を受付
2. MANAGE_SCHEDULES 権限を確認
3. バリデーション（start_at < end_at、title 必須 等）
4. `comment_option` が省略されている場合:
   同一スコープの直近スケジュール（attendance_required = TRUE、deleted_at IS NULL）を取得し
   その `comment_option` を適用。存在しない場合は 'OPTIONAL'
5. schedules に INSERT（recurrence_rule = NULL、parent_schedule_id = NULL）
6. surveys が存在する場合: event_surveys を全件 INSERT
7. reminders が存在する場合:
   a. 各 remind_at が attendance_deadline（または start_at）より前か確認 → 違反は 400
   b. schedule_attendance_reminders を全件 INSERT
8. attendance_required = TRUE の場合:
   a. 当該チームの全 MEMBER・ADMIN・DEPUTY_ADMIN の user_id を取得
   b. schedule_attendances を (status='UNDECIDED') で一括 INSERT
   c. プッシュ通知「出欠確認が届きました」を送信（通知機能実装後）
9. Google カレンダー個人同期（@Async）:
   a. このチームの user_calendar_sync_settings.is_enabled = TRUE かつ
      user_google_calendar_connections.is_active = TRUE のユーザーを取得
   b. 各ユーザーの Google Calendar に非同期でイベント作成
   c. 作成成功時に user_schedule_google_events に INSERT
10. audit_logs に SCHEDULE_CREATED を記録
11. 201 Created を返す
```

---

### スケジュール作成フロー（繰り返し）

```
1. recurrence_rule を含むリクエストを受付
2. MANAGE_SCHEDULES 権限を確認
3. recurrence_rule の形式をバリデーション
4. 親スケジュールを schedules に INSERT（recurrence_rule を保存、parent_schedule_id = NULL）
5. recurrence_rule に従って子スケジュールを展開（@Async）:
   a. 展開範囲: 作成日から最大12ヶ月先（または end_date / count に達した方）
   b. 上限: 200件。超過する場合は end_date を自動調整してユーザーに通知
   c. 各子スケジュールを schedules に INSERT（parent_schedule_id = 親 ID、recurrence_rule = NULL）
6. attendance_required = TRUE の場合: 全子スケジュールに対して単発フロー step 7 を適用
7. Google カレンダー個人同期: 全子スケジュールに対して単発フロー step 8 を適用
8. audit_logs に SCHEDULE_CREATED を記録
   metadata: {"recurrence_type": "WEEKLY", "generated_count": 74}
9. 201 Created を返す
```

---

### 出欠回答フロー

```
1. POST /api/v1/teams/{id}/schedules/{scheduleId}/attendance を受付
2. スケジュールが存在・未削除・status = SCHEDULED か確認（CANCELLED / COMPLETED は 422）
3. attendance_required = TRUE か確認 → FALSE は 422
4. リクエスト者が当該チームの MEMBER 以上か確認（SUPPORTER / GUEST は 403）
5. attendance_deadline が設定されている場合: deadline < NOW() であれば 409
6. comment_option に応じて reason をバリデーション:
   - HIDDEN   → reason 値を無視（保存時に NULL に上書き）
   - OPTIONAL → バリデーションなし
   - REQUIRED → reason が null または空文字の場合 400
7. schedule_attendances を UPSERT（UNIQUE KEY: schedule_id + user_id）
8. survey_responses が含まれる場合:
   a. is_required = TRUE の全設問に回答があるか確認 → 未回答は 400
   b. event_survey_responses を UPSERT
9. responded_at が NULL の場合（初回回答）: NOW() を設定
10. 200 OK を返す
```

---

### 繰り返しスケジュール更新フロー

```
[THIS_ONLY]
1. 当該スケジュールのみ UPDATE
2. is_exception = TRUE に設定
3. 当該スケジュールを同期しているユーザーの Google Calendar イベントを更新（@Async）
4. audit_logs に SCHEDULE_UPDATED を記録（metadata: {"update_scope": "THIS_ONLY"}）

[THIS_AND_FOLLOWING]
1. 当該スケジュールの start_at 以降の全子スケジュール（is_exception = FALSE）を論理削除
2. 親スケジュールの recurrence_rule.end_date を「当該スケジュールの start_at の前日」に更新（分割）
3. 変更内容で新しい親スケジュールを INSERT
4. 新しい親の recurrence_rule に従って子スケジュールを再展開（@Async）
5. 削除された子スケジュールの Google Calendar イベントを削除（@Async）
6. 新しい子スケジュールを Google カレンダーに追加（@Async）
7. audit_logs に SCHEDULE_UPDATED を記録（metadata: {"update_scope": "THIS_AND_FOLLOWING"}）

[ALL]
1. 親スケジュールを UPDATE（変更フィールドのみ）
2. is_exception = FALSE の全子スケジュールを同一フィールドで UPDATE
3. 全子スケジュールの Google Calendar イベントを更新（@Async）
4. audit_logs に SCHEDULE_UPDATED を記録（metadata: {"update_scope": "ALL"}）
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
6. Google カレンダー同期: このスケジュールを同期しているユーザーの Google Calendar イベントを削除（@Async）
7. クロスチームリンクの確認:
   a. このスケジュールに紐づく schedule_cross_refs（source_schedule_id）を取得
   b. status = ACCEPTED のものがある場合、target_schedule の所属チーム/組織 ADMIN に通知
      「〇〇チームが招待スケジュールをキャンセルしました」
8. audit_logs に SCHEDULE_CANCELLED を記録
9. 200 OK を返す
```

---

### 出欠リマインダーバッチフロー

毎時実行の定期バッチ（例: 毎時 0 分）。未回答メンバーのみに通知を送る。

```
1. schedule_attendance_reminders から is_sent = FALSE かつ remind_at <= NOW() のレコードを取得
2. 各リマインダーについて:
   a. 紐づくスケジュールが status = SCHEDULED かつ deleted_at IS NULL か確認（CANCELLED はスキップ）
   b. attendance_deadline が未到来か確認（期限切れはスキップ）
   c. schedule_attendances から status = UNDECIDED の user_id 一覧を取得
   d. 各ユーザーにプッシュ通知「出欠未回答です。期限は〇〇です。」を送信
   e. schedule_attendance_reminders.is_sent = TRUE、sent_at = NOW() に UPDATE
3. audit_logs には記録しない（通知インフラのログで管理）
```

---

### クロスチームスケジュール招待フロー

```
[招待送信]
1. POST /api/v1/teams/{id}/schedules/{scheduleId}/cross-invite を受付
2. 操作者が当該チームの ADMIN か確認
3. target_id のチーム / 組織が存在するか確認 → なければ 404
4. スケジュールが SCHEDULED 状態か確認 → CANCELLED は 422
5. 同一 source + target への重複招待がないか確認（PENDING / ACCEPTED）→ あれば 409
6. schedule_cross_refs に INSERT（status = PENDING）
7. 招待先チーム / 組織の ADMIN にプッシュ通知「〇〇から試合招待が届きました」
8. 201 Created を返す

[招待承認]
1. POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/accept を受付
2. 操作者が当該チームの ADMIN か確認
3. invitation が PENDING 状態か確認 → ACCEPTED / REJECTED は 422
4. 招待元スケジュールの内容で自チームのカレンダーにミラースケジュールを作成:
   a. schedules に INSERT（team_id = 自チーム, title = 招待元タイトル, is_exception = FALSE）
   b. attendance_required = TRUE の場合: 自チームの全メンバーに schedule_attendances を INSERT
5. schedule_cross_refs.target_schedule_id = 新スケジュール ID、status = ACCEPTED、
   responded_at = NOW() に UPDATE
6. 招待元チームの ADMIN に「招待が承認されました」と通知
7. audit_logs に SCHEDULE_CROSS_INVITE_ACCEPTED を記録
8. 201 Created を返す

[招待拒否]
1. POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/reject を受付
2. 操作者が当該チームの ADMIN か確認
3. invitation が PENDING 状態か確認
4. schedule_cross_refs.status = REJECTED、responded_at = NOW() に UPDATE
5. 招待元チームの ADMIN に「招待が拒否されました」と通知
6. 204 No Content を返す
```

---

### Google カレンダー個人同期フロー

```
[連携開始]
1. POST /api/v1/me/google-calendar/connect を受付
2. Google OAuth 2.0 認可コードを Google Token Endpoint に送信
3. アクセストークン・リフレッシュトークンを取得
4. 両トークンを AES-256-GCM で暗号化して user_google_calendar_connections に UPSERT
5. 200 OK を返す

[同期有効化]
1. PUT /api/v1/me/teams/{id}/calendar-sync（is_enabled: true）を受付
2. user_google_calendar_connections.is_active = TRUE か確認（未連携は 422）
3. user_calendar_sync_settings を UPSERT（is_enabled = true）
4. 今日以降の未来スケジュールを @Async で一括同期（バックフィル）:
   a. 当該チームの schedules（start_at >= NOW()、deleted_at IS NULL）を取得
   b. user_schedule_google_events に未登録のスケジュールを Google Calendar に INSERT
   c. user_schedule_google_events にレコードを INSERT
5. 200 OK を返す（backfill_count を含む）

[スケジュール変更時の自動同期（ApplicationEvent 受信）]
- SCHEDULE_CREATED  → 同期設定ユーザーの Google Calendar にイベント作成 → user_schedule_google_events INSERT
- SCHEDULE_UPDATED  → user_schedule_google_events から google_event_id を取得 → Google Calendar API で UPDATE
- SCHEDULE_CANCELLED / SCHEDULE_DELETED
                   → user_schedule_google_events から google_event_id を取得 → Google Calendar API でイベント削除
                   → user_schedule_google_events からレコードを DELETE

[トークンリフレッシュ]
- API 呼び出し前に token_expires_at を確認
- 期限切れの場合: Google Token Endpoint にリフレッシュトークンで再取得
- リフレッシュ失敗時: is_active = FALSE に設定し、ユーザーに再連携通知を送信
```

---

### 組織スケジュール出欠集計フロー

```
1. GET /api/v1/organizations/{id}/schedules/{scheduleId}/attendances を受付
2. 操作者が当該組織の ADMIN か確認
3. スケジュールが当該組織スコープ（organization_id 一致）か確認
4. schedule_attendances を取得し、user_id から team_memberships でチームを解決
5. チームごとに attending / partial / absent / undecided を集計:
   - チームに所属しない組織直接メンバーは team_id = null として集計
6. 集計結果のみ返す（個別 user_id・名前は含まない）
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
4. 新しい子スケジュールの Google カレンダー同期（同期設定ユーザーに対して）
5. audit_logs に SCHEDULE_RECURRENCE_EXPANDED を記録
```

---

## 6. セキュリティ考慮事項

- **権限チェック**: スケジュール作成・編集には `MANAGE_SCHEDULES` 権限を F03 の権限解決ロジック経由で確認する
- **他者スケジュールの削除**: MEMBER が自分以外の作成スケジュールを削除するには `DELETE_OTHERS_CONTENT` 権限が必要。ADMIN は無条件で削除可能
- **出欠情報のプライバシー**: `attendance_summary`（数値集計）は ADMIN のみ返す。MEMBER は自分のステータスのみ参照可能。組織レベルの出欠集計はチーム単位の件数のみ開示し、個人の出欠情報は返さない
- **可視性制御**: `visibility = 'MEMBERS_ONLY'` のスケジュールは SUPPORTER / GUEST・未ログインユーザーに返さない
- **繰り返し展開の上限**: 一括 INSERT 200件の制限でリソース枯渇を防ぐ。展開は非同期（Spring `@Async`）で実行し、作成 API のレスポンスをブロックしない
- **スコープ越境防止**: team_id / organization_id がリクエスト者の所属スコープと一致するかを全エンドポイントで確認する
- **Google Calendar OAuth トークン管理**: `access_token` / `refresh_token` は AES-256-GCM で暗号化して保存。平文での DB 保存・ログ出力は禁止。Google OAuth スコープは `https://www.googleapis.com/auth/calendar.events` のみ要求（最小権限）
- **クロスチーム招待のスコープ確認**: 招待元・招待先いずれも操作者が当該チーム / 組織の ADMIN であることを確認する。招待承認時も招待先チームへの所属確認を必ず実施する

---

## 7. Flywayマイグレーション

```
V3.007__create_schedules_table.sql
V3.008__create_schedule_attendances_table.sql
V3.009__create_event_surveys_table.sql
V3.010__create_event_survey_responses_table.sql
V3.011__create_schedule_attendance_reminders_table.sql
V3.012__create_schedule_cross_refs_table.sql
V3.013__create_user_google_calendar_connections_table.sql
V3.014__create_user_calendar_sync_settings_table.sql
V3.015__create_user_schedule_google_events_table.sql
```

**マイグレーション上の注意点**
- V3.007 は V2.001（organizations）/ V2.002（teams）完了後に実行
- V3.008 は V3.007（schedules）および V1.005（users）完了後
- V3.009 は V3.007（schedules）完了後
- V3.010 は V3.009（event_surveys）および V1.005（users）完了後
- V3.011 は V3.007（schedules）完了後
- V3.012 は V3.007（schedules）完了後
- V3.013 は V1.005（users）完了後
- V3.014 は V1.005（users）完了後
- V3.015 は V1.005（users）および V3.007（schedules）完了後

---

## 8. 未解決事項

- [ ] 出席率の集計期間のデフォルト（現: 3ヶ月）を、シーズン単位（開始日〜終了日）で設定できるかを確定する（シーズン管理機能との連動）
- [ ] UNDECIDED のまま `attendance_deadline` を過ぎたメンバーを ABSENT として出席率計算に含めるオプション設定の必要性を確認する（現設計は UNDECIDED を除外）
- [ ] 大規模チームでの出席率集計パフォーマンス: 将来的に `member_attendance_stats` キャッシュテーブルを設けるかを Phase 4+ で検討する（現設計はオンザフライ計算）
- [ ] 繰り返しスケジュール自動展開バッチのスケジュール（毎週 or 毎日・実行時刻）を確定する
- [ ] Google Calendar 管理者レベル共有連携（`google_calendar_event_id` カラムの用途）は F09 で別途設計し、個人同期との役割分担を確定する
- [ ] `event_type` の選択肢はテンプレート（SPORTS / SCHOOL 等）に応じて表示切替するかを確定する（テンプレート管理 feature doc で検討）
- [ ] 出欠集計（attending / absent / undecided の件数）を MEMBER にも件数のみ開示するかを確定する（現設計は ADMIN のみ）
- [ ] 大規模チーム（1000人規模）への一括 `schedule_attendances` INSERT 時のパフォーマンス対策（バッチ INSERT 分割・バックグラウンド処理化等）
- [ ] `visibility = 'PUBLIC'` のスケジュールを未ログインユーザーに公開するかを確定する（チームの `visibility` との連動ルールを整理）
- [ ] クロスチーム招待時、招待先チームが非公開の場合でも招待を送れるかを確定する（招待送信時のプライバシー設計）
- [ ] Google Calendar 同期エラー時の最大リトライ回数・最終失敗時のユーザー通知方法を確定する
- [ ] 組織スケジュールの出欠確認: チームに所属しない組織直接メンバーへの出欠通知フローを確定する
- [ ] クロスチーム招待承認後、招待元が内容（日時・場所）を変更した場合の招待先への通知仕様を確定する

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-02-21 | `schedules.comment_option`（HIDDEN / OPTIONAL / REQUIRED）を追加。省略時は同スコープの直近スケジュール設定を自動適用。出欠回答フローに REQUIRED バリデーションを追加 |
| 2026-02-21 | PARTIAL（遅刻・早退）ステータスを追加。出席率（広義・完全）計算定義を策定。チームダッシュボード用 `GET /teams/{id}/attendance-stats` と個人ダッシュボード用 `GET /me/attendance-stats` を追加 |
| 2026-02-21 | 精査: Google カレンダー個人同期・クロスチームスケジュール招待・出欠リマインダー・組織スケジュールのチーム別出欠集計を追加。テーブル5件追加（schedule_attendance_reminders / schedule_cross_refs / user_google_calendar_connections / user_calendar_sync_settings / user_schedule_google_events）。API 11本追加。Flyway V3.011〜V3.015 追加 |
| 2026-02-21 | 初版作成 |
