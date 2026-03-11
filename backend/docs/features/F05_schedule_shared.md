# F05: スケジュール・出欠管理（組織・チームスコープ）

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 3
> **最終更新**: 2026-03-11
> **関連ドキュメント**: 個人スケジュール → `F05_schedule_personal.md` / 外部連携 → `F08_external_integration.md`

---

## 1. 概要

チーム・組織の練習・試合・イベント等のスケジュールを管理し、出欠確認・アンケートを一体化した出席管理機能を提供する。繰り返しスケジュール（週次練習等）をサポートし、「この回のみ変更」「この回以降を変更」「全回変更」の3段階編集に対応する。MEMBER はデフォルトで `MANAGE_SCHEDULES` 権限を持ちスケジュールの作成・編集が可能。ユーザーは任意でチーム・組織のスケジュールを個人の Google カレンダーへ自動同期できる。試合スケジュールは相手チーム・組織を招待してカレンダーを共有できる（クロスチームスケジュール）。出欠アンケートにはリマインダーを設定でき、未回答メンバーに期日前通知を送れる。組織スケジュールの出欠は所属チーム別に集計して参照できる。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全スケジュールの参照・強制削除 |
| ADMIN | スケジュールの作成・編集・削除・キャンセル。個人名付き出欠一覧・集計・ダッシュボード統計の参照。クロスチーム招待の送受信 |
| DEPUTY_ADMIN | `MANAGE_SCHEDULES` 権限を持つ場合: スケジュール作成・編集・自己作成スケジュールの削除。他者作成スケジュールの削除は `MANAGE_SCHEDULES` + `DELETE_OTHERS_CONTENT` の両方が必要。`min_response_role` 以上のスケジュールの出欠集計サマリー（件数のみ）参照可 |
| MEMBER | デフォルトで `MANAGE_SCHEDULES` を保持（作成・編集）。他者のスケジュール削除は `DELETE_OTHERS_CONTENT` 権限が必要。`min_response_role IN ('SUPPORTER+', 'MEMBER+')` のスケジュールに出欠回答可・出欠集計サマリー（件数のみ）参照可 |
| SUPPORTER | `min_view_role IN ('ANYONE', 'SUPPORTER+')` のスケジュールのみ閲覧可。スコープ判定は `visibility` に従う（`MEMBERS_ONLY`: 所有チーム/組織への直接所属が必要。`ORGANIZATION`: 親組織への直接所属ロールで評価）。`visibility = 'ORGANIZATION'` でも `min_view_role = 'MEMBER+'`（デフォルト）なら閲覧不可。SUPPORTER に見せる場合は `min_view_role = 'SUPPORTER+'` を明示する。`min_response_role = 'SUPPORTER+'` のスケジュールに限り出欠回答可・出欠集計サマリー（件数のみ）参照可 |
| GUEST / 未ログイン | `min_view_role = 'ANYONE'` のスケジュールのみ閲覧可。未ログインユーザーは GUEST と同等に扱う（Optional Authentication）|

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

> Google Calendar 連携テーブル（`user_google_calendar_connections` / `user_calendar_sync_settings` / `user_schedule_google_events`）の定義は `F08_external_integration.md` Section 3 を参照。

| `member_attendance_stats` | メンバー出席統計月次キャッシュ（Phase 4+）| なし |

### テーブル定義

#### `schedules`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（チームスコープ; NULL = 組織または個人スコープ）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（組織スコープ; NULL = チームまたは個人スコープ）|
| `user_id` | BIGINT UNSIGNED | YES | NULL | FK → users（個人スコープ; NULL = チームまたは組織スコープ）。ON DELETE CASCADE |
| `title` | VARCHAR(200) | NO | — | タイトル |
| `description` | TEXT | YES | NULL | 詳細説明 |
| `location` | VARCHAR(300) | YES | NULL | 場所・会場名 |
| `start_at` | DATETIME | NO | — | 開始日時 |
| `end_at` | DATETIME | YES | NULL | 終了日時（NULL = 終了時間未定）|
| `all_day` | BOOLEAN | NO | FALSE | 終日フラグ（TRUE の場合、時刻は無効とし start_at を日付のみで扱う）|
| `event_type` | ENUM('PRACTICE', 'MATCH', 'EVENT', 'MEETING', 'OTHER') | NO | 'OTHER' | イベント種別（カレンダー表示のカテゴリ分け用）|
| `visibility` | ENUM('MEMBERS_ONLY', 'ORGANIZATION') | NO | 'MEMBERS_ONLY' | 公開スコープ（どの範囲まで公開するか。`min_view_role = 'ANYONE'` の場合は無効）|
| `min_view_role` | ENUM('ANYONE', 'SUPPORTER+', 'MEMBER+', 'ADMIN_ONLY') | NO | 'MEMBER+' | 閲覧可能な最小権限。省略時はチーム/組織の `default_schedule_min_view_role` 設定を継承し、設定がない場合は `MEMBER+`。`ANYONE` は未ログイン含む全員に公開（`visibility` を上書き）|
| `min_response_role` | ENUM('SUPPORTER+', 'MEMBER+', 'ADMIN_ONLY') | NO | 'MEMBER+' | 出欠回答可能な最小権限。省略時はチーム/組織の `default_schedule_min_response_role` 設定を継承し、設定がない場合は `MEMBER+`。`attendance_required = TRUE` 時、このロール以上のメンバーのみ `schedule_attendances` に登録される |
| `status` | ENUM('SCHEDULED', 'CANCELLED', 'COMPLETED') | NO | 'SCHEDULED' | 開催状態 |
| `attendance_required` | BOOLEAN | NO | FALSE | 出欠確認が必要か |
| `attendance_status` | ENUM('READY', 'GENERATING') | NO | 'READY' | 出欠レコードの生成状態。`attendance_required = TRUE` かつ大規模チームの場合、@Async 処理中は GENERATING になる。`attendance_required = FALSE` の場合は常に READY |
| `attendance_deadline` | DATETIME | YES | NULL | 出欠回答期限（NULL = 無期限）|
| `comment_option` | ENUM('HIDDEN', 'OPTIONAL', 'REQUIRED') | NO | 'OPTIONAL' | 補足コメント欄の表示設定（`attendance_required = FALSE` の場合は無視）|
| `parent_schedule_id` | BIGINT UNSIGNED | YES | NULL | 繰り返しスケジュールの親 ID（自己参照 FK; ON DELETE RESTRICT; NULL = 単発または繰り返しの親自身）|
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
INDEX idx_sch_user_start (user_id, start_at)            -- 個人カレンダー取得用
INDEX idx_sch_parent (parent_schedule_id)               -- 繰り返し子スケジュール取得用
UNIQUE KEY uq_sch_parent_start (parent_schedule_id, start_at)  -- 繰り返し展開の重複防止（parent_schedule_id = NULL 行は制約対象外）
INDEX idx_sch_status_end_at (status, end_at)              -- 自動完了バッチ用（V3.021）
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
- `team_id`・`organization_id`・`user_id` は三者うちいずれか1つのみ非 NULL（XOR）。DB レベルでは CHECK 制約、アプリ層でも重複バリデーションを実施する:
  ```sql
  CONSTRAINT chk_schedule_scope CHECK (
    (team_id IS NOT NULL AND organization_id IS NULL AND user_id IS NULL) OR
    (team_id IS NULL AND organization_id IS NOT NULL AND user_id IS NULL) OR
    (team_id IS NULL AND organization_id IS NULL AND user_id IS NOT NULL)
  )
  ```
- `user_id IS NOT NULL`（個人スコープ）のスケジュールは `attendance_required = FALSE`・`min_view_role = 'ADMIN_ONLY'`・`visibility = 'MEMBERS_ONLY'` が固定値となり変更不可（詳細は `F05_schedule_personal.md` 参照）
- `parent_schedule_id IS NOT NULL`（子）の場合、`recurrence_rule` は NULL とする
- `all_day = TRUE` の場合、`start_at` の時刻は `00:00:00` に固定し `end_at` は NULL または `23:59:59` で統一する
- `comment_option` の挙動:
  - `HIDDEN`: コメント欄を非表示。送信された `comment` 値はバックエンドで無視し保存しない
  - `OPTIONAL`: コメント欄を任意表示（デフォルト）
  - `REQUIRED`: コメント欄を必須表示。`comment` が空の場合は出欠回答を 400 で拒否
- `comment_option` 省略時: 同一スコープ（team_id / organization_id）の直近スケジュールの設定を自動適用。前回設定が存在しない場合は `OPTIONAL`
- `min_view_role` の挙動:
  - `ANYONE`: 未ログイン含む全員に公開。`visibility` を上書きしパブリックスケジュールとして扱う
  - `SUPPORTER+`: SUPPORTER・MEMBER・DEPUTY_ADMIN・ADMIN が閲覧可
  - `MEMBER+`: MEMBER・DEPUTY_ADMIN・ADMIN のみ閲覧可（デフォルト）
  - `ADMIN_ONLY`: DEPUTY_ADMIN・ADMIN のみ閲覧可（機密スケジュール等）
- `min_view_role` 省略時: チーム/組織の `default_schedule_min_view_role` 設定を継承。設定がない場合は `MEMBER+`
- `min_response_role` の挙動:
  - `SUPPORTER+`: SUPPORTER・MEMBER・DEPUTY_ADMIN・ADMIN が出欠回答可
  - `MEMBER+`: MEMBER・DEPUTY_ADMIN・ADMIN のみ出欠回答可（デフォルト）
  - `ADMIN_ONLY`: DEPUTY_ADMIN・ADMIN のみ出欠回答可
- `min_response_role` 省略時: チーム/組織の `default_schedule_min_response_role` 設定を継承。設定がない場合は `MEMBER+`
- `min_response_role` は `min_view_role` と独立した設定。回答権限のないユーザーはスケジュールを閲覧可能だが PATCH /responses は 403 を返す
- `attendance_required = TRUE` 時、`min_response_role` 以上のロールを持つユーザーのみ `schedule_attendances` に INSERT される
- **`min_view_role` の評価スコープ（親子関係）**:
  - `visibility = 'MEMBERS_ONLY'`: スケジュールを所有するチーム/組織への**直接所属ロール**で評価する
  - `visibility = 'ORGANIZATION'`（チームスコープのスケジュールを組織共有する場合）: 親組織への**直接所属ロール**で評価する
  - 親グループのロールは子グループのスケジュールに**継承しない**（例: 学校の SUPPORTER は、クラスに直接所属していない場合はクラスの `visibility = MEMBERS_ONLY` スケジュールを閲覧不可）
- **SUPPORTER と `visibility = 'ORGANIZATION'` の組み合わせ**:
  - `visibility = 'ORGANIZATION'` + `min_view_role = 'SUPPORTER+'`: 親組織に直接所属する SUPPORTER も閲覧可（組織全体へのお知らせ等に適する）
  - `visibility = 'ORGANIZATION'` + `min_view_role = 'MEMBER+'`（デフォルト）: 親組織の SUPPORTER は閲覧不可。チームメンバーおよび組織の MEMBER 以上のみ閲覧可
  - SUPPORTER に org-shared スケジュールをデフォルトで表示したい場合: `organizations.default_schedule_min_view_role = 'SUPPORTER+'` を設定する
  - 「運営専用」スケジュール（SUPPORTER を除外）: `min_view_role = 'ADMIN_ONLY'`（DEPUTY_ADMIN/ADMIN のみ）または `min_view_role = 'MEMBER+'`（MEMBER 以上のみ）を明示的に設定する
  - `visibility = 'ORGANIZATION'` は**チームスコープのスケジュールにのみ有効**（組織スコープのスケジュールでは `MEMBERS_ONLY` と実質同義）
- **`treat_undecided_as_absent_after_deadline`**（`teams` / `organizations` テーブルに追加する設定、BOOLEAN, DEFAULT FALSE）の挙動:
  - `FALSE`（デフォルト）または `attendance_deadline IS NULL`: UNDECIDED は分母・分子ともに含めない（回答済みのみで計算）
  - `TRUE` かつ `attendance_deadline < NOW()`: UNDECIDED を ABSENT として扱い、分母を `total_members`（全対象メンバー数）に変更する
  - `attendance_deadline IS NULL` の場合はフラグが `TRUE` でも無効（期限が設定されていなければ「期限経過」は発生しない）
- 論理削除: `deleted_at DATETIME nullable`

---

#### `schedule_attendances`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → schedules（ON DELETE CASCADE）|
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `status` | ENUM('ATTENDING', 'PARTIAL', 'ABSENT', 'UNDECIDED') | NO | 'UNDECIDED' | 出欠ステータス（PARTIAL = 遅刻・早退）|
| `comment` | VARCHAR(500) | YES | NULL | 補足コメント（例: 「15分遅刻」「17時に早退予定」）。全ステータスで任意入力可 |
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
| `source_schedule_id` | BIGINT UNSIGNED | NO | — | FK → schedules（招待元スケジュール; ON DELETE CASCADE）|
| `target_type` | ENUM('TEAM', 'ORGANIZATION') | NO | — | 招待先の種別 |
| `target_id` | BIGINT UNSIGNED | NO | — | 招待先のチーム / 組織 ID |
| `target_schedule_id` | BIGINT UNSIGNED | YES | NULL | 承認後に作成された招待先のスケジュール ID（FK → schedules SET NULL on delete）|
| `invited_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete）|
| `status` | ENUM('PENDING', 'AWAITING_CONFIRMATION', 'ACCEPTED', 'REJECTED', 'CANCELLED') | NO | 'PENDING' | 招待状態 |
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
- `status = 'CANCELLED'` は招待元 ADMIN が `PENDING` または `AWAITING_CONFIRMATION` 状態で取り消した場合
- `status = 'AWAITING_CONFIRMATION'`: 招待先 ADMIN が承認済みだが招待元 ADMIN の最終確認待ち。招待先チームの `visibility = 'PRIVATE'` の場合のみ経由する（PUBLIC / ORGANIZATION_ONLY の場合は直接 ACCEPTED になる）
- `UNIQUE KEY uq_scr_source_target` により同一 source + target への重複 INSERT は常に禁止される。`REJECTED` / `CANCELLED` 後に再招待する場合はアプリ層で既存行を `status = PENDING` に UPDATE する（新規 INSERT ではなく UPDATE で対応）

---

#### `member_attendance_stats`（Phase 4+ キャッシュテーブル）

メンバーの出席統計をスコープ・月単位で事前集計したキャッシュテーブル。大規模チーム（1000人規模）での `GET /teams/{id}/attendance-stats` 等の高速化を目的とする。Phase 3 ではオンザフライ計算で実装し、Phase 4 でこのテーブルを導入してクエリを切り替える。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（PK 構成要素）|
| `scope_type` | ENUM('TEAM', 'ORGANIZATION') | NO | — | 集計スコープ種別（PK 構成要素）|
| `scope_id` | BIGINT UNSIGNED | NO | — | チームまたは組織の ID（PK 構成要素）|
| `year_month` | DATE | NO | — | 集計対象月の初日（例: `2026-03-01`）（PK 構成要素）|
| `count_attending` | INT UNSIGNED | NO | 0 | ATTENDING の件数 |
| `count_partial` | INT UNSIGNED | NO | 0 | PARTIAL（遅刻・早退）の件数 |
| `count_absent` | INT UNSIGNED | NO | 0 | ABSENT の件数 |
| `count_undecided` | INT UNSIGNED | NO | 0 | UNDECIDED の実件数（`treat_undecided_as_absent_after_deadline` 適用前の生値）|
| `total_scheduled` | INT UNSIGNED | NO | 0 | 対象スケジュール数（`attendance_required = TRUE` かつ `status != CANCELLED` かつ `deleted_at IS NULL`）|
| `computed_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | 最終計算日時 |

**主キー**
```sql
PRIMARY KEY (user_id, scope_type, scope_id, year_month)
```

**インデックス**
```sql
INDEX idx_mas_scope_month (scope_type, scope_id, year_month)  -- チーム/組織の全メンバー統計取得用
INDEX idx_mas_user_month  (user_id, year_month)               -- 個人ダッシュボード用
```

**更新タイミング（イベント駆動）**
| トリガー | 対象レコード | 処理 |
|---------|------------|------|
| `PATCH /responses`（出欠回答・変更）| 当該 user_id × スケジュールの scope_type/scope_id × start_at の年月 | 該当月レコードを再計算して UPSERT |
| スケジュール `status → CANCELLED`（単発・ALL）| 当該スケジュールの全出欠対象ユーザー × 同スコープ × 同年月 | 同上 |
| スケジュール `deleted_at` 設定（論理削除）| 同上 | 同上 |
| `treat_undecided_as_absent_after_deadline` 設定変更 | — | **再計算不要**。`count_undecided` は生値のままのためフラグ変更の影響なし。API 取得時に動的適用する |

**統計 API での利用**
- `from`〜`to` クエリに対して、対応する `year_month` レコードの `SUM` を返す（O(period_months × members)）
- `from`/`to` が月の境界と一致しない端数月はその月レコードを使用せずオンザフライ計算で補正する
- `computed_at` を HTTP レスポンスヘッダーの `Last-Modified` に使用し、フロントエンドの不要な再取得を防ぐ

**制約・備考**
- `count_undecided` は `treat_undecided_as_absent_after_deadline` 適用前の生値を保存する。フラグを事前適用すると設定変更時に全レコードの大量再計算が必要になるため、この判断は API 取得時に動的適用する
- Phase 3 では未作成（オンザフライ計算で運用）。Phase 4 実装時にバックフィルバッチで既存データを一括計算して INSERT する
- チーム / 組織退会後も統計データは保持し、再加入時にそのまま利用可能とする

---

### ER図（テキスト形式）
```
teams / organizations / users (1) ──── (N) schedules   ※ team_id / organization_id / user_id の三者XOR
schedules (1) ──── (N) schedules              ※ parent_schedule_id（自己参照・繰り返し）
schedules (1) ──── (N) schedule_attendances
users (1) ──── (N) schedule_attendances
schedules (1) ──── (N) event_surveys
event_surveys (1) ──── (N) event_survey_responses
users (1) ──── (N) event_survey_responses
schedules (1) ──── (N) schedule_attendance_reminders
schedules (1) ──── (N) schedule_cross_refs         ※ source_schedule_id
schedules (0..1) ──── (N) schedule_cross_refs      ※ target_schedule_id
users / schedules ──── Google Calendar 連携テーブル（詳細は F08_external_integration.md 参照）
users (1) ──── (N) member_attendance_stats           ※ Phase 4+（scope_type/scope_id でチーム・組織両対応）
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/teams/{id}/schedules` | 必要（min_view_role 以上; デフォルト MEMBER+）| スケジュール一覧（期間指定）|
| POST | `/api/v1/teams/{id}/schedules` | 必要（MANAGE_SCHEDULES）| スケジュール作成（繰り返し含む）|
| GET | `/api/v1/teams/{id}/schedules/{scheduleId}` | 必要（当該スケジュールの min_view_role 以上）| スケジュール詳細 |
| PATCH | `/api/v1/teams/{id}/schedules/{scheduleId}` | 必要（MANAGE_SCHEDULES）| スケジュール更新（update_scope 指定）|
| DELETE | `/api/v1/teams/{id}/schedules/{scheduleId}` | 必要（自分が作成: MEMBER以上 / 他者作成: DELETE_OTHERS_CONTENT / 強制: ADMIN）| スケジュール論理削除（繰り返しは `?update_scope` で範囲指定）|
| POST | `/api/v1/teams/{id}/schedules/{scheduleId}/cancel` | 必要（ADMIN）| キャンセル（status = CANCELLED）。繰り返しは `?update_scope` で範囲指定 |
| GET | `/api/v1/teams/{id}/schedules/{scheduleId}/attendances` | 必要（ADMIN）| 出欠一覧・集計（ADMIN 用）|
| POST | `/api/v1/teams/{id}/schedules/{scheduleId}/cross-invite` | 必要（ADMIN）| 他チーム・組織へのスケジュール招待送信 |
| DELETE | `/api/v1/teams/{id}/schedules/{scheduleId}/cross-invite/{invitationId}` | 必要（ADMIN）| 送信済み招待のキャンセル |
| GET | `/api/v1/teams/{id}/schedule-invitations` | 必要（ADMIN）| 受信したスケジュール招待一覧 |
| POST | `/api/v1/teams/{id}/schedule-invitations/{invitationId}/accept` | 必要（ADMIN）| スケジュール招待を承認 |
| POST | `/api/v1/teams/{id}/schedule-invitations/{invitationId}/reject` | 必要（ADMIN）| スケジュール招待を拒否 |
| POST | `/api/v1/teams/{id}/schedule-invitations/{invitationId}/confirm` | 必要（ADMIN）| スケジュール招待を最終確認（PRIVATE チームへの招待時のみ。`{id}` は招待元チーム ID）|
| GET | `/api/v1/organizations/{id}/schedules` | 必要（min_view_role 以上; デフォルト MEMBER+）| 組織スケジュール一覧 |
| POST | `/api/v1/organizations/{id}/schedules` | 必要（MANAGE_SCHEDULES）| 組織スケジュール作成 |
| GET | `/api/v1/organizations/{id}/schedules/{scheduleId}` | 必要（当該スケジュールの min_view_role 以上）| 組織スケジュール詳細 |
| PATCH | `/api/v1/organizations/{id}/schedules/{scheduleId}` | 必要（MANAGE_SCHEDULES）| 組織スケジュール更新 |
| DELETE | `/api/v1/organizations/{id}/schedules/{scheduleId}` | 必要（自分が作成: MEMBER以上 / 他者作成: DELETE_OTHERS_CONTENT / 強制: ADMIN）| 組織スケジュール削除（繰り返しは `?update_scope` で範囲指定）|
| POST | `/api/v1/organizations/{id}/schedules/{scheduleId}/cancel` | 必要（ADMIN）| 組織スケジュールキャンセル（繰り返しは `?update_scope` で範囲指定）|
| GET | `/api/v1/organizations/{id}/schedules/{scheduleId}/attendances` | 必要（ADMIN）| チーム別出欠集計（個人情報なし）|
| PATCH | `/api/v1/schedules/{scheduleId}/responses` | 必要（当該スケジュールの min_response_role 以上）| 出欠を回答・変更（チーム/組織スコープ共通の統一エンドポイント）|
| GET | `/api/v1/schedules/{id}/stats` | 必要（当該スケジュールの min_response_role 以上）| スケジュール単位の出欠集計サマリー（件数のみ・treat_undecided フラグ適用済み）|
| POST | `/api/v1/schedules/{id}/remind` | 必要（ADMIN）| 未回答（UNDECIDED）メンバーへ即時リマインド通知 |
| GET | `/api/v1/my/calendar` | 必要 | 全チーム・組織横断のスケジュール一覧（自分の回答ステータス付き）|

> Google Calendar 連携 API（`/me/google-calendar/*` / `/me/calendar-sync-settings` / `/me/teams/{id}/calendar-sync` / `/me/organizations/{id}/calendar-sync`）の仕様は `F08_external_integration.md` Section 4 を参照。

| GET | `/api/v1/organizations/{id}/attendance-stats` | 必要（ADMIN）| 組織内チーム別の出席率（ダッシュボード用）|
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
  "min_view_role": "MEMBER+",
  "min_response_role": "MEMBER+",
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
  "min_view_role": "SUPPORTER+",
  "min_response_role": "MEMBER+",
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
      "visibility": "MEMBERS_ONLY",
      "min_view_role": "SUPPORTER+",
      "min_response_role": "MEMBER+",
      "attendance_required": false,
      "attendance_deadline": null,
      "my_response": null,
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
      "visibility": "MEMBERS_ONLY",
      "min_view_role": "MEMBER+",
      "min_response_role": "MEMBER+",
      "attendance_required": true,
      "attendance_deadline": "2026-04-03T23:59:59",
      "my_response": { "status": "UNDECIDED", "comment": null },
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

> - `my_response`: リクエスト者自身の出欠情報。`attendance_required = false` の場合、またはリクエスト者が `min_response_role` 未満のロールで出欠対象外の場合は null。形式: `{"status": "...", "comment": "..."}`。`comment` は `comment_option = HIDDEN` のスケジュールでは常に null
> - `attendance_summary`: 当該スケジュールの `min_response_role` 以上のロールを持つリクエスト者のみ返す（min_response_role 未満の場合は null）。内容は件数集計のみで個人情報は含まない
> - `min_view_role` に基づくフィルタリング: SUPPORTER は `min_view_role IN ('ANYONE', 'SUPPORTER+')` のスケジュールのみ返す。MEMBER は `min_view_role IN ('ANYONE', 'SUPPORTER+', 'MEMBER+')` を返す。DEPUTY_ADMIN / ADMIN はすべて（`ADMIN_ONLY` 含む）返す

---

#### `GET /api/v1/teams/{id}/schedules/{scheduleId}`

スケジュールの詳細情報を返す。出欠アンケート設問・リマインダー設定・クロスチーム招待状況も含む。

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 20,
    "title": "vs 東京FC 練習試合",
    "description": "ホームゲーム。ユニフォーム持参。",
    "location": "駒沢オリンピック公園陸上競技場",
    "start_at": "2026-04-05T13:00:00",
    "end_at": "2026-04-05T17:00:00",
    "all_day": false,
    "event_type": "MATCH",
    "visibility": "MEMBERS_ONLY",
    "min_view_role": "MEMBER+",
    "min_response_role": "MEMBER+",
    "status": "SCHEDULED",
    "attendance_required": true,
    "attendance_status": "READY",
    "attendance_deadline": "2026-04-03T23:59:59",
    "comment_option": "OPTIONAL",
    "parent_schedule_id": null,
    "recurrence_rule": null,
    "is_exception": false,
    "created_by": 1,
    "created_at": "2026-03-01T10:00:00Z",
    "updated_at": "2026-03-01T10:00:00Z",
    "my_response": { "status": "UNDECIDED", "comment": null },
    "attendance_summary": {
      "attending": 12,
      "partial": 2,
      "absent": 3,
      "undecided": 9
    },
    "surveys": [
      {
        "id": 5,
        "question": "バス乗り合いに参加しますか？",
        "question_type": "BOOLEAN",
        "options": null,
        "is_required": true,
        "sort_order": 1
      }
    ],
    "reminders": [
      { "id": 1, "remind_at": "2026-03-29T09:00:00", "is_sent": false },
      { "id": 2, "remind_at": "2026-04-02T09:00:00", "is_sent": false }
    ],
    "cross_invitations": [
      {
        "invitation_id": 100,
        "target_type": "TEAM",
        "target_id": 42,
        "target_name": "FC Tokyo Youth B",
        "status": "PENDING"
      }
    ]
  }
}
```

> - `attendance_status`: `GENERATING` の場合、フロントエンドは「出欠表を生成中...」を表示し、定期ポーリング（例: 3秒間隔で `GET /schedules/{id}` を再取得）で READY への遷移を確認する。`attendance_required = FALSE` の場合は常に `READY`
> - `attendance_summary`: 当該スケジュールの `min_response_role` 以上のロールを持つリクエスト者のみ返す（min_response_role 未満の場合は null）。内容は件数集計のみで個人情報は含まない
> - `reminders`: スケジュール作成者 / ADMIN のみ返す
> - `cross_invitations`: このスケジュールから送信した招待一覧（ADMIN のみ返す）。受信招待は `GET /teams/{id}/schedule-invitations` で取得

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

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 10,
    "title": "火木練習（会場変更）",
    "location": "第2グラウンド",
    "status": "SCHEDULED",
    "is_exception": true,
    "parent_schedule_id": 1,
    "updated_at": "2026-02-23T10:00:00Z"
  }
}
```

> - `THIS_ONLY`: 更新されたスケジュール自身（`is_exception = true`）を返す
> - `THIS_AND_FOLLOWING`: 新しく生成された親スケジュール（`parent_schedule_id = null`）を返す
> - `ALL` または省略: 更新された親スケジュールを返す

**`surveys` フィールドを含む場合の追加仕様**

`surveys` を省略した場合、既存の設問は変更されない。指定した場合は以下の差分更新ルールを適用する:
- `id` あり → 既存設問の更新（後述の安全判定に従う）
- `id` なし → 新規設問追加（常に許可）
- リストに含まれない既存設問の `id` → 削除（`force_clear_responses` 要否は後述）

`force_clear_responses` (boolean, default: `false`): 既存回答データがある場合に、設問の削除・`question_type` 変更・`options` 変更を強制的に許可するフラグ。`false` の場合、これらの操作があると 422 を返す。

> `surveys` 変更は `update_scope = THIS_ONLY`（単発スケジュールを含む）にのみ対応。`THIS_AND_FOLLOWING` / `ALL` の場合は 422（詳細は Section 5「アンケート設問更新フロー」参照）

> `status` フィールドに `"COMPLETED"` を指定することで、`SCHEDULED` → `COMPLETED` への手動遷移が可能（ADMIN のみ）。対象スケジュールが `CANCELLED` または既に `COMPLETED` の場合は 422。`THIS_AND_FOLLOWING` / `ALL` スコープでの一括 COMPLETED 設定は 422（自動完了バッチで対応）。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足（MANAGE_SCHEDULES なし）|
| 403 | ADMIN 以外が `status: "COMPLETED"` を手動設定しようとした |
| 404 | スケジュールが存在しない / 削除済み |
| 422 | `status = CANCELLED` のスケジュールを更新 |
| 422 | `status = COMPLETED` のスケジュールを更新（COMPLETED 状態からの再更新は ADMIN 含む全員不可・終端状態）|
| 422 | `status: "COMPLETED"` を `update_scope = THIS_AND_FOLLOWING / ALL` で一括設定しようとした（自動完了バッチで対応。手動 COMPLETED は THIS_ONLY のみ）|
| 422 | `surveys` フィールドを含む、かつ `update_scope = THIS_AND_FOLLOWING / ALL`（surveys 変更は THIS_ONLY のみ対応・Phase 4+ で拡張予定）|
| 422 | `surveys` の変更（削除 / `question_type` 変更 / `options` 変更）が必要だが対象設問に既存回答が存在し、かつ `force_clear_responses = false`（`error.code: survey_responses_exist`・影響設問 ID・回答件数を返す）|

---

#### `DELETE /api/v1/teams/{id}/schedules/{scheduleId}`

スケジュールを論理削除する。繰り返しスケジュールは `update_scope` クエリパラメータで削除範囲を指定する。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `update_scope` | String | `THIS_ONLY` | 繰り返しスケジュールの削除範囲（`THIS_ONLY` / `THIS_AND_FOLLOWING` / `ALL`）。単発スケジュールは無視される |

**レスポンス（204 No Content）**

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 他者が作成したスケジュールで DELETE_OTHERS_CONTENT 権限なし |
| 404 | スケジュールが存在しない / 削除済み |

---

#### `POST /api/v1/teams/{id}/schedules/{scheduleId}/cancel`

スケジュールをキャンセルする（`schedules.status = CANCELLED` に更新）。論理削除とは独立した操作であり、キャンセル後もスケジュール・出欠履歴は参照可能。繰り返しスケジュールは `update_scope` で範囲を指定する。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `update_scope` | String | `THIS_ONLY` | 繰り返しスケジュールのキャンセル範囲（`THIS_ONLY` / `THIS_AND_FOLLOWING` / `ALL`）。単発スケジュールは無視される |

**レスポンス（200 OK）**
```json
{
  "data": {
    "cancelled_count": 1
  }
}
```

> - `cancelled_count`: キャンセルされたスケジュール件数（THIS_ONLY の場合は常に 1、THIS_AND_FOLLOWING / ALL の場合は N 件）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN 権限なし |
| 404 | スケジュールが存在しない / 削除済み |
| 422 | 対象スケジュールがすでに `CANCELLED` または `COMPLETED` |

---

#### `POST /api/v1/organizations/{id}/schedules/{scheduleId}/cancel`

組織スケジュールをキャンセルする。クエリパラメータ・レスポンス・エラーレスポンスはチーム版（`POST /teams/{id}/schedules/{scheduleId}/cancel`）と同様。

---

#### `GET /api/v1/organizations/{id}/schedules`

チームスケジュール一覧（`GET /api/v1/teams/{id}/schedules`）と同様。クエリパラメータ（`from` / `to` / `event_type` / `status`）・レスポンス形式は同一。`organization_id` に紐づくスケジュールを返す。

---

#### `POST /api/v1/organizations/{id}/schedules`

チームスケジュール作成（`POST /api/v1/teams/{id}/schedules`）と同様。リクエストボディ・レスポンス形式は同一。内部では `organization_id` が設定され `team_id` は NULL となる。

---

#### `GET /api/v1/organizations/{id}/schedules/{scheduleId}`

チームスケジュール詳細（`GET /api/v1/teams/{id}/schedules/{scheduleId}`）と同様。レスポンス形式は同一。ただし以下の差分がある:
- `cross_invitations` フィールドは含まない（クロスチーム招待機能はチームスコープのみ）

---

#### `PATCH /api/v1/organizations/{id}/schedules/{scheduleId}`

チームスケジュール更新（`PATCH /api/v1/teams/{id}/schedules/{scheduleId}`）と同様。リクエストボディ・`update_scope` の挙動・レスポンス・エラーレスポンスは同一。

---

#### `DELETE /api/v1/organizations/{id}/schedules/{scheduleId}`

チームスケジュール削除（`DELETE /api/v1/teams/{id}/schedules/{scheduleId}`）と同様。クエリパラメータ（`?update_scope`）・レスポンス・エラーレスポンスは同一。内部では `organization_id` が対象スコープとなる。

---

#### `PATCH /api/v1/schedules/{scheduleId}/responses`

チーム・組織スコープを問わず、スケジュール ID だけで出欠を回答・変更する統一エンドポイント。サーバー側でスケジュールの所属スコープを解決し、リクエスト者が当該スケジュールの `min_response_role` 以上のロールを持つことを確認する。

**リクエストボディ**
```json
{
  "status": "ATTENDING",
  "comment": null,
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
    "comment": null,
    "responded_at": "2026-04-01T10:00:00Z"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー・`is_required` の設問に未回答・`comment_option = REQUIRED` で `comment` が空 |
| 403 | 当該スケジュールの `min_response_role` 未満のロール（回答権限不足）|
| 404 | スケジュールが存在しない / 論理削除済み |
| 409 | `attendance_deadline` を過ぎている（ただし ADMIN が COMPLETED スケジュールの出欠修正を行う場合は deadline チェックをスキップ）|
| 422 | `attendance_required = false` のスケジュールへの回答 |
| 422 | 対象スケジュールのチーム / 組織がアーカイブ済み |
| 422 | `status = CANCELLED` のスケジュール（ADMIN 含む全員不可）|
| 422 | `status = COMPLETED` のスケジュール（ADMIN 以外。ADMIN は COMPLETED 後の出欠手動修正のため許可）|

---

#### `GET /api/v1/teams/{id}/schedules/{scheduleId}/attendances`

チームメンバー全員の出欠回答を個人単位で返す。補足コメント（comment）とアンケート回答（survey_responses）を含む。ADMIN のみ参照可能。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `status` | String | — | 出欠ステータスで絞り込み（ATTENDING / PARTIAL / ABSENT / UNDECIDED）|
| `sort` | String | `name` | 並び順（`name` / `responded_at` / `status`）|

**レスポンス（200 OK）**
```json
{
  "data": {
    "schedule_id": 20,
    "comment_option": "OPTIONAL",
    "summary": {
      "attending": 12,
      "partial": 2,
      "absent": 3,
      "undecided": 9
    },
    "members": [
      {
        "user_id": 1,
        "display_name": "田中 太郎",
        "status": "PARTIAL",
        "comment": "15分遅刻します",
        "responded_at": "2026-04-01T10:00:00Z",
        "survey_responses": [
          {
            "event_survey_id": 5,
            "question": "バス乗り合いに参加しますか？",
            "question_type": "BOOLEAN",
            "answer_text": "true",
            "answer_options": null
          }
        ]
      },
      {
        "user_id": 2,
        "display_name": "鈴木 花子",
        "status": "ABSENT",
        "comment": "体調不良",
        "responded_at": "2026-03-31T15:00:00Z",
        "survey_responses": [
          {
            "event_survey_id": 5,
            "question": "バス乗り合いに参加しますか？",
            "question_type": "BOOLEAN",
            "answer_text": "false",
            "answer_options": null
          }
        ]
      },
      {
        "user_id": 3,
        "display_name": "山田 次郎",
        "status": "UNDECIDED",
        "comment": null,
        "responded_at": null,
        "survey_responses": []
      }
    ]
  }
}
```

> - `comment_option` をレスポンスに含め、フロントエンドがコメント欄の表示制御に使用できるようにする
> - `comment` は `comment_option = HIDDEN` の場合は常に `null`（送信されなかったため）
> - `survey_responses` は回答済みの設問のみ返す（未回答の設問は含まない）
> - UNDECIDED メンバーも一覧に含める（未回答者の把握のため）

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
      "partial": 5,
      "absent": 10,
      "undecided": 15
    },
    "by_team": [
      {
        "team_id": 1,
        "team_name": "Aチーム",
        "attending": 20,
        "partial": 3,
        "absent": 4,
        "undecided": 6
      },
      {
        "team_id": 2,
        "team_name": "Bチーム",
        "attending": 25,
        "partial": 2,
        "absent": 6,
        "undecided": 9
      },
      {
        "team_id": null,
        "team_name": "チーム未所属（組織直接メンバー）",
        "attending": 0,
        "partial": 0,
        "absent": 0,
        "undecided": 0
      }
    ]
  }
}
```

> - 個人レベルの出欠詳細（user_id・名前・個別ステータス）は含まない
> - `team_id: null`「チーム未所属（組織直接メンバー）」グループ: 組織に直接所属しているがいずれのチームにも属さないメンバー（組織管理者・事務局スタッフ等）の集計。出欠通知・回答・集計は他チームメンバーと同一フローで処理される（スケジュール作成フロー step 8i・出欠回答フロー step 6 参照）
> - フロントエンド補足: `GET /my/calendar` が組織スコープのスケジュールも返すため、組織直接メンバーのダッシュボードには既存の横断ビューで組織スケジュールが表示される（「組織全体の予定」専用セクションの表示分割はフロントエンドの実装判断）

---

#### `GET /api/v1/schedules/{id}/stats`

スケジュール単位の出欠集計サマリーを返す。当該スケジュールの `min_response_role` 以上のロールを持つメンバーが参照可能。個別メンバーの出欠一覧（個人名付き）は ADMIN 専用の `GET /teams/{id}/schedules/{scheduleId}/attendances` を使用する。

> **自動リマインダーとの違い**: `schedule_attendance_reminders` は期日前の定期自動通知。このエンドポイントは出欠回答権限を持つメンバーが現在の回答状況（件数）を確認するためのサマリー取得。なお未回答者へのリマインド送信ボタン（`POST /remind`）は ADMIN のみ表示する。

**レスポンス（200 OK）**
```json
{
  "data": {
    "schedule_id": 20,
    "attendance_deadline": "2026-04-03T23:59:59",
    "deadline_passed": false,
    "treat_undecided_as_absent": false,
    "total_members": 30,
    "count_attending": 16,
    "count_partial": 4,
    "count_absent": 4,
    "count_undecided": 6,
    "response_rate": 80.0,
    "attendance_rate": 83.3,
    "strict_rate": 66.7
  }
}
```

> - `treat_undecided_as_absent`: チーム/組織の `treat_undecided_as_absent_after_deadline` フラグ AND `deadline_passed` の AND 条件（実際に適用されている計算モードをフロントエンドへ通知）
> - `total_members`: `attendance_required = TRUE` かつ `status != CANCELLED` かつ `deleted_at IS NULL` のスケジュールに対して `schedule_attendances` が存在する全メンバー数
> - `response_rate`: (count_attending + count_partial + count_absent) / total_members × 100
> - **`treat_undecided_as_absent = false` の場合**: `attendance_rate` = (count_attending + count_partial) / (count_attending + count_partial + count_absent) × 100
> - **`treat_undecided_as_absent = true` の場合**: `attendance_rate` = (count_attending + count_partial) / total_members × 100（UNDECIDED を ABSENT として扱う）
> - `strict_rate`: `attendance_rate` と同じ分母ロジックで `count_attending` のみを分子に使用
> - `count_undecided > 0` の場合、フロントエンドは「未回答者にリマインドを送る」ボタンを表示する

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 当該スケジュールの `min_response_role` 未満のロール |
| 404 | スケジュールが存在しない / 論理削除済み |
| 422 | `attendance_required = FALSE` のスケジュール（出欠確認対象外） |
| 422 | 対象スケジュールのチーム / 組織がアーカイブ済み |

---

#### `POST /api/v1/schedules/{id}/remind`

未回答（UNDECIDED）のメンバー全員へ即時リマインド通知を送信する。ADMIN のみ実行可能。

> **自動リマインダーとの違い**: `schedule_attendance_reminders` テーブルに基づく定期自動通知（バッチ処理）とは独立した機能。管理者が任意のタイミングで手動実行する即時通知であり、`schedule_attendance_reminders` レコードは作成しない。

**リクエストボディ**: なし

**レスポンス（200 OK）**
```json
{
  "data": {
    "notified_count": 6
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN 以外が実行しようとした |
| 404 | スケジュールが存在しない / 論理削除済み |
| 422 | `attendance_required = FALSE` のスケジュール（出欠確認対象外）|
| 422 | 対象スケジュールのチーム / 組織がアーカイブ済み |

> - `notified_count = 0` の場合（全員回答済み）も 200 OK を返す
> - 通知の実際の送信はバックグラウンド処理（`@Async`）で行い、API はカウントを即時返却する

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
| 409 | 同一 source + target への重複招待（PENDING または ACCEPTED）。REJECTED / CANCELLED 後の再招待は既存行を PENDING に更新（409 にはならない）|
| 422 | `status = CANCELLED` または `COMPLETED` のスケジュールへの招待 |

---

#### `DELETE /api/v1/teams/{id}/schedules/{scheduleId}/cross-invite/{invitationId}`

送信済みのクロスチームスケジュール招待をキャンセルする（`schedule_cross_refs.status = CANCELLED`）。招待が `PENDING` または `AWAITING_CONFIRMATION` 状態の場合のみ操作可能。

**レスポンス（204 No Content）**

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN 権限なし |
| 404 | 招待が存在しない / 対象スケジュールのスコープ外 |
| 422 | 招待がすでに `ACCEPTED` / `REJECTED` / `CANCELLED` 状態 |

> `AWAITING_CONFIRMATION`（招待先が承認済み、招待元が確認待ち）の状態でも招待元はキャンセル可能。招待先チームに「招待がキャンセルされました」と通知する

---

#### `GET /api/v1/teams/{id}/schedule-invitations`

受信したスケジュール招待一覧（他チームから招待された一覧）。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `status` | String | `PENDING` | PENDING / AWAITING_CONFIRMATION / ACCEPTED / REJECTED / CANCELLED |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "invitation_id": 100,
      "source_team_id": 5,
      "source_team_name": "FC Tokyo Youth",
      "source_team_icon_url": "https://...",
      "invited_by_name": "山田 太郎",
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

> **承認前の開示情報（プライバシー設計）**: 招待受信側に開示するのは、招待元チーム名・チームアイコン・招待者名・当該スケジュール情報（タイトル・日時・場所）・招待メッセージのみ。招待元チームのメンバー一覧・他のスケジュール・統計データは含まない（ACCEPTED 後も招待経由では非公開；各チームの通常公開ポリシーに従う）

---

#### `POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/accept`

招待を承認する。招待先チームの `visibility` によってレスポンスが異なる。

**レスポンス（招待先チームが PUBLIC / ORGANIZATION_ONLY の場合）（201 Created）**
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

**レスポンス（招待先チームが PRIVATE の場合）（200 OK）**
```json
{
  "data": {
    "invitation_id": 100,
    "status": "AWAITING_CONFIRMATION",
    "created_schedule_id": null
  }
}
```

> - ミラースケジュールはまだ作成されない。招待元 ADMIN が `POST /confirm` で最終確認した後に作成される
> - 招待元チームの ADMIN に「招待が承認されました。確認してください」と通知する

---

#### `POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/confirm`

招待を最終確認する（送信側 ADMIN 専用）。`AWAITING_CONFIRMATION` 状態の招待に対して、招待元チームの ADMIN が最終合意を行う。確認後にミラースケジュールが作成される。

> PRIVATE チームへの招待時のみ使用する。`{id}` は**招待元**チームの ID。

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

> `created_schedule_id`: 招待先チームのカレンダーに新規作成されたミラースケジュールの ID

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN 権限なし / `{id}` が招待元チームと不一致 |
| 404 | 招待が存在しない |
| 422 | 招待が `AWAITING_CONFIRMATION` 状態でない（PENDING / ACCEPTED / REJECTED / CANCELLED）|

---

#### `POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/reject`

受信したクロスチームスケジュール招待を拒否する。招待が `PENDING` 状態の場合のみ操作可能。

**レスポンス（204 No Content）**

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN 権限なし |
| 404 | 招待が存在しない / 招待先が自チームでない |
| 422 | 招待がすでに `AWAITING_CONFIRMATION` / `ACCEPTED` / `REJECTED` / `CANCELLED` 状態（`AWAITING_CONFIRMATION` は既に承認済みのため `confirm` か `cancel` のみ許容）|

> Google Calendar 連携 API の仕様（`/me/google-calendar/*` / `/me/calendar-sync-settings` / `/me/teams/{id}/calendar-sync` / `/me/organizations/{id}/calendar-sync`）は `F08_external_integration.md` Section 4 を参照。

---

#### `GET /api/v1/my/calendar`

全チーム・組織・個人横断のスケジュールを統合して返す。チーム・組織スケジュールには自分の現在の回答ステータス（`my_response`）を LEFT JOIN して返す。個人スケジュール（`scope_type = "PERSONAL"`）は常に `my_response = null`。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 今日 | 取得開始日 |
| `to` | ISO 8601 date | 30日後 | 取得終了日 |
| `attendance_required_only` | Boolean | false | 出欠確認が必要なもののみ |
| `undecided_only` | Boolean | false | 自分が未回答のもののみ |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 10,
      "scope_type": "TEAM",
      "scope_id": 1,
      "scope_name": "FC Tokyo Youth",
      "title": "火木練習",
      "start_at": "2026-04-01T19:00:00",
      "end_at": "2026-04-01T21:00:00",
      "all_day": false,
      "event_type": "PRACTICE",
      "location": null,
      "status": "SCHEDULED",
      "min_view_role": "SUPPORTER+",
      "min_response_role": "MEMBER+",
      "attendance_required": false,
      "attendance_deadline": null,
      "my_response": null
    },
    {
      "id": 20,
      "scope_type": "TEAM",
      "scope_id": 1,
      "scope_name": "FC Tokyo Youth",
      "title": "vs 東京FC 練習試合",
      "start_at": "2026-04-05T13:00:00",
      "end_at": "2026-04-05T17:00:00",
      "all_day": false,
      "event_type": "MATCH",
      "location": "駒沢オリンピック公園陸上競技場",
      "status": "SCHEDULED",
      "min_view_role": "MEMBER+",
      "min_response_role": "MEMBER+",
      "attendance_required": true,
      "attendance_deadline": "2026-04-03T23:59:59",
      "my_response": { "status": "UNDECIDED", "comment": null }
    },
    {
      "id": 500,
      "scope_type": "PERSONAL",
      "scope_id": null,
      "scope_name": "個人",
      "title": "歯医者の予約",
      "start_at": "2026-04-10T14:00:00",
      "end_at": "2026-04-10T15:00:00",
      "all_day": false,
      "event_type": "OTHER",
      "location": "〇〇歯科",
      "status": "SCHEDULED",
      "min_view_role": null,
      "min_response_role": null,
      "attendance_required": false,
      "attendance_deadline": null,
      "my_response": null
    }
  ]
}
```

> - `scope_type` / `scope_id` / `scope_name`: スケジュールが属するスコープの情報（横断表示用）。`scope_type = "PERSONAL"` の場合、`scope_id = null`・`scope_name = "個人"` 固定
> - `my_response`: リクエスト者自身の出欠情報。`scope_type = "PERSONAL"` の場合・`attendance_required = false` の場合・リクエスト者が `min_response_role` 未満のロールで出欠対象外の場合は null。形式: `{"status": "...", "comment": "..."}`。`comment_option = HIDDEN` のスケジュールでは `comment` は常に null
> - `min_view_role` / `min_response_role`: `scope_type = "PERSONAL"` の場合は null（個人スコープに閲覧/回答権限の概念がないため）。フロントエンドはこれらが null の場合は回答 UI を非表示にする
> - `attendance_summary` は含まない（個人ビューのため集計情報は非表示）
> - `from`〜`to` の期間内で `start_at` が一致するスケジュールを全チーム・組織・個人横断で返す。チーム・組織スケジュールには `min_view_role` に基づくフィルタリングを適用する。個人スケジュールは `schedules.user_id = current_user_id` で絞り込む
> - `attendance_required_only = true` および `undecided_only = true` フィルタは個人スケジュールを除外する（`attendance_required` が常に false のため）

---

#### `GET /api/v1/organizations/{id}/attendance-stats`

組織内の全チームの出席率をダッシュボード用に返す。ADMIN のみ参照可能。個人情報は含まず、チーム単位の集計値のみ返す。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 3ヶ月前 | 集計開始日（inclusive）|
| `to` | ISO 8601 date | 今日 | 集計終了日（inclusive）|
| `team_id` | Long | — | 特定チームに絞り込み（省略時は全チーム）|

> `from` 〜 `to` の期間は最長 12ヶ月。超過する場合は 400 Bad Request を返す。

**レスポンス（200 OK）**
```json
{
  "data": {
    "period": { "from": "2025-11-21", "to": "2026-02-21" },
    "organization_summary": {
      "total_schedules": 48,
      "avg_attendance_rate": 80.3,
      "avg_strict_rate": 64.5
    },
    "by_team": [
      {
        "team_id": 1,
        "team_name": "FC Tokyo Youth A",
        "total_required": 24,
        "member_count": 20,
        "avg_attendance_rate": 82.5,
        "avg_strict_rate": 66.7
      },
      {
        "team_id": 2,
        "team_name": "FC Tokyo Youth B",
        "total_required": 24,
        "member_count": 18,
        "avg_attendance_rate": 78.0,
        "avg_strict_rate": 62.2
      }
    ]
  }
}
```

> - `avg_attendance_rate` = チーム内全メンバーの出席率の平均値
> - `avg_strict_rate` = チーム内全メンバーの完全出席率の平均値
> - `total_required` は `attendance_required = TRUE` かつ `status != CANCELLED` かつ `deleted_at IS NULL` のスケジュール数
> - `member_count` は集計期間内に1件以上の `schedule_attendances` レコードが存在するメンバー数

---

#### `GET /api/v1/teams/{id}/attendance-stats`

チームメンバー全員の出席率をダッシュボード用に返す。ADMIN のみ参照可能。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 3ヶ月前 | 集計開始日（inclusive）|
| `to` | ISO 8601 date | 今日 | 集計終了日（inclusive）|
| `user_id` | Long | — | 特定メンバーに絞り込み（省略時は全員）|

> `from` 〜 `to` の期間は最長 12ヶ月。超過する場合は 400 Bad Request を返す。

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
> - `total_required` は `attendance_required = TRUE` かつ `status != CANCELLED` かつ `deleted_at IS NULL` のスケジュール数

---

#### `GET /api/v1/me/attendance-stats`

自分の出席率をチーム・組織別および全体でまとめて返す。個人ダッシュボード用。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 3ヶ月前 | 集計開始日（inclusive）|
| `to` | ISO 8601 date | 今日 | 集計終了日（inclusive）|

> `from` 〜 `to` の期間は最長 12ヶ月。超過する場合は 400 Bad Request を返す。

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

**集計期間の制約**
- デフォルト: `from` = 3ヶ月前、`to` = 今日
- 最長: 12ヶ月（`from` 〜 `to` が 365日超の場合は 400 Bad Request）
- 各ダッシュボード（組織 / チーム / 個人）で任意の期間を指定可能

**UNDECIDED の扱い（`treat_undecided_as_absent_after_deadline` フラグ）**

チーム/組織の `treat_undecided_as_absent_after_deadline` 設定（BOOLEAN, DEFAULT FALSE）と `attendance_deadline` の組み合わせで計算モードが変わる。

| 条件 | attendance_rate の計算式 | strict_rate の計算式 |
|------|--------------------------|----------------------|
| フラグ `FALSE`（デフォルト）または `attendance_deadline IS NULL` | (ATTENDING + PARTIAL) / (ATTENDING + PARTIAL + ABSENT) × 100 | ATTENDING / (ATTENDING + PARTIAL + ABSENT) × 100 |
| フラグ `TRUE` かつ `attendance_deadline < NOW()` | (ATTENDING + PARTIAL) / total_members × 100 | ATTENDING / total_members × 100 |

- `total_members`: `schedule_attendances` に登録されている全メンバー数（UNDECIDED 含む）
- `undecided` は残カウントとして常に返し、フロントエンドで「未回答あり」の警告表示および `POST /remind` ボタンの表示制御に使用する
- ダッシュボード集計（`GET /teams/{id}/attendance-stats` 等）もスケジュール単位で同フラグを適用して積算する

### スケジュール作成フロー（単発）

```
1. POST /api/v1/teams/{id}/schedules または POST /api/v1/organizations/{id}/schedules を受付
   （以降のフローは両スコープ共通。team_id / organization_id のどちらが設定されるかが異なるのみ）
2. MANAGE_SCHEDULES 権限を確認
3. バリデーション（start_at < end_at、title 必須 等）
4. `comment_option` が省略されている場合:
   同一スコープの直近スケジュール（attendance_required = TRUE、deleted_at IS NULL）を取得し
   その `comment_option` を適用。存在しない場合は 'OPTIONAL'
4a. `min_view_role` が省略されている場合:
   チーム/組織の `default_schedule_min_view_role` 設定を取得して適用。設定がない場合は 'MEMBER+'
4b. `min_response_role` が省略されている場合:
   チーム/組織の `default_schedule_min_response_role` 設定を取得して適用。設定がない場合は 'MEMBER+'
5. schedules に INSERT（recurrence_rule = NULL、parent_schedule_id = NULL）
6. surveys が存在する場合:
   a. attendance_required = FALSE の場合は 400（surveys は attendance_required = TRUE のスケジュールのみ設定可）
   b. event_surveys を全件 INSERT
7. reminders が存在する場合:
   a. 各 remind_at が attendance_deadline（または start_at）より前か確認 → 違反は 400
   b. schedule_attendance_reminders を全件 INSERT
8. attendance_required = TRUE の場合:
   a. schedules.attendance_status = 'GENERATING' に UPDATE
   b. 以下を @Async で実行（メインのレスポンスをブロックしない）:
      i.  スコープに応じて、min_response_role 以上のロールを持つ対象ユーザーを取得:
          - チームスコープ: 当該チームのメンバーのうち min_response_role 以上のロールを持つ全員
          - 組織スコープ: 組織に所属するメンバーのうち min_response_role 以上のロールを持つ全員
            （全所属チームのメンバー + 組織直接所属メンバー、重複は除外）
          ※ ロール判定基準: SUPPORTER+ ≧ SUPPORTER, MEMBER+ ≧ MEMBER, ADMIN_ONLY ≧ DEPUTY_ADMIN
      ii. schedule_attendances を 500件単位のチャンクに分割してバルク INSERT（status='UNDECIDED'）
      iii. 全チャンク完了後: schedules.attendance_status = 'READY' に UPDATE
      iv. プッシュ通知「出欠確認が届きました」を送信（通知機能実装後）
          ※ 組織スコープの場合、直接所属メンバー（team_id を持たない組織メンバー）も送信対象に含まれる
          ※ メール通知は通知機能 Feature Doc で定義する送信可否設定に従う
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
4. `comment_option` が省略されている場合: 単発フロー step 4 と同様に同一スコープ直近設定を自動適用
4a. `min_view_role` が省略されている場合: 単発フロー step 4a と同様にチーム/組織設定を自動適用
4b. `min_response_role` が省略されている場合: 単発フロー step 4b と同様にチーム/組織設定を自動適用
5. 親スケジュールを schedules に INSERT（recurrence_rule を保存、parent_schedule_id = NULL）
6. recurrence_rule から展開予定件数を事前算出し generated_count として保持（@Async 開始前・同期処理で実施）:
    - 展開範囲: 作成日から最大12ヶ月先（または end_date / count に達した方が先）
    - 上限 200件; 超過する場合は超過分を切り捨て generated_count = 200 とし end_date を自動調整
7. recurrence_rule に従って子スケジュールを展開（@Async）:
   a. 6. で算出した件数分を schedules に INSERT（parent_schedule_id = 親 ID、recurrence_rule = NULL）
   b. end_date を自動調整した場合はユーザーに通知
8. attendance_required = TRUE の場合: 全子スケジュールに対して単発フロー step 8 を適用
9. Google カレンダー個人同期: 全子スケジュールに対して単発フロー step 9 を適用
10. audit_logs に SCHEDULE_CREATED を記録
    metadata: {"recurrence_type": "WEEKLY", "generated_count": 74}
11. 201 Created を返す（generated_count を含む; @Async の展開処理は非同期で継続）
```

---

### 出欠回答フロー（`PATCH /api/v1/schedules/{scheduleId}/responses`）

チーム・組織スコープ共通の統一フロー。

```
1. PATCH /api/v1/schedules/{scheduleId}/responses を受付
2. スケジュールを schedules テーブルから取得。存在しない / deleted_at IS NOT NULL → 404
3. スケジュールの team_id / organization_id からスコープを解決
4. スケジュールのステータス確認:
   - status = CANCELLED → 422（ADMIN 含む全員不可）
   - status = COMPLETED かつ操作者が ADMIN → 許可（ADMIN による COMPLETED 後の出欠手動修正）
   - status = COMPLETED かつ操作者が ADMIN 以外 → 422
   - status = SCHEDULED → 次ステップへ
5. attendance_required = TRUE か確認 → FALSE は 422
6. リクエスト者のスコープ内ロールが min_response_role 以上か確認 → 不足は 403
   - チームスコープ: 当該チームへの直接所属ロールで判定
   - 組織スコープ: 組織直接所属またはいずれかの所属チームが当該組織に属している場合のロールで判定
   ※ GUEST は min_response_role = 'SUPPORTER+' でも不可（GUEST は SUPPORTER 未満のため）
7. attendance_deadline が設定されている場合: deadline < NOW() であれば 409
   （ただし step 4 で ADMIN + COMPLETED として許可された場合は deadline チェックをスキップする）
8. comment_option に応じて comment をバリデーション:
   - HIDDEN   → comment 値を無視（保存時に NULL に上書き）
   - OPTIONAL → バリデーションなし
   - REQUIRED → comment が null または空文字の場合 400
9. schedule_attendances を UPSERT（UNIQUE KEY: schedule_id + user_id）
10. survey_responses が含まれる場合:
    a. is_required = TRUE の全設問に回答があるか確認 → 未回答は 400
    b. event_survey_responses を UPSERT
11. responded_at が NULL の場合（初回回答）: NOW() を設定
12. 200 OK を返す
```

---

### アンケート設問更新フロー（PATCH 時に `surveys` フィールドが含まれる場合）

適用範囲: 単発スケジュール、または `update_scope = THIS_ONLY` の場合のみ。`THIS_AND_FOLLOWING` / `ALL` は 422 を返す。

```
[設問変更の差分計算と安全判定]
1. DB の既存 event_surveys（schedule_id = 対象スケジュール）を取得
2. リクエストの surveys 配列と照合:
   - id なし → 新規追加対象（常に許可・schedule_id 単位で最大10件超過時は 400）
   - id あり かつ DB に存在 → 更新対象（以下の安全判定へ）
   - DB に存在するが request に含まれていない id → 削除対象（以下の安全判定へ）
3. 操作の安全性を判定:
   ■ 安全（event_survey_responses の有無に関わらず常に許可）:
     - question テキスト変更（`question` フィールドのみ）
     - is_required / sort_order 変更
   ■ 危険（event_survey_responses が存在する場合は force_clear_responses = true が必要）:
     - question_type の変更（answer_text / answer_options と型不整合になる）
     - options 配列の変更（既存の answer_options の選択値が無効になる可能性がある）
     - 設問の削除（関連する event_survey_responses が消去される）
4. 危険な操作が 1 件以上あり、かつ対象設問に event_survey_responses > 0 の場合:
   a. force_clear_responses = false（デフォルト）→ 422 を返す:
      {
        "error": {
          "code": "survey_responses_exist",
          "affected_surveys": [
            { "id": 1, "question": "バス乗り合いに参加しますか？", "response_count": 12 }
          ]
        }
      }
   b. force_clear_responses = true → 次ステップへ進む

[設問変更の実行]
5. 削除対象: event_surveys を DELETE
   （event_survey_responses は FK ON DELETE CASCADE で自動物理削除。force_clear_responses = true が保証された後のみ実行）
6. 危険な更新対象（question_type / options 変更）:
   当該設問の event_survey_responses を先に DELETE → event_surveys を UPDATE
7. 安全な更新対象（question / is_required / sort_order 変更）:
   event_surveys を直接 UPDATE（responses への影響なし）
8. 新規追加対象: event_surveys に INSERT
9. force_clear_responses = true かつ responses が削除された場合:
   影響を受けたユーザー（削除された responses の user_id）へ通知
   「アンケート設問が変更されました。再回答をお願いします。」（通知機能 Feature Doc 参照）
```

---

### `min_response_role` 変更時の `schedule_attendances` 更新フロー

`PATCH /teams/{id}/schedules/{scheduleId}` で `min_response_role` が変更された場合、かつ `attendance_required = TRUE` の場合のみ適用する。

```
[ロール緩和時（対象拡大: 例 MEMBER+ → SUPPORTER+）]
1. 新しい min_response_role の適用範囲に入るが、既存の schedule_attendances にレコードがない
   ユーザーを特定する（既存 UNIQUE KEY: schedule_id + user_id でチェック）
2. 対象ユーザーが存在する場合:
   a. schedules.attendance_status = 'GENERATING' に UPDATE
   b. 以下を @Async で実行（作成フロー step 8 と同一のバックグラウンド処理パターン）:
      i.  新たに対象となったユーザーリストを取得（スコープに応じて team / org メンバー解決）
      ii. schedule_attendances を 500件単位のチャンクでバルク INSERT（status = 'UNDECIDED'）
          UNIQUE KEY により既存レコードとの競合は INSERT IGNORE で安全にスキップ
      iii. 全チャンク完了後: schedules.attendance_status = 'READY' に UPDATE
      iv.  追加対象ユーザーへ通知「出欠確認が届きました（対象に追加されました）」（通知機能実装後）

[ロール制限時（対象縮小: 例 SUPPORTER+ → MEMBER+）]
- 自動削除は行わない。既存の schedule_attendances レコードはすべて保持する。
  ・既に回答済み（ATTENDING / PARTIAL / ABSENT）のレコードは出欠履歴として有意義なため削除しない
  ・UNDECIDED レコードのみ削除する条件付き削除は Phase 4+ で対応（削除コスト vs メリット分析要）
- 集計（GET /schedules/{id}/stats）時に「対象外ロールの UNDECIDED レコードが含まれる」状態になるが
  Phase 3 では許容する。厳密な集計が必要な場合は min_response_role フィルタをクエリに適用する

[update_scope ごとの適用範囲]
- THIS_ONLY（単発含む）: 当該スケジュール 1 件に対して上記フローを実行
- THIS_AND_FOLLOWING: 新しい親シリーズの子スケジュールは step 4a（単発フロー step 8 の適用）で処理済み。
  追加フロー不要
- ALL: is_exception = FALSE の全子スケジュールに対して上記フロー（緩和時）または無処理（制限時）を
  @Async で適用（子スケジュール数 × 新対象メンバー数の積で処理量が大きくなる可能性があるため非同期必須）
```

---

### 繰り返しスケジュール更新フロー

```
[THIS_ONLY]
0. 権限確認: 操作者が ADMIN、または created_by = 操作者、または MANAGE_SCHEDULES 権限を持つこと（なければ 403）
1. 当該スケジュールのみ UPDATE
2. is_exception = TRUE に設定
3. 当該スケジュールを同期しているユーザーの Google Calendar イベントを更新（@Async）
3a. クロスチームリンクの確認（start_at / end_at / location / title のいずれかが変更された場合のみ実行）:
   - 当該スケジュールを source_schedule_id とする schedule_cross_refs（status = ACCEPTED）を取得
   - 存在する場合:
     ① target_schedule の所属チーム/組織 ADMIN に通知「招待スケジュールの内容が変更されました」
       （通知ペイロードに変更フィールド名・変更後の値を含める。フロントエンドが「変更あり」バッジを表示するための差分情報として使用）
     ② target_schedule_id の schedule_attendances を持つメンバーにも同通知を送信
   - mirror スケジュール（target_schedule_id）は自動更新しない。target チームの ADMIN が手動で対応する
   ※ メール通知の送信可否は通知システム Feature Doc の重要度設定に従う
3b. min_response_role が変更された場合（attendance_required = TRUE のみ）:
   Section 5「min_response_role 変更時の schedule_attendances 更新フロー」の [THIS_ONLY] を適用
4. audit_logs に SCHEDULE_UPDATED を記録（metadata: {"update_scope": "THIS_ONLY"}）

[THIS_AND_FOLLOWING]
0. 権限確認: 操作者が ADMIN、または created_by = 操作者、または MANAGE_SCHEDULES 権限を持つこと（なければ 403）
1. 当該スケジュールの start_at 以降の全子スケジュール（is_exception の有無問わず）を論理削除
   （is_exception = TRUE の孤立データが残らないよう、削除前に関連する schedule_attendances / event_survey_responses も CASCADE で削除済みであることを確認）
2. 既存の親スケジュールの recurrence_rule を以下の通り更新し、シリーズを当日前日で打ち切る（ルール変換）:
   - end_type = DATE の場合: end_date = 当該スケジュールの start_at の前日
   - end_type = COUNT の場合: end_type を COUNT → DATE に変換し、end_date = 当該スケジュールの start_at の前日
   - end_type = NEVER の場合: end_type を NEVER → DATE に変換し、end_date = 当該スケジュールの start_at の前日
3. 変更内容で新しい親スケジュールを INSERT（当日以降を新しい別シリーズとして作成。新たに採番された parent_id を持つ）
4. 新しい親の recurrence_rule に従って子スケジュールを再展開（@Async）
4a. attendance_required = TRUE の場合: 新しい子スケジュールに対して単発フロー step 8 を適用（min_response_role 以上のロールを持つメンバーに schedule_attendances を一括 INSERT）
5. 削除された子スケジュールの Google Calendar イベントを削除（@Async）
6. 新しい子スケジュールを Google カレンダーに追加（@Async）
6a. クロスチームリンクの確認:
   - 論理削除した子スケジュールのうち、source_schedule_id として schedule_cross_refs（status = ACCEPTED）が存在するものを取得
   - 存在する場合:
     ① target_schedule の所属チーム/組織 ADMIN に通知「招待スケジュールが変更・再作成されました」
     ② target_schedule_id の schedule_attendances を持つメンバーにも同通知を送信
   - mirror スケジュール（target_schedule_id）は自動更新しない
7. audit_logs に SCHEDULE_UPDATED を記録（metadata: {"update_scope": "THIS_AND_FOLLOWING"}）

[ALL]
0. 権限確認: 操作者が ADMIN、または created_by = 操作者、または MANAGE_SCHEDULES 権限を持つこと（なければ 403）
1. 親スケジュールを UPDATE（変更フィールドのみ）
2. is_exception = FALSE の全子スケジュールを同一フィールドで UPDATE
   （`comment_option` が変更対象に含まれる場合は is_exception = FALSE のスケジュールにのみ伝播。
    is_exception = TRUE の子スケジュールは個別設定を保持するため更新しない）
3. 全子スケジュールの Google Calendar イベントを更新（@Async）
3a. クロスチームリンクの確認（start_at / end_at / location / title のいずれかが変更された場合のみ実行）:
   - 親スケジュールおよび全子スケジュールを source_schedule_id とする schedule_cross_refs（status = ACCEPTED）を取得
   - 存在する場合:
     ① target_schedule の所属チーム/組織 ADMIN に通知「招待スケジュールの内容が変更されました」
       （通知ペイロードに変更フィールド名・変更後の値を含める）
     ② target_schedule_id の schedule_attendances を持つメンバーにも同通知を送信
   - mirror スケジュール（target_schedule_id）は自動更新しない
3b. min_response_role が変更された場合（attendance_required = TRUE のみ）:
   Section 5「min_response_role 変更時の schedule_attendances 更新フロー」の [update_scope = ALL] を適用
4. audit_logs に SCHEDULE_UPDATED を記録（metadata: {"update_scope": "ALL"}）
```

---

### スケジュールキャンセルフロー

繰り返しスケジュールの場合、`update_scope` クエリパラメータ（`THIS_ONLY` / `THIS_AND_FOLLOWING` / `ALL`、デフォルト: `THIS_ONLY`）で範囲を指定する。

```
[THIS_ONLY または単発スケジュール]
1. POST /api/v1/teams/{id}/schedules/{scheduleId}/cancel を受付（?update_scope）
2. ADMIN か確認
3. 対象スケジュールの status が SCHEDULED か確認（CANCELLED / COMPLETED は 422）
4. schedules.status = CANCELLED に UPDATE
   schedule_attendances レコードは変更しない（ステータス保持・キャンセル後も出欠履歴を参照可能）
5. attendance_required = TRUE の場合:
   プッシュ通知「〇〇がキャンセルされました」を出欠登録済みメンバーへ送信（通知機能実装後）
6. Google カレンダー同期: 対象スケジュールを同期しているユーザーの Google Calendar イベントを削除（@Async）
7. クロスチームリンクの確認:
   a. このスケジュールに紐づく schedule_cross_refs（source_schedule_id）を取得
   b. status = ACCEPTED のものがある場合、target_schedule の所属チーム/組織 ADMIN に通知
      「〇〇チームが招待スケジュールをキャンセルしました」
8. audit_logs に SCHEDULE_CANCELLED を記録（metadata: {"update_scope": "THIS_ONLY"}）
9. 200 OK を返す（cancelled_count: 1）

[THIS_AND_FOLLOWING]
1〜3. THIS_ONLY と同様
4. 当該スケジュール自身および start_at 以降の全子スケジュール（is_exception の有無問わず）の
   status = CANCELLED に UPDATE
   schedule_attendances レコードは変更しない（出欠履歴を保持）
5. 影響スケジュールのうち attendance_required = TRUE のものに対して一括プッシュ通知（通知機能実装後）
6. Google カレンダー同期: 各対象スケジュールのイベントを削除（@Async）
7. クロスチームリンクの確認: キャンセル対象スケジュールに紐づく schedule_cross_refs で
   status = ACCEPTED のものがある場合、target_schedule の所属チーム/組織 ADMIN に通知
   「〇〇チームが招待スケジュールをキャンセルしました」
8. audit_logs に SCHEDULE_CANCELLED を記録
   metadata: {"update_scope": "THIS_AND_FOLLOWING", "cancelled_count": N}
9. 200 OK を返す（cancelled_count: N）

[ALL]
1〜3. THIS_ONLY と同様
4. 全子スケジュール（is_exception の有無問わず）および親スケジュールの status = CANCELLED に UPDATE
   schedule_attendances レコードは変更しない（出欠履歴を保持）
5. 影響スケジュールのうち attendance_required = TRUE のものに対して一括プッシュ通知（通知機能実装後）
6. Google カレンダー同期: 全対象スケジュールのイベントを削除（@Async）
7. クロスチームリンクの確認: キャンセル対象スケジュールに紐づく schedule_cross_refs で
   status = ACCEPTED のものがある場合、target_schedule の所属チーム/組織 ADMIN に通知
   「〇〇チームが招待スケジュールをキャンセルしました」
8. audit_logs に SCHEDULE_CANCELLED を記録
   metadata: {"update_scope": "ALL", "cancelled_count": N}
9. 200 OK を返す（cancelled_count: N）
```

---

### スケジュール削除フロー

スケジュールを論理削除する。繰り返しスケジュールの場合は `update_scope` で削除範囲を指定する。

```
[単発 / THIS_ONLY（繰り返しの1回のみ削除）]
1. DELETE /api/v1/teams/{id}/schedules/{scheduleId} を受付
2. 権限確認:
   - 操作者が ADMIN の場合: 無条件で削除可能
   - created_by = 操作者の場合: 削除可能（MEMBER / DEPUTY_ADMIN いずれも自作スケジュールは削除可）
   - 他者作成スケジュールを削除するには DELETE_OTHERS_CONTENT 権限が必要（なければ 403）
     ※ DEPUTY_ADMIN が他者作成スケジュールを削除するには MANAGE_SCHEDULES + DELETE_OTHERS_CONTENT の両方が必要
3. 対象スケジュールの deleted_at = NOW() に UPDATE（論理削除）
4. 対象スケジュールを同期しているユーザーの Google Calendar イベントを削除（@Async）
5. クロスチームリンクの確認:
   紐づく schedule_cross_refs（source_schedule_id / target_schedule_id）の ACCEPTED なものがある場合
   相手チーム / 組織の ADMIN に「スケジュールが削除されました」と通知
6. audit_logs に SCHEDULE_DELETED を記録
7. 204 No Content を返す

[THIS_AND_FOLLOWING（この回以降を削除）]
0. 権限確認: 単発/THIS_ONLY フロー step 2 と同様
1. 当該スケジュール自身を含む、start_at 以降の全子スケジュール（is_exception の有無問わず）を論理削除
   （is_exception = TRUE の例外スケジュールも含めて削除し、孤立データが残らないようにする）
2. 親スケジュールの recurrence_rule を以下の通り更新し、シリーズを当日前日で打ち切る（ルール変換）:
   - end_type = DATE の場合: end_date = 当該スケジュールの start_at の前日
   - end_type = COUNT の場合: end_type を COUNT → DATE に変換し、end_date = 当該スケジュールの start_at の前日
   - end_type = NEVER の場合: end_type を NEVER → DATE に変換し、end_date = 当該スケジュールの start_at の前日
3. クロスチームリンクの確認:
   削除対象スケジュールに紐づく schedule_cross_refs（source_schedule_id / target_schedule_id）で
   status = ACCEPTED のものがある場合、相手チーム / 組織の ADMIN に「スケジュールが削除されました」と通知
4. 削除された子スケジュールの Google Calendar イベントを全件削除（@Async）
5. audit_logs に SCHEDULE_DELETED を記録
   metadata: {"update_scope": "THIS_AND_FOLLOWING", "deleted_count": N}
6. 204 No Content を返す

[ALL（繰り返し全回 + 親を削除）]
0. 権限確認: 単発/THIS_ONLY フロー step 2 と同様
1. 全子スケジュール（is_exception の有無問わず）および親スケジュールを論理削除
   （is_exception = TRUE の例外スケジュールも含めて削除し、孤立データが残らないようにする）
2. クロスチームリンクの確認:
   削除対象スケジュールに紐づく schedule_cross_refs（source_schedule_id / target_schedule_id）で
   status = ACCEPTED のものがある場合、相手チーム / 組織の ADMIN に「スケジュールが削除されました」と通知
3. 全 Google Calendar イベントを削除（@Async）
4. audit_logs に SCHEDULE_DELETED を記録
   metadata: {"update_scope": "ALL", "deleted_count": N}
5. 204 No Content を返す
```

> - 単発スケジュールはリクエストボディ不要（`update_scope` 不要）
> - 繰り返しの子スケジュールに対して `update_scope` が省略された場合は `THIS_ONLY` として扱う
> - `status = CANCELLED` のスケジュールも削除可能（キャンセルと論理削除は独立した操作）

---

### 出欠リマインダーバッチフロー

毎時実行の定期バッチ（例: 毎時 0 分）。未回答メンバーのみに通知を送る。

```
1. schedule_attendance_reminders から is_sent = FALSE かつ remind_at <= NOW() のレコードを取得
2. 各リマインダーについて:
   a. 紐づくスケジュールが status = SCHEDULED かつ deleted_at IS NULL か確認（CANCELLED / COMPLETED はスキップ）
   b. attendance_deadline が設定されている場合: deadline < NOW() であれば期限切れとしてスキップ
      attendance_deadline = NULL（無期限）の場合はスキップしない（必ず通知する）
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
   ※ target チームが PRIVATE であっても team ID を直接指定すれば招待送信可能。
     エラーコードは存在の有無によらず常に 404 を返し、PRIVATE 判定を外部に漏らさない
4. スケジュールが SCHEDULED 状態か確認 → CANCELLED / COMPLETED は 422
5. 同一 source + target の既存招待レコードを確認:
   - PENDING または ACCEPTED または AWAITING_CONFIRMATION の場合: 409
   - REJECTED または CANCELLED の場合: status = PENDING、message を更新（再招待 UPDATE）→ 手順 7 へスキップ
   - 存在しない場合: 次手順へ
6. schedule_cross_refs に INSERT（status = PENDING）
7. 招待先チーム / 組織の ADMIN にプッシュ通知「〇〇から試合招待が届きました」
8. 201 Created を返す

[招待承認（target チームが PUBLIC / ORGANIZATION_ONLY の場合）]
1. POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/accept を受付
2. 操作者が招待先チームの ADMIN か確認
3. invitation が PENDING 状態か確認 → ACCEPTED / REJECTED / AWAITING_CONFIRMATION は 422
4. 招待元スケジュールの内容で自チームのカレンダーにミラースケジュールを作成:
   a. schedules に INSERT（以下のフィールドを招待元からコピー）:
      - team_id = 自チームの ID（承認した team_id）
      - title, description, location, start_at, end_at, all_day, event_type をコピー
      - visibility = 'MEMBERS_ONLY'（招待先チームのデフォルト；招待元の visibility は引き継がない）
      - min_view_role = 招待先チームの default_schedule_min_view_role（招待元の値は引き継がない）
      - min_response_role = 招待先チームの default_schedule_min_response_role（招待元の値は引き継がない）
      - attendance_required, attendance_deadline, comment_option をコピー
      - status = 'SCHEDULED'
      - is_exception = FALSE, parent_schedule_id = NULL（単発スケジュールとして作成）
      - recurrence_rule = NULL（繰り返し設定はコピーしない）
      - created_by = 操作者（承認した ADMIN のユーザー ID）
   b. attendance_required = TRUE の場合: 自チームのメンバーのうち min_response_role 以上のロールを持つ全員に schedule_attendances を INSERT（単発フロー step 8 と同様の絞り込み）
5. schedule_cross_refs.target_schedule_id = 新スケジュール ID、status = ACCEPTED、
   responded_at = NOW() に UPDATE
6. 招待元チームの ADMIN に「招待が承認されました」と通知
7. audit_logs に SCHEDULE_CROSS_INVITE_ACCEPTED を記録
8. 201 Created を返す

[招待承認（target チームが PRIVATE の場合）]
1. POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/accept を受付
2. 操作者が招待先チームの ADMIN か確認
3. invitation が PENDING 状態か確認 → ACCEPTED / REJECTED / AWAITING_CONFIRMATION は 422
4. ミラースケジュールはまだ作成しない
5. schedule_cross_refs.status = AWAITING_CONFIRMATION、responded_at = NOW() に UPDATE
6. 招待元チームの ADMIN に「招待が承認されました。確認・最終合意をお願いします」と通知
7. 200 OK を返す（schedule_cross_refs の状態変更のみ）

[招待確認（source チームが最終合意する・PRIVATE チームへの招待時のみ）]
1. POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/confirm を受付
2. 操作者が招待元チームの ADMIN か確認（{id} が source_schedule.team_id と一致するか）
3. invitation が AWAITING_CONFIRMATION 状態か確認 → それ以外は 422
4. 招待元スケジュールの内容で招待先チームのカレンダーにミラースケジュールを作成
   （フィールドコピールールは公開チームの承認フロー step 4a と同一）
5. schedule_cross_refs.target_schedule_id = 新スケジュール ID、status = ACCEPTED に UPDATE
6. 招待先チームの ADMIN に「最終確認が完了しました。カレンダーに追加されました」と通知
7. audit_logs に SCHEDULE_CROSS_INVITE_ACCEPTED を記録
8. 201 Created を返す

[招待拒否]
1. POST /api/v1/teams/{id}/schedule-invitations/{invitationId}/reject を受付
2. 操作者が当該チームの ADMIN か確認
3. invitation が PENDING 状態か確認（AWAITING_CONFIRMATION での拒否は不可：既に承認済みのため confirm か cancel のみ許容）
4. schedule_cross_refs.status = REJECTED、responded_at = NOW() に UPDATE
5. 招待元チームの ADMIN に「招待が拒否されました」と通知
6. 204 No Content を返す
```

---

> Google カレンダー個人同期フローは `F08_external_integration.md` Section 5 を参照。

---

### スケジュール自動完了バッチ（status = COMPLETED 自動遷移）

`status = SCHEDULED` のスケジュールが `end_at` を経過した場合に、自動で `COMPLETED` へ遷移させるバッチ。**毎時 0 分**に定期実行する。

```
バッチフロー（毎時 0 分実行）:
1. status = 'SCHEDULED' かつ end_at < NOW() かつ deleted_at IS NULL かつ
   recurrence_rule IS NULL のスケジュールを取得（繰り返しの親スケジュールを除外）
   （INDEX: idx_sch_status_end_at (status, end_at) を使用）
2. 対象スケジュールの status = 'COMPLETED' に UPDATE
3. audit_logs に SCHEDULE_COMPLETED を記録（metadata: {"trigger": "batch", "count": N}）
4. 処理件数を application log に出力（0 件の場合もログ出力）
```

> - 繰り返しスケジュールでは **個別の子スケジュール** が `end_at` を経過した時点でその子が COMPLETED に遷移する（親スケジュールは繰り返しシリーズ管理用のため、バッチ対象外）
> - `end_at IS NULL`（終了時刻なし）のスケジュールはバッチ対象外（COMPLETED への遷移は手動のみ）
> - ADMIN による手動完了（`PATCH` に `status: "COMPLETED"` を指定）は、バッチとは独立して任意のタイミングで実行可能

**Flyway 追加インデックス**
```sql
V3.021__add_idx_sch_status_end_at.sql
  -- schedules テーブルへの変更:
  --   INDEX idx_sch_status_end_at (status, end_at) 追加（自動完了バッチ用）
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

`end_type = NEVER` または遠い将来の `end_date` を持つ繰り返しスケジュールは、初回作成時には最大12ヶ月分のみ展開する。バッチが**毎日 JST 03:30** に定期実行され、全アクティブな親スケジュールを対象に「今日〜12ヶ月先」の範囲で不足している子スケジュールを補完する（Idempotent: すでに存在する子はスキップ）。

> **実行時刻の根拠**: ユーザーのアクティビティが最小となる深夜帯であり、仮に失敗しても翌朝の業務開始前に再実行のチャンスがある。

```
バッチフロー（毎日 JST 03:30 実行）:
1. parent_schedule_id = NULL かつ recurrence_rule IS NOT NULL かつ deleted_at IS NULL かつ status = 'SCHEDULED' の全スケジュールを取得
2. 各親スケジュールについて: recurrence_rule から「今日〜今日+12ヶ月」の範囲に発生する日付一覧を算出
   （end_date / count 上限に達する場合はその時点まで）
3. 算出した各発生日について、すでに子スケジュール（deleted_at IS NULL）が存在するか UNIQUE KEY (parent_schedule_id, start_at) で確認:
   - 存在する（論理削除されていない） → スキップ（Idempotent）
   - 存在しない → schedules に INSERT（parent_schedule_id = 親 ID、recurrence_rule = NULL）
4. 新規 INSERT した子スケジュールについて:
   4a. attendance_required = TRUE の場合: 単発フロー step 8 を適用（min_response_role 以上のロールを持つメンバーに schedule_attendances を一括 INSERT）
   4b. Google カレンダー同期（同期設定ユーザーに対して @Async で実施）
5. 展開が発生した場合のみ audit_logs に SCHEDULE_RECURRENCE_EXPANDED を記録
   metadata: {"expanded_count": N}
```

---

### アーカイブ状態における書き込み制限

アーカイブ済みチーム / 組織（`archived_at IS NOT NULL`）に対する書き込み操作は、Service 層の入り口で `archived_at` を確認し 422（`"TEAM_ARCHIVED"` / `"ORGANIZATION_ARCHIVED"`）を返してブロックする（F03 設計に準拠）。

**F05 スコープでのブロック対象操作:**

| 操作 | 対象エンドポイント |
|------|-----------------|
| スケジュール作成 | `POST /teams/{id}/schedules`・`POST /organizations/{id}/schedules` |
| スケジュール更新 | `PATCH /teams/{id}/schedules/{scheduleId}`・`PATCH /organizations/{id}/schedules/{scheduleId}` |
| スケジュールキャンセル | `POST /teams/{id}/schedules/{scheduleId}/cancel`・`POST /organizations/{id}/schedules/{scheduleId}/cancel` |
| 出欠回答 | `PATCH /schedules/{scheduleId}/responses`（対象スケジュールのチーム / 組織がアーカイブ済みの場合）|
| 即時リマインド送信 | `POST /schedules/{id}/remind`（同上）|
| クロスチーム招待送信 | `POST /teams/{id}/schedules/{scheduleId}/cross-invite` |
| 招待承認 | `POST /teams/{id}/schedule-invitations/{invitationId}/accept`（承認するチームがアーカイブ済みの場合）|
| 招待確認 | `POST /teams/{id}/schedule-invitations/{invitationId}/confirm`（招待先チームがアーカイブ済みの場合）|

**アーカイブ中も許可する操作:**

| 操作 | 理由 |
|------|------|
| 全 GET 操作 | 読み取り専用のため影響なし |
| スケジュール論理削除 | ADMIN によるクリーンアップ目的（`DELETE /teams/{id}/schedules/{id}` 等）|
| 招待拒否 | 受信済み招待の拒否は常に許可（`POST /reject`）|
| 招待キャンセル | 送信済み招待のキャンセルは常に許可（`DELETE /cross-invite/{id}`）|

---

## 6. セキュリティ考慮事項

- **権限チェック**: スケジュール作成・編集には `MANAGE_SCHEDULES` 権限を F03 の権限解決ロジック経由で確認する
- **他者スケジュールの削除**: MEMBER が自分以外の作成スケジュールを削除するには `DELETE_OTHERS_CONTENT` 権限が必要。ADMIN は無条件で削除可能
- **出欠情報のプライバシー**: `attendance_summary`（件数集計）は当該スケジュールの `min_response_role` 以上のロールのみ返す（min_response_role 未満は null）。個別メンバーの出欠一覧（`GET /teams/{id}/schedules/{scheduleId}/attendances`、個人名・回答内容付き）は ADMIN のみ参照可能。ダッシュボード統計（`GET /teams/{id}/attendance-stats` 等、個人別出席率付き）も ADMIN のみ。組織レベルの出欠集計はチーム単位の件数のみ開示し、個人の出欠情報は返さない
- **可視性制御（スコープ）**: `visibility = 'MEMBERS_ONLY'` のスケジュールは当該チーム/組織の直接所属者にのみ返す（未ログイン含む非所属ユーザーには返さない）。`visibility = 'ORGANIZATION'` のスケジュールは所有チームの親組織メンバーにも返す（評価ロール: 親組織への直接所属ロール）。SUPPORTER が `visibility = 'ORGANIZATION'` スケジュールを閲覧できるのは `min_view_role = 'SUPPORTER+'` を明示した場合のみ（`min_view_role = 'MEMBER+'` デフォルトでは閲覧不可）。「組織全体公開かつ SUPPORTER 閲覧可」は `visibility = 'ORGANIZATION'` + `min_view_role = 'SUPPORTER+'` の組み合わせで実現する
- **未ログインアクセスのポリシー（ANYONE 設定）**: `min_view_role = 'ANYONE'` のスケジュールはスケジュール一覧・詳細エンドポイントで未認証アクセスを許可する（Optional Authentication）。Spring Security で `permitAll()` を設定し、未認証リクエストはアプリ層で GUEST 相当として処理する。返すフィールドは GUEST ロールと同等に制限する（`my_response`・`attendance_summary`・`surveys`・`reminders`・`cross_invitations` は常に null）。エンドポイント別の動作: 一覧（`GET /teams/{id}/schedules`）では `min_view_role = 'ANYONE'` のスケジュールのみ返す。詳細（`GET /teams/{id}/schedules/{id}`）では当該スケジュールの `min_view_role` を確認し、`ANYONE` 以外なら 401 を返す。`public_token` 等の専用 URL は設けない（通常エンドポイントのフィルタリングで保護する）
- **可視性制御（ロール）**: `min_view_role` によるフィルタリングを全閲覧系エンドポイントで実施する。SUPPORTER は `min_view_role IN ('ANYONE', 'SUPPORTER+')` のスケジュールのみ閲覧可。GUEST は `min_view_role = 'ANYONE'` のみ閲覧可。`min_view_role` は作成・編集権限を持つ MEMBER 以上のみが変更可能（SUPPORTER/GUEST 自身は変更不可）
- **回答権限制御（min_response_role）**: `min_response_role` によるチェックを `PATCH /schedules/{scheduleId}/responses` で実施する。`min_view_role`（閲覧可否）とは独立した2軸制御。回答権限がないユーザーはスケジュールを閲覧可能だが 403 を返す。`attendance_required = TRUE` 時の `schedule_attendances` 一括 INSERT も `min_response_role` 以上のロールを持つメンバーのみを対象とする
- **繰り返し一括操作の権限確認**: `THIS_AND_FOLLOWING` / `ALL` スコープの更新・削除時も必ず権限確認（作成者 or MANAGE_SCHEDULES or ADMIN）を実施する。スコープが広いほど影響が大きいため省略不可
- **is_exception 孤立データ防止**: `THIS_AND_FOLLOWING` / `ALL` の一括削除・更新時は `is_exception = TRUE` の例外スケジュールも対象に含め、孤立データ（削除済み親の子が残存する状態）を防ぐ
- **親子スコープの非継承**: `min_view_role` の評価はスケジュールの所有エンティティへの直接所属ロールのみを参照する。親グループ/組織のロールは子グループのスケジュールに継承しない。`visibility = 'ORGANIZATION'` の場合のみ親組織への直接所属ロールで評価する（階層は1段のみ適用し、さらに上の祖先グループへは伝播しない）
- **繰り返し展開の上限**: 一括 INSERT 200件の制限でリソース枯渇を防ぐ。展開は非同期（Spring `@Async`）で実行し、作成 API のレスポンスをブロックしない
- **大規模チーム向け出欠レコード生成**: `schedule_attendances` の一括 INSERT を `@Async` で非同期化し、500件単位のチャンクに分割してバルク INSERT する。処理中は `schedules.attendance_status = 'GENERATING'` で状態を管理し、フロントエンドが生成中表示を制御できるようにする
- **スコープ越境防止**: team_id / organization_id がリクエスト者の所属スコープと一致するかを全エンドポイントで確認する
- **Google Calendar OAuth トークン管理**: `access_token` / `refresh_token` は AES-256-GCM で暗号化して保存。平文での DB 保存・ログ出力は禁止。Google OAuth スコープは `https://www.googleapis.com/auth/calendar.events` のみ要求（最小権限）
- **クロスチーム招待のスコープ確認**: 招待元・招待先いずれも操作者が当該チーム / 組織の ADMIN であることを確認する。招待承認時も招待先チームへの所属確認を必ず実施する。`POST /confirm` は招待元チームの ADMIN のみ呼び出し可能（招待先が呼び出しても 403）
- **クロスチーム招待の情報マスキング**: 招待受信側が承認前に取得できるのは、招待元チーム名・チームアイコン・招待者名・当該スケジュール情報（タイトル・日時・場所）・招待メッセージのみ。招待元チームのメンバー一覧・他スケジュール・統計はレスポンスに含めない。PRIVATE チームへの招待では AWAITING_CONFIRMATION 経由の双方向合意フローを経るまでミラースケジュールを生成しない
- **PRIVATE チームの存在隠蔽**: 招待送信エンドポイントでは、チームが存在しない場合と PRIVATE な場合を区別せず一律 404 を返す（招待可否によるサイドチャネル漏洩を防ぐ）

---

## 7. Flywayマイグレーション

```
V3.007__create_schedules_table.sql
V3.008__create_schedule_attendances_table.sql
V3.009__create_event_surveys_table.sql
V3.010__create_event_survey_responses_table.sql
V3.011__create_schedule_attendance_reminders_table.sql
V3.012__create_schedule_cross_refs_table.sql
-- Google Calendar 連携テーブルのマイグレーション（V3.013〜V3.015・V3.017）は F08_external_integration.md Section 6 を参照
V3.016__add_user_id_to_schedules.sql                    -- schedules.user_id カラム追加・INDEX・CHECK 制約（F05_schedule_personal.md と共有）
V3.020__update_schedule_cross_refs_status_enum.sql       -- schedule_cross_refs.status ENUM に 'AWAITING_CONFIRMATION' を追加
V3.021__add_idx_sch_status_end_at.sql                    -- schedules.idx_sch_status_end_at (status, end_at) INDEX 追加（自動完了バッチ用）

-- Phase 4+（本テーブルが必要になったタイミングで実行）
V4.001__create_member_attendance_stats_table.sql
```

**マイグレーション上の注意点**
- V3.007 は V2.001（organizations）/ V2.002（teams）完了後に実行
- V3.008 は V3.007（schedules）および V1.005（users）完了後
- V3.009 は V3.007（schedules）完了後
- V3.010 は V3.009（event_surveys）および V1.005（users）完了後
- V3.011 は V3.007（schedules）完了後
- V3.012 は V3.007（schedules）完了後
- V3.016 は V3.007（schedules）および V1.005（users）完了後（F05_schedule_personal.md と共有）
- Google Calendar 連携マイグレーション（V3.013〜V3.015・V3.017）の依存関係は F08_external_integration.md Section 6 を参照

---

## 8. 未解決事項

- [x] 大規模チームでの出席率集計パフォーマンス: Phase 4+ で `member_attendance_stats` 月次スナップショットテーブルを導入（Section 3 参照）。Phase 3 はオンザフライ計算で運用
- [x] 繰り返しスケジュール自動展開バッチのスケジュールを確定: 毎日 JST 03:30 実行。ロジックを「今日〜12ヶ月先の不足分を毎日補完（Idempotent）」に変更。schedules テーブルに UNIQUE KEY (parent_schedule_id, start_at) 追加（Section 3・Section 5 参照）
- [ ] Google Calendar 管理者レベル共有連携（`google_calendar_event_id` カラムの用途）は F09 で別途設計し、個人同期との役割分担を確定する
- [ ] `event_type` の選択肢はテンプレート（SPORTS / SCHOOL 等）に応じて表示切替するかを確定する（テンプレート管理 feature doc で検討）
- [ ] 組織スコープのクロスチーム招待受信エンドポイントが未定義: `schedule_cross_refs.target_type` に `'ORGANIZATION'` が含まれるが、組織スコープの受信招待一覧（`GET /organizations/{id}/schedule-invitations`）・承認（`POST /organizations/{id}/schedule-invitations/{id}/accept`）・拒否（`POST /organizations/{id}/schedule-invitations/{id}/reject`）エンドポイントがない。チーム版と同様のエンドポイントを組織スコープに追加するか、`target_type` を `'TEAM'` のみに制限するかを確定する
- [x] 出欠集計の開示範囲を確定: 当該スケジュールの `min_response_role` 以上のロールへ件数のみ開示（`attendance_summary`・`GET /schedules/{id}/stats`）。個人名付き一覧（`GET /attendances`）・ダッシュボード統計（`GET /attendance-stats`）は引き続き ADMIN のみ
- [x] 大規模チーム（1000人規模）への一括 `schedule_attendances` INSERT 対策を確定: @Async 非同期化・500件チャンク分割バルク INSERT・`schedules.attendance_status ENUM('READY','GENERATING')` による生成状態管理（Section 3・Section 5・Section 6 参照）
- [x] `min_view_role = 'ANYONE'` 設定時の未ログインアクセスを確定: 未ログインユーザーを GUEST 相当として扱う Optional Authentication パターン。専用 URL・`public_token` なし。一覧は ANYONE スケジュールのみ返し、詳細は ANYONE 以外なら 401。返すフィールドは GUEST ロールと同等に制限（Section 6 参照）
- [x] クロスチーム招待時、招待先チームが非公開の場合でも招待を送れるかを確定する（招待送信時のプライバシー設計）: PRIVATE チームへの招待は team ID を直接指定すれば送信可能。ただし存在の有無と PRIVATE 判定を区別せず一律 404 を返す（サイドチャネル漏洩防止）。承認後は双方向合意フロー（`AWAITING_CONFIRMATION` 状態・`POST /confirm` エンドポイント）を経由してミラースケジュールを作成する（Section 4・Section 5「クロスチームスケジュール招待フロー」・Section 6「PRIVATE チームの存在隠蔽」参照）
- [x] 組織スケジュールの出欠確認: チームに所属しない組織直接メンバーへの出欠通知フローを確定する: 既存フローで対応済み。スケジュール作成フロー step 8i「全所属チームのメンバー + 組織直接所属メンバー（重複除外）」に含まれるため通知・schedule_attendances 生成は自動的に行われる。出欠回答フロー step 6 で組織直接所属ロールによる回答権限を確認。集計は `GET /organizations/{id}/schedules/{id}/attendances` の `team_id: null`「チーム未所属（組織直接メンバー）」グループで区別表示。ダッシュボードへの表示は `GET /my/calendar` の横断ビューで対応（専用セクション表示はフロントエンド判断）
- [x] クロスチーム招待承認後、招待元が内容（日時・場所）を変更した場合の招待先への通知仕様を確定する: 更新フロー THIS_ONLY / THIS_AND_FOLLOWING / ALL の各 `3a` / `6a` / `3a` に cross-ref 通知ステップを追加。通知トリガーは `start_at` / `end_at` / `location` / `title` の変更（`address` フィールドは存在しないため `location` のみ）。通知対象: target チーム ADMIN（常時）+ mirror schedule の schedule_attendances 保持メンバー。mirror schedule は自動更新しない（独立管理方針を維持）。通知ペイロードに変更フィールド名・変更後の値を含め、フロントエンドが「変更あり」バッジを表示できるようにする（Section 5 参照）
- [x] `PATCH /teams/{id}/schedules/{scheduleId}` でアンケート設問（surveys）を変更できるかを確定する: `THIS_ONLY`（単発含む）のみ対応。id ベースの差分更新（id あり=更新、id なし=追加、リスト外 id=削除）。安全な変更（question テキスト / is_required / sort_order）は常に許可。危険な変更（question_type / options 変更・削除）は `force_clear_responses = true` がない限り 422。`THIS_AND_FOLLOWING` / `ALL` で surveys を含むリクエストは 422（Phase 4+）。既存回答の削除は ON DELETE CASCADE で自動物理削除（Section 4・Section 5「アンケート設問更新フロー」参照）
- [x] スケジュール更新で `min_response_role` を変更した場合の `schedule_attendances` の扱いを確定する: 緩和時（対象拡大）→ 新対象メンバーに retroactively INSERT（`@Async` + 500件チャンクバルク INSERT・`GENERATING → READY` 状態管理。作成フロー step 8 と同一パターン）。制限時（対象縮小）→ 自動削除なし（既存回答データ保護）。UNDECIDED のみ条件付き削除は Phase 4+。ALL スコープ更新時は全子スケジュールに同フローを適用（Section 5「min_response_role 変更時の schedule_attendances 更新フロー」参照）
- [x] `schedules.status = COMPLETED` のセットタイミングを確定する: 毎時バッチ（`status = SCHEDULED` かつ `end_at < NOW()`）と ADMIN 手動 PATCH（`status: "COMPLETED"` フィールド指定）の両方を採用。COMPLETED 後の出欠修正は ADMIN のみ PATCH /responses で許可（一般ユーザーは 422）。INDEX `idx_sch_status_end_at (status, end_at)` を Flyway V3.021 で追加。Section 5「スケジュール自動完了バッチ」・出欠回答フロー step 4 参照
- [x] `visibility = 'ORGANIZATION'` のスケジュールを SUPPORTER が閲覧できるかを確定する: `min_view_role = 'SUPPORTER+'` を明示した場合のみ閲覧可。`min_view_role = 'MEMBER+'`（デフォルト）では閲覧不可。「運営専用」は `min_view_role = 'ADMIN_ONLY'` / `'MEMBER+'` で実現。org レベルで SUPPORTER へのデフォルト表示を有効化したい場合は `organizations.default_schedule_min_view_role = 'SUPPORTER+'` を設定。`visibility = 'ORGANIZATION'` はチームスコープのスケジュールにのみ有効（組織スコープでは MEMBERS_ONLY と同義）。Section 2 スコープ表・Section 3 備考・Section 6 可視性制御（スコープ）を一括更新

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-11 | 精査(18回目): ① エンドポイント一覧に `POST /teams/{id}/schedule-invitations/{invitationId}/confirm` を追加（仕様定義は存在したがテーブルに欠落していた）② `PATCH /responses` エラーテーブルに 404（スケジュール不存在 / 論理削除済み）を追加（フロー step 2 で 404 を返すと明記されていたがエラーテーブルに欠落）③ `GET /schedules/{id}/stats` にエラーレスポンステーブルを新設（403 / 404 / 422）④ `POST /remind` の `attendance_required = FALSE` エラーを 409 → 422 に修正（`PATCH /responses` の同条件 422 との整合）⑤ アーカイブ状態における書き込み制限セクションを追加（F03 の「F05 等も同様にアーカイブチェック必要」指示に対応。ブロック対象 8 操作・許可対象 4 操作を列挙）⑥ 各エラーテーブルにアーカイブ済み 422 を追加（`PATCH /responses`・`GET /stats`・`POST /remind`）⑦ 組織スコープのクロスチーム招待受信エンドポイント欠落を未解決事項に追加（`target_type = 'ORGANIZATION'` に対応するエンドポイントが未定義） |
| 2026-03-07 | 精査(17回目): ① `schedules` テーブルのインデックス一覧に `idx_sch_status_end_at (status, end_at)` を追加（V3.021 で定義済みだがテーブル定義セクションに欠落していた）。② `PATCH /responses` エラーテーブルの 409（deadline 超過）に「ADMIN が COMPLETED スケジュールの出欠修正を行う場合は deadline チェックをスキップ」の注記を追加（出欠回答フロー step 7 との整合）。③ 出欠リマインダーバッチ step 2a のスキップ条件に COMPLETED を追加（CANCELLED のみだった。COMPLETED スケジュールに attendance_deadline = NULL の場合リマインダーが誤送信される問題を防止）。④ `POST /reject` エラーテーブルの 422 条件に `AWAITING_CONFIRMATION` を追加（フロー step 3 では不可と明記されていたがエラーテーブルに欠落していた） |
| 2026-03-07 | 精査(16回目): ① テーブル一覧セクションの `member_attendance_stats` 行がブロック引用直後に連結し Markdown 構造が破損していた問題を修正（空行を挿入）。② 自動完了バッチのクエリ条件に `recurrence_rule IS NULL` を追加（繰り返しの親スケジュールがバッチで誤って COMPLETED に遷移するバグを防止）。③ 出欠回答フロー step 7 に「ADMIN + COMPLETED の場合は deadline チェックをスキップ」を追記（ADMIN が COMPLETED 後に出欠を手動修正する際、期限切れ 409 でブロックされる問題を修正）。④ PATCH /schedules エラーテーブルに `status: "COMPLETED"` の `THIS_AND_FOLLOWING / ALL` 一括設定を 422 とする行を追加（注記には記載があったがエラーテーブルに欠落していた）。⑤ 最終更新日を 2026-03-07 に更新 |
| 2026-03-06 | visibility = 'ORGANIZATION' + SUPPORTER の閲覧権限ルール確定: min_view_role = 'SUPPORTER+' を明示した場合のみ閲覧可（min_view_role = 'MEMBER+' デフォルトでは閲覧不可）。「運営専用」= min_view_role = 'ADMIN_ONLY' / 'MEMBER+' の明示設定で実現。org デフォルトは organizations.default_schedule_min_view_role = 'SUPPORTER+' で変更可。visibility と min_view_role の 2 軸設計を Section 2 スコープ表・Section 3 備考（SUPPORTER + ORGANIZATION 組み合わせルールを追加）・Section 6 可視性制御に明記 |
| 2026-03-06 | schedules.status = COMPLETED セットタイミング確定: 毎時バッチ（status = SCHEDULED かつ end_at 経過で自動遷移）と ADMIN 手動 PATCH（status: "COMPLETED" フィールド指定）の両方を採用。COMPLETED 後の出欠修正は ADMIN のみ PATCH /responses で許可。出欠回答フロー step 4 を ADMIN バイパス対応に更新。PATCH /schedules に COMPLETED 手動設定の注記・エラーテーブルに 403（ADMIN 以外）と 422（終端状態からの再更新）を追加。PATCH /responses のエラーテーブルを ADMIN/非 ADMIN で分離。Section 5 に「スケジュール自動完了バッチ」フロー新設。Flyway V3.021（INDEX idx_sch_status_end_at 追加）追加 |
| 2026-03-03 | min_response_role 変更時の schedule_attendances 更新仕様確定: 緩和時は @Async + 500件チャンクバルク INSERT で retroactively 追加（GENERATING → READY 状態管理）。制限時は自動削除なし（既存回答データ保護・UNDECIDED 条件付き削除は Phase 4+）。繰り返し ALL スコープ更新時は全子スケジュールに適用。Section 5 に新フロー追加。THIS_ONLY / ALL 更新フローに 3b ステップ参照を追記 |
| 2026-03-03 | アンケート設問更新仕様確定: PATCH での surveys 変更を THIS_ONLY（単発含む）のみ対応に限定。id ベース差分更新。安全変更（question テキスト / is_required / sort_order）は常に許可。危険変更（question_type / options 変更・削除）は force_clear_responses = true が必要（false の場合 422 + 影響設問情報を返す）。事前に event_survey_responses を DELETE してから question_type/options を UPDATE。THIS_AND_FOLLOWING / ALL への surveys 変更は 422（Phase 4+）。Section 5「アンケート設問更新フロー」を新設 |
| 2026-03-03 | クロスチーム招待変更通知確定: 更新フロー THIS_ONLY / THIS_AND_FOLLOWING / ALL に cross-ref 通知ステップを追加（step 3a / 6a / 3a）。通知トリガーは start_at / end_at / location / title の変更時。通知対象: target チーム ADMIN + mirror schedule の schedule_attendances 保持メンバー。mirror schedule は自動更新しない（独立管理維持） |
| 2026-03-03 | 組織直接メンバーへの出欠フロー確定: 既存フロー（スケジュール作成 step 8i・出欠回答 step 6）が既に対応済みであることを明文化。集計レスポンスの `team_id: null` グループ名を「チーム未所属（組織直接メンバー）」に統一。通知 step iv に組織直接メンバーへの適用注記を追記 |
| 2026-03-03 | クロスチーム招待プライバシー設計確定: PRIVATE チームへの双方向承認フロー追加（`AWAITING_CONFIRMATION` 状態・`POST /confirm` エンドポイント）。招待前情報マスキングポリシーを明文化。PRIVATE チーム存在隠蔽ポリシーを追加。Flyway V3.020 追加 |
| 2026-03-01 | Google Calendar 連携を F08_external_integration.md へ分離: テーブル定義（user_google_calendar_connections / user_calendar_sync_settings / user_schedule_google_events）・API 仕様 6本・ビジネスロジック（Google カレンダー個人同期フロー）・Flyway V3.013〜V3.015・V3.017 を移管。本ドキュメントには F08 への参照のみ残す |
| 2026-03-01 | 個人スケジュール統合: `GET /api/v1/my/calendar` に個人スケジュールを追加（`scope_type = "PERSONAL"`・`scope_id = null`・`scope_name = "個人"`）。`user_google_calendar_connections` に `personal_sync_enabled BOOLEAN DEFAULT FALSE` を追加（個人スケジュールの Google 同期設定；`user_calendar_sync_settings` への scope_type='PERSONAL' 追加は不採用）。Flyway V3.017 追加 |
| 2026-03-01 | ドキュメント分割: F05_schedule_attendance.md を F05_schedule_shared.md にリネーム（組織・チームスコープ専用）。個人スケジュール機能を F05_schedule_personal.md として分離。schedules テーブルに user_id カラム追加（三者XOR CHECK制約・INDEX idx_sch_user_start）。Flyway V3.016 追加 |
| 2026-03-01 | 未ログインアクセスポリシー確定: `min_view_role = 'ANYONE'` 設定時の未ログインユーザーを GUEST 相当として扱う Optional Authentication パターンに決定。`public_token` カラム・専用 URL は不採用（通常エンドポイントのフィルタリングで保護）。Section 2 スコープ表に「GUEST / 未ログイン」を明記。Section 6 セキュリティ考慮事項に未ログインアクセスポリシー・返却フィールドの制限・401/403 使い分けを追記。未解決事項を解決済みに更新 |
| 2026-03-01 | 大規模チーム向け出欠データ生成最適化: `schedules` テーブルに `attendance_status ENUM('READY','GENERATING')` カラムを追加。単発フロー step 8（schedule_attendances 一括 INSERT）を @Async 化し 500件チャンク分割バルク INSERT に変更。スケジュール詳細レスポンスに `attendance_status` フィールドを追加（GENERATING 時フロントエンドが 3秒ポーリングで完了を検知）。セキュリティ考慮事項に大規模 INSERT 最適化を追記。未解決事項を解決済みに更新 |
| 2026-03-01 | 出欠集計サマリーの開示範囲変更: `attendance_summary`・`GET /schedules/{id}/stats` の参照権限を「ADMIN のみ」から「当該スケジュールの min_response_role 以上のロール」に変更。個人名付き一覧（attendances）・ダッシュボード統計（attendance-stats）は引き続き ADMIN のみ。Section 2 スコープ表・API 一覧・スケジュール一覧/詳細レスポンス注記・stats エンドポイント説明・セキュリティ考慮事項・未解決事項を一括更新 |
| 2026-03-01 | バッチ設計確定: 繰り返しスケジュール自動展開バッチを毎日 JST 03:30 実行に確定。ロジックを「残り30日以内トリガー＋12ヶ月追加」から「毎日・今日〜12ヶ月先の不足分を補完（Idempotent）」に変更。schedules テーブルに UNIQUE KEY uq_sch_parent_start (parent_schedule_id, start_at) を追加し DB レベルで重複展開を防止。未解決事項を解決済みに更新 |
| 2026-03-01 | キャッシュ設計追加: `member_attendance_stats`（月次スナップショット）を Section 3 に追加。提案設計から以下を修正 → ① `organization_id` 単独キーを `(user_id, scope_type, scope_id, year_month)` 複合主キーに変更（チーム・組織両スコープ対応）、② `count_partial` 追加（PARTIAL ステータス対応）、③ 任意期間クエリ対応のため累積値から月次スナップショット形式に変更、④ `treat_undecided_as_absent_after_deadline` は生値保存・API 側動的適用に修正（フラグ変更時の大量再計算を回避）。Flyway V4.001 追加。未解決事項①を解決済みに更新 |
| 2026-03-01 | 未解決事項解決②: `treat_undecided_as_absent_after_deadline` フラグ（teams / organizations テーブル、BOOLEAN DEFAULT FALSE）を追加。期限経過後の UNDECIDED を ABSENT として扱う計算モードを定義（PARTIAL を含む既存定義と整合）。`GET /api/v1/schedules/{id}/stats`（出欠サマリー・フラグ適用済み）と `POST /api/v1/schedules/{id}/remind`（UNDECIDED メンバーへの即時手動通知）を追加。自動リマインダー（schedule_attendance_reminders）との役割分担を明記 |
| 2026-03-01 | 未解決事項解決①: 出席率の集計期間を任意指定可能（最長12ヶ月、デフォルト3ヶ月）に確定。組織ダッシュボード用 `GET /organizations/{id}/attendance-stats`（チーム別集計・個人情報なし）を追加。全3エンドポイントに最長12ヶ月制約（超過時 400）を追記。ビジネスロジックに集計期間の制約セクションを追加 |
| 2026-02-28 | 精査(15回目): ① クロスチーム招待送信のエラーテーブル（422条件）と招待送信フロー step 4 に COMPLETED を追加（CANCELLED のみ記載されており、CANCELLED / COMPLETED 両方を 422 とする他エンドポイントと不整合だった）。② 自動展開バッチ step 2 の「最後の子スケジュール」取得条件に `deleted_at IS NULL` を追記（論理削除済みの子スケジュールが最後の子として取得される可能性を排除） |
| 2026-02-28 | 精査(14回目): ① 自動展開バッチ step 1 のフィルタ条件に `deleted_at IS NULL` と `status = 'SCHEDULED'` を追加（削除済み・キャンセル済みの親スケジュールが再展開されるバグを防止）。② 自動展開バッチ step 3 後に step 3a を追加（attendance_required = TRUE の場合、新規展開した子スケジュールに schedule_attendances を一括 INSERT）。③ PATCH /responses エラーテーブルの 422 条件に COMPLETED を追記（フロー step 4 では CANCELLED / COMPLETED 両方が 422 と明記されていたが、エラーテーブルに COMPLETED が欠落していた） |
| 2026-02-28 | 精査(13回目): ① 繰り返し更新フロー THIS_AND_FOLLOWING の step 4 後に step 4a を追加（attendance_required = TRUE の場合、再展開した子スケジュールに対して単発フロー step 8 を適用し schedule_attendances を一括 INSERT）。② 削除フロー THIS_AND_FOLLOWING / ALL にクロスチームリンク確認ステップを追加（単発/THIS_ONLY の step 5 と統一。キャンセルフローでは3パターンとも存在していたが削除フローでは欠落していた） |
| 2026-02-28 | 精査(12回目): ① 最終更新日を 2026-02-28 に修正。② クロスチーム招待承認フロー step 4a に min_view_role / min_response_role のコピーロジックを追加（招待先チームの default 値を適用）。③ 招待承認フロー step 4b の schedule_attendances INSERT 対象を「全メンバー」から「min_response_role 以上のロールを持つ全員」に修正（単発作成フロー step 8 と統一） |
| 2026-02-28 | 精査(11回目): ① GET スケジュール一覧の min_view_role フィルタリング注記で旧 enum 値（SUPPORTER/GUEST）を使用していた誤りを修正（正: ANYONE/SUPPORTER+）、MEMBER の可視範囲を正確に記載。② 単発作成フロー step 4a のデフォルト値 'MEMBER' → 'MEMBER+' に修正。③ 削除フロー THIS_AND_FOLLOWING に end_type=NEVER→DATE 変換を追記（更新フローと統一）。④ 未解決事項の visibility='PUBLIC' を min_view_role='ANYONE' ベースの表現に更新。⑤ 閲覧権限（min_view_role）と回答権限（min_response_role）を独立した2軸設計に変更。schedules テーブルに min_response_role カラム追加（ENUM: SUPPORTER+/MEMBER+/ADMIN_ONLY、DEFAULT: MEMBER+）。チーム/組織の default_schedule_min_response_role 設定対応。スコープ表・単発/繰り返し作成フロー（step 4b・schedule_attendances 対象絞り込み）・出欠回答フロー step 6・PATCH /responses spec・my/calendar レスポンス・セキュリティ考慮事項を一括更新 |
| 2026-02-28 | 精査(10回目): 繰り返し作成フローのステップ番号を修正（5a → 6、以降を +1 繰り上げ）。survey_responses 注釈の矛盾解消（「全設問分を返す（未回答は含まない）」→「回答済みの設問のみ返す（未回答の設問は含まない）」） |
| 2026-02-28 | 精査(9回目): DEPUTY_ADMIN の削除権限を明確化。自己作成は MANAGE_SCHEDULES のみで削除可、他者作成は MANAGE_SCHEDULES + DELETE_OTHERS_CONTENT の両方が必要。スコープ表・削除フロー step 2 を統一 |
| 2026-02-28 | 仕様変更(8回目): `min_view_role` enum を ANYONE / SUPPORTER+ / MEMBER+ / ADMIN_ONLY に再設計（フィールド名・テーブル名は変更なし）。`reason` → `comment` リネーム。出欠回答を `PATCH /api/v1/schedules/{id}/responses` 統一エンドポイント化。`GET /me/schedules` → `GET /my/calendar`（`my_response` フィールドで回答ステータス統合）。THIS_AND_FOLLOWING / ALL の更新・削除フローに権限確認 step 0・is_exception 孤立データ防止・end_type=COUNT 変換方法を追加 |
| 2026-02-28 | 精査(7回目): `min_view_role` の評価スコープ（親子関係）を明記。直接所属ロールのみで評価し親グループのロールは継承しない原則を `schedules` 備考・セキュリティ考慮事項に追加。`visibility = 'ORGANIZATION'` 時は親組織への直接所属ロールで評価（階層1段のみ）を明記 |
| 2026-02-28 | 精査(6回目): `min_view_role`（MEMBER / SUPPORTER / GUEST）を `schedules` テーブルに追加。チーム/組織設定の `default_schedule_min_view_role` から初期値を継承。スコープ表・API認証要件・リクエスト/レスポンス例・作成フロー・セキュリティ考慮事項を一括更新。`visibility` の説明を「スコープ制御」に整理し `min_view_role` と役割を分離 |
| 2026-02-28 | 精査(5回目): `DELETE /api/v1/organizations/{id}/schedules/{scheduleId}` spec セクション追加（チーム版と同様・organization_id がスコープ対象） |
| 2026-02-23 | 精査(4回目): 組織版エンドポイント4本の spec 追加（GET/POST list・GET detail・PATCH）。PATCH 422 に COMPLETED を追記。schedule_cross_refs UNIQUE KEY と再招待フローを整合（UPDATE方式を明記）。繰り返し作成フローの generated_count を @Async 前に事前算出するよう修正。キャンセルフロー THIS_AND_FOLLOWING / ALL にクロスチームリンク確認を追加。Google 同期フロー [同期有効化] に組織版を明記。リマインダーバッチの deadline = NULL 挙動を明記。parent_schedule_id FK に ON DELETE RESTRICT 追記 |
| 2026-02-23 | 精査(3回目): POST /cancel spec 追加（teams・orgs 版・update_scope 対応）。DELETE /cross-invite/{invitationId} spec 追加。POST /reject spec 追加。繰り返しキャンセルに update_scope 適用。単発作成フローに組織スコープ適用を明記。招待承認フローのミラースケジュールコピーフィールドを明定義。backfill_count を @Async 前に取得するよう修正。schedule_cross_refs.source_schedule_id FK に ON DELETE CASCADE 追記。total_required 集計条件に deleted_at IS NULL 追記。キャンセルフローに出欠レコード保持を明記。THIS_AND_FOLLOWING 削除フローに当該スケジュール自身の削除を明記 |
| 2026-02-23 | 精査(2回目): DELETE 認証要件修正・`?update_scope` クエリパラメータ定義。PATCH レスポンス仕様追加。DELETE spec セクション追加。組織版 attendance / calendar-sync レスポンス仕様追加。単発作成フローに surveys バリデーション追加。繰り返し更新 THIS_AND_FOLLOWING の is_exception 削除範囲を統一。組織スコープ出欠回答フロー追加。event_surveys PATCH ハンドリングを未解決事項に追加 |
| 2026-02-23 | 精査(1回目): `POST /organizations/{id}/schedules/{scheduleId}/attendance` を追加。スケジュール詳細・`GET /me/schedules`・disconnect・calendar-sync-settings のレスポンス仕様を追記。繰り返し作成フローに comment_option ステップ追加・参照番号修正。スケジュール削除フロー（単発 / THIS_AND_FOLLOWING / ALL）を追記。繰り返し ALL 更新での comment_option 伝播を明記。組織スコープの attendance_required 時 INSERT 対象を明記。`my_response` に reason を追加。未解決事項2件追加 |
| 2026-02-21 | `GET /teams/{id}/schedules/{scheduleId}/attendances` のレスポンス仕様を策定。メンバー個別の status / reason / survey_responses を返す。組織レベル集計に partial フィールドを追加 |
| 2026-02-21 | `schedules.comment_option`（HIDDEN / OPTIONAL / REQUIRED）を追加。省略時は同スコープの直近スケジュール設定を自動適用。出欠回答フローに REQUIRED バリデーションを追加 |
| 2026-02-21 | PARTIAL（遅刻・早退）ステータスを追加。出席率（広義・完全）計算定義を策定。チームダッシュボード用 `GET /teams/{id}/attendance-stats` と個人ダッシュボード用 `GET /me/attendance-stats` を追加 |
| 2026-02-21 | 精査: Google カレンダー個人同期・クロスチームスケジュール招待・出欠リマインダー・組織スケジュールのチーム別出欠集計を追加。テーブル5件追加（schedule_attendance_reminders / schedule_cross_refs / user_google_calendar_connections / user_calendar_sync_settings / user_schedule_google_events）。API 11本追加。Flyway V3.011〜V3.015 追加 |
| 2026-02-21 | 初版作成 |
