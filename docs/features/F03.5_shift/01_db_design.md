# F03.5 シフト管理 — §3 DB設計

> このファイルは [F03.5_shift/README.md](README.md) の一部です。

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `shift_schedules` | シフトスケジュールマスター（期間・状態・設定） | あり |
| `shift_positions` | チームのポジションマスター（表示名・並び順） | なし |
| `shift_slots` | 個別のシフト枠（日付・時間帯・ポジション・必要人数）。同一日に複数スロットを定義可能（時間帯分割） | なし |
| `shift_requests` | メンバーのシフト希望（希望収集フェーズ）。preference は5段階 | なし |
| `shift_swap_requests` | 公開後のシフト交代リクエスト（1 対 1 指名＋**【v2.1】**オープンコール全体募集） | なし |
| `shift_change_requests` | **【v2.1 新規】** 確定前（DRAFT/COLLECTING/ADJUSTING）の割当変更依頼 | なし |
| `member_availability_defaults` | メンバーの週間デフォルト可否プロファイル。preference は5段階 | なし |
| `shift_hourly_rates` | メンバーの時給設定（給与概算表示用） | なし |
| `shift_assignments` | 自動割当の実行結果履歴（監査・差し戻し用）【v2 新規】 | なし |
| `member_work_constraints` | メンバー単位の任意勤務制約（月次時間上限・連勤上限等）【v2 新規】 | なし |
| `shift_assignment_runs` | 自動割当バッチの実行ログ（実行ユーザー・戦略・実行時間・警告集計・**【v2.1】**目視確認承認記録） | なし |

### テーブル定義

#### `shift_schedules`

シフトスケジュールの管理単位。週次/月次のシフト表1枚に対し1レコード。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams。ON DELETE CASCADE |
| `title` | VARCHAR(200) | NO | — | シフト表タイトル（例: 「2026年3月第2週シフト」） |
| `period_type` | VARCHAR(20) | NO | 'WEEKLY' | 期間種別（WEEKLY / MONTHLY / CUSTOM） |
| `start_date` | DATE | NO | — | シフト期間の開始日 |
| `end_date` | DATE | NO | — | シフト期間の終了日 |
| `status` | VARCHAR(20) | NO | 'DRAFT' | シフトの状態（DRAFT / COLLECTING / ADJUSTING / PUBLISHED / ARCHIVED） |
| `request_deadline` | DATETIME | YES | NULL | 希望提出の締切日時（NULL = 管理者が手動で締め切る） |
| `note` | TEXT | YES | NULL | 管理者からのメモ（希望収集時にメンバーに表示） |
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users。ON DELETE SET NULL |
| `published_at` | DATETIME | YES | NULL | 公開日時 |
| `published_by` | BIGINT UNSIGNED | YES | NULL | FK → users。ON DELETE SET NULL |
| `is_reminder_sent` | BOOLEAN | NO | FALSE | 24h 前リマインド通知送信済みフラグ |
| `is_reminder_sent_48h` | BOOLEAN | NO | FALSE | **【v2 新規】** 48h 前リマインド通知送信済みフラグ |
| `is_low_submission_alerted` | BOOLEAN | NO | FALSE | 低提出率アラート送信済みフラグ |
| `last_auto_transition_at` | DATETIME | YES | NULL | バッチによる最終自動遷移日時（冪等性保証用） |
| `version` | BIGINT | NO | 0 | 楽観的ロックバージョン（@Version） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除日時（SoftDeletableEntity 適用） |

**インデックス**
```sql
INDEX idx_shift_schedules_team (team_id, start_date DESC)       -- チーム別のシフト一覧
INDEX idx_shift_schedules_status (team_id, status)              -- ステータス別フィルタ
INDEX idx_shift_schedules_period (start_date, end_date)         -- 期間指定の検索
```

**制約・備考**
- `status` のライフサイクル: `DRAFT` → `COLLECTING`（希望収集開始）→ `ADJUSTING`（希望締切後、管理者調整中）→ `PUBLISHED`（確定・公開）→ `ARCHIVED`（期間終了後）
- `COLLECTING` 状態の `shift_schedules` は同一チーム内で同時に複数存在可能（複数週分の希望を並行収集）
- 論理削除時: 配下の `shift_slots` と `shift_requests` はそのまま保持（復元時に復活）
- `period_type = 'CUSTOM'` は任意期間（例: 年末年始シフト等の変則期間）に使用
- `start_date` と `end_date` のバリデーション: `end_date >= start_date`。WEEKLY の場合は `end_date - start_date = 6日`、MONTHLY の場合は `start_date` が月初、`end_date` が月末であることを Service 層で検証
- `version`: 楽観的ロック（.claudecode.md §22）。PUT / PATCH 更新時に `WHERE version = :expected` で競合検出。競合時は 409 Conflict を返却
- `is_reminder_sent`: 希望収集リマインド通知の二重送信防止フラグ。リマインドバッチが送信時に `TRUE` に更新
- `is_low_submission_alerted`: 低提出率（50%未満）アラートの二重送信防止フラグ。deadline 48時間前に管理者に通知した際に `TRUE` に更新
- `last_auto_transition_at`: `COLLECTING → ADJUSTING` の自動遷移バッチが処理した日時。冪等性保証用。バッチは `last_auto_transition_at IS NULL OR last_auto_transition_at < request_deadline` を条件に対象を抽出する

#### `shift_positions`

チームのポジション（役割）マスター。スロット作成時にポジションを選択する際に使用し、表記ゆれ（「ホール」「ホール担当」「ﾎｰﾙ」等）を防止する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams。ON DELETE CASCADE |
| `name` | VARCHAR(50) | NO | — | ポジション名（例: ホール、キッチン、レジ） |
| `display_order` | INT | NO | 0 | 表示順序 |
| `is_active` | BOOLEAN | NO | TRUE | 有効フラグ。FALSE にするとスロット作成時の選択肢に表示しないが、既存スロットの参照は維持 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_shift_positions_team_name (team_id, name)             -- 同一チーム内のポジション名重複防止
INDEX idx_shift_positions_team (team_id, display_order)              -- チーム別の順序付き一覧
```

**制約・備考**
- ポジション未登録のチームはスロット作成時に `position_id = NULL` で運用可能（ポジション指定なしのシフト）
- `is_active = FALSE` のポジションに紐づく既存スロットは正常に参照可能（FK の整合性は維持）
- ポジション名の変更は `name` を UPDATE するだけで、既存スロットの表示にも即時反映される

#### `shift_slots`

シフト表内の個別枠。日付×時間帯×ポジション（役割）の組み合わせで1レコード。**v2 より同一日に複数スロットを定義することで時間帯分割に対応**（例: 同日の 11:00-15:00 = 3 人、15:00-18:00 = 2 人を2レコードで表現）。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → shift_schedules。ON DELETE CASCADE |
| `slot_date` | DATE | NO | — | シフト日付（深夜跨ぎスロットの場合は開始日を格納。例: 22:00-06:00 のスロットは slot_date = 開始日） |
| `start_time` | TIME | NO | — | 開始時刻 |
| `end_time` | TIME | NO | — | 終了時刻 |
| `position_id` | BIGINT UNSIGNED | YES | NULL | FK → shift_positions。ON DELETE SET NULL。NULL = ポジション指定なし |
| `required_count` | TINYINT UNSIGNED | NO | 1 | 必要人数（時間帯単位） |
| `assigned_user_ids` | JSON | YES | NULL | 確定した担当者の user_id 配列（例: `[10, 11, 12]`）。公開前は NULL |
| `note` | VARCHAR(200) | YES | NULL | 枠ごとの備考（例: 「新人研修あり」） |
| `version` | BIGINT | NO | 0 | 楽観的ロックバージョン（@Version） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_shift_slots_schedule_date (schedule_id, slot_date, start_time)  -- スケジュール内の日付順取得
INDEX idx_shift_slots_date (slot_date, start_time)                        -- 日付横断の検索
INDEX idx_shift_slots_schedule_position (schedule_id, position_id, slot_date)  -- ポジション×日付の集計（自動割当・サマリー用）
```

**制約・備考**
- `start_time` < `end_time` のバリデーションは Service 層で実施。深夜跨ぎ（例: 22:00-06:00）は `end_time < start_time` の場合に翌日跨ぎとして解釈し、フロントエンドで表示を調整する
- **時間帯分割（v2）**: 同一 `schedule_id + slot_date + position_id` に対し複数レコードを INSERT することで、時間帯別の必要人数設定を実現する。UNIQUE 制約は敢えて設けない（同日同ポジションで時間帯違いの複数枠を許容するため）。時間帯の重複は Service 層で検証し、完全重複（`start_time` と `end_time` が同一）は 409 エラー、部分重複は警告のみ（意図的な重複配置を許容。例: フル出勤者と短時間出勤者を同枠に配置）
- `assigned_user_ids` は JSON 配列で管理。正規化テーブルも検討したが、シフト枠あたりの割り当て人数が少数（通常1〜5人）のため JSON で十分。配列内の各 user_id はチームメンバーであることを Service 層で検証する。`GET /shifts/my` での検索（`JSON_CONTAINS`）はインデックスが効かないため、パフォーマンスが課題になった場合は Valkey キャッシュ（`mannschaft:cache:user-shifts:{userId}`、TTL 30分、シフト公開・変更時に無効化）で対応する
- `position_id`: FK → shift_positions。ポジションマスターから選択。`ON DELETE SET NULL` によりポジション削除時もスロットは保持（ポジション無指定扱いになる）。API レスポンスでは `position` オブジェクト（`{ "id": 1, "name": "ホール" }`）として返却
- スロットの削除は物理削除（論理削除不要。`shift_schedules` の論理削除で管理単位ごと保持する設計）
- `required_count` と `assigned_user_ids` の要素数が一致しない場合は「欠員」として UI で表示する
- `version`: 楽観的ロック。複数の ADMIN/DEPUTY_ADMIN が同一スロットの `assigned_user_ids` を同時編集する競合を防止。PUT 更新時に競合検出、409 Conflict を返却
- **既存スロットの移行（v2）**: v1 時点で存在する単一時間帯スロットはそのまま有効。v2 UI は同一日の複数スロットを時間軸に沿ってタイムラインで描画する

#### `shift_requests`

メンバーのシフト希望。希望収集フェーズ（`COLLECTING`）中にメンバーが提出する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → shift_schedules。ON DELETE CASCADE |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users。ON DELETE CASCADE |
| `slot_id` | BIGINT UNSIGNED | YES | NULL | FK → shift_slots。ON DELETE CASCADE。NULL = 日付レベルの希望（特定枠なし） |
| `slot_date` | DATE | NO | — | 希望日付（slot_id 指定時は shift_slots.slot_date と一致） |
| `preference` | VARCHAR(20) | NO | — | 希望度（**v2 5段階**: PREFERRED / AVAILABLE / WEAK_REST / STRONG_REST / ABSOLUTE_REST） |
| `note` | VARCHAR(200) | YES | NULL | 補足コメント（例: 「午後なら可」「早番希望」） |
| `submitted_at` | DATETIME | NO | CURRENT_TIMESTAMP | 提出日時 |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_shift_requests_user_slot (schedule_id, user_id, slot_id, slot_date)  -- 同一ユーザーの同一枠の重複防止（※ slot_id が NULL の場合 MySQL の NULL!=NULL により制約が効かないため、Service 層で追加バリデーション必須）
INDEX idx_shift_requests_schedule_user (schedule_id, user_id)                       -- ユーザー別の希望一覧
INDEX idx_shift_requests_schedule_date (schedule_id, slot_date)                     -- 日付別の希望集計
```

**制約・備考**
- `preference` は **v2 より5段階**:

  | 値 | 日本語表示 | 意味 | 自動割当スコア（既定・v2.3） |
  |---|---|---|---|
  | `PREFERRED` | 出勤希望 | できれば入れてほしい | +100 |
  | `AVAILABLE` | 指定なし | 通常勤務可 | 0 |
  | `WEAK_REST` | 出れなくはない | 弱い休み希望（できれば休みたい）【v2 新規】 | -30 |
  | `STRONG_REST` | できれば休み | 強い休み希望 | -80 |
  | `ABSOLUTE_REST` | 絶対休み | ハード制約（割当禁止） | `-Infinity`（割当不可） |

  > **v2.3 更新注記**: Phase 1 MVP 実装値に合わせて PREFERRED=+100 / WEAK_REST=-30 に上方修正。旧 v2.2 では PREFERRED=+50 / WEAK_REST=-20 としていたが、実機テスト（Phase 2 自動割当）の結果を踏まえ動的重み調整の余地を残しつつ、現行実装値で設計書を整合させた。

  管理者はこの優先度を参考にしてスロットに割り当てる。自動割当アルゴリズム（§5.10）は本スコアと他の要素（連勤ペナルティ・月次時間残等）を合算して割当候補を決定する。スコア値は `GreedyShiftAssignmentStrategy` の設定として定数化し、将来チーム設定で調整可能にする余地を残す
- CHECK 制約: `CHECK (preference IN ('PREFERRED','AVAILABLE','WEAK_REST','STRONG_REST','ABSOLUTE_REST'))` を付与（§23 準拠。VARCHAR + CHECK 制約方式）
- `slot_id = NULL` の場合は特定のスロットではなく日付レベルの希望を表す（「この日は出たい」等）。ポジション別スロットが設定されていない場合に使用
- 提出済みの希望は `COLLECTING` ステータス中のみ編集可能。`ADJUSTING` 以降は変更不可
- UNIQUE 制約: `schedule_id + user_id + slot_id + slot_date` の組み合わせで重複防止。`slot_id` が NULL のレコードは `slot_date` で一意に識別（MySQL の NULL 処理に注意: `NULL != NULL` のため、slot_id が NULL の場合の一意制御は Service 層で追加バリデーション）
- `slot_id` 指定時の `slot_date` 整合性: Service 層で `slot_date` が `shift_slots.slot_date` と一致することを検証。不一致の場合は 400 エラー
- ON DELETE CASCADE: スケジュール/スロット/ユーザー削除時に連動削除
- **v1 → v2 データ移行（後方互換）**: 既存の `UNAVAILABLE` レコードは `STRONG_REST` に変換する（従来の「できれば休み」扱い）。移行マイグレーション `V3.077__migrate_shift_request_preference_v2.sql` で `UPDATE shift_requests SET preference = 'STRONG_REST' WHERE preference = 'UNAVAILABLE';` を実行。ロールバック手順は §7 に詳細記載

#### `shift_swap_requests`

公開後のシフト交代リクエスト。**v2.1 より 2 パターンをサポートする**:

- **(A-2) 個別交代依頼**: `target_user_id` に特定メンバーを指名。`is_open_call = FALSE`。既存 v2 の挙動を踏襲
- **(A-3) オープンコール（全体募集）**: `target_user_id = NULL` かつ `is_open_call = TRUE`。チーム全員に broadcast し、先着で手を挙げたメンバーが `claimed_by` に記録される

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `slot_id` | BIGINT UNSIGNED | NO | — | FK → shift_slots。ON DELETE CASCADE |
| `requester_id` | BIGINT UNSIGNED | NO | — | FK → users。交代を依頼するメンバー |
| `target_user_id` | BIGINT UNSIGNED | YES | NULL | **【v2.1 新規】** FK → users。指名交代依頼の相手（NULL = オープンコール全体募集） |
| `is_open_call` | BOOLEAN | NO | FALSE | **【v2.1 新規】** TRUE = オープンコール全体募集。`target_user_id IS NULL` と組み合わせる |
| `claimed_by` | BIGINT UNSIGNED | YES | NULL | **【v2.1 新規】** FK → users。オープンコールで先着した候補メンバー（先着優先、楽観ロックで競合防止） |
| `claimed_at` | DATETIME | YES | NULL | **【v2.1 新規】** `claimed_by` が決まった日時 |
| `accepter_id` | BIGINT UNSIGNED | YES | NULL | FK → users。実際に交代を引き受けるメンバー。個別交代では指名相手、オープンコールでは `claimed_by` と同値になるのが通常。管理者が別候補に差し替えると上書き可能 |
| `status` | VARCHAR(20) | NO | 'PENDING' | リクエスト状態（PENDING / OPEN_CALL / CLAIMED / ACCEPTED / APPROVED / REJECTED / CANCELLED） |
| `reason` | VARCHAR(500) | YES | NULL | 交代理由（例: 「体調不良のため」） |
| `admin_note` | VARCHAR(500) | YES | NULL | 管理者コメント（承認・却下時） |
| `resolved_by` | BIGINT UNSIGNED | YES | NULL | FK → users。承認・却下した管理者 |
| `resolved_at` | DATETIME | YES | NULL | 承認・却下日時 |
| `version` | BIGINT | NO | 0 | **【v2.1 新規】** 楽観的ロック（オープンコールの先着 claim 競合防止用、@Version） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_shift_swap_requests_slot (slot_id)                          -- スロット別の交代リクエスト
INDEX idx_shift_swap_requests_requester (requester_id, status)        -- 依頼者の未解決リクエスト
INDEX idx_shift_swap_requests_status (status)                         -- 管理者の未処理一覧
INDEX idx_shift_swap_requests_open_call (is_open_call, status, slot_id)  -- 【v2.1】オープンコール一覧の検索
INDEX idx_shift_swap_requests_target (target_user_id, status)         -- 【v2.1】指名された相手の未解決一覧
```

**制約・備考**
- ステータスライフサイクル:
  - **個別交代（is_open_call=FALSE, target_user_id=NOT NULL）**: `PENDING` → `ACCEPTED`（指名相手が引き受け）→ `APPROVED`（管理者承認）/ `REJECTED`（管理者却下）/ `CANCELLED`（依頼者取下）
  - **オープンコール（is_open_call=TRUE, target_user_id=NULL）**: `OPEN_CALL`（募集中）→ `CLAIMED`（先着メンバー確定 = `claimed_by` 記録）→ `ACCEPTED`（依頼者 or 管理者が候補を確定、`accepter_id` = `claimed_by`）→ `APPROVED` / `REJECTED` / `CANCELLED`
- `APPROVED` 時の処理: スロットの `assigned_user_ids` から `requester_id` を除去し `accepter_id` を追加（1トランザクション内）。両メンバーにプッシュ通知
- 同一スロットに `PENDING` / `OPEN_CALL` / `CLAIMED` / `ACCEPTED` の交代リクエストは1件のみ（Service 層バリデーション）
- `PUBLISHED` 状態のスケジュールに属するスロットのみ交代リクエスト可能
- **【v2.1】`is_open_call` と `target_user_id` の排他**: CHECK 制約で `(is_open_call = TRUE AND target_user_id IS NULL) OR (is_open_call = FALSE)` を強制
- **【v2.1】`claimed_by` のレース条件対策**: 楽観的ロック（@Version）で「最初に `PATCH /{id}/claim` を発行した者が勝ち」のセマンティクスを保証。2人目以降は 409 Conflict で差し戻し、UI で「別の方が先に応じたため締め切られました」と Toast 表示
- **【v2.1】オープンコールの悪用防止**: 同一ユーザーが 1 ヶ月（作成時点の年月）に作成できるオープンコール数の上限を **3 件** とする。超過時は 429 Too Many Requests。カウントは `SELECT COUNT(*) FROM shift_swap_requests WHERE requester_id = ? AND is_open_call = TRUE AND YEAR(created_at) = ? AND MONTH(created_at) = ?` で判定
- **【v2.1】チーム全員への通知**: オープンコール作成時、チームメンバー全員（自分・SUPPORTER・GUEST を除く）にプッシュ + アプリ内通知を配信。ただし個人設定で「代打募集通知を受け取らない」を ON にしたユーザーは送信対象から除外（F04.3 通知設定を参照）
- **【v2.1】候補選定の裁量**: `CLAIMED` 状態でも管理者（ADMIN/DEPUTY_ADMIN）は `accepter_id` を別メンバーに差し替える裁量を持つ（例: 先着者がスキル不足の場合、他候補に差し替えて `ACCEPTED` に進める）。依頼者は差し替え不可（管理者のみ）

#### `shift_change_requests`【v2.1 新規】

確定前（`DRAFT / COLLECTING / ADJUSTING`）のスケジュールに対する割当変更依頼（A-1 パターン）。メンバーが「この日の割当を変えてほしい」「この枠を別の人に」「この日付・スロットに移してほしい」等を、スケジュール作成者（= 管理者/副管理者）に対して依頼する。

公開後の交代依頼は `shift_swap_requests`、確定前の変更依頼は本テーブルと完全に分離する（ライフサイクル・承認フロー・影響先が異なるため）。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → shift_schedules。ON DELETE CASCADE |
| `slot_id` | BIGINT UNSIGNED | YES | NULL | FK → shift_slots。ON DELETE CASCADE。対象スロット（特定枠に関する依頼の場合）。NULL = 日付レベル or スケジュール全体への依頼 |
| `requester_user_id` | BIGINT UNSIGNED | NO | — | FK → users。ON DELETE CASCADE。依頼を出したメンバー本人 |
| `request_type` | VARCHAR(30) | NO | — | 依頼種別（`SWAP_SELF` = 自分の割当を誰かと交代 / `CHANGE_DATE` = 別日付に移してほしい / `CHANGE_SLOT` = 別スロットに移してほしい / `CANCEL_SELF` = 自分の割当を外してほしい / `OTHER` = その他。reason 必須） |
| `target_user_id` | BIGINT UNSIGNED | YES | NULL | FK → users。ON DELETE SET NULL。SWAP_SELF で具体的な候補メンバーを推奨する場合のみ（任意・ヒント扱い。最終判断は管理者） |
| `target_slot_id` | BIGINT UNSIGNED | YES | NULL | FK → shift_slots。ON DELETE SET NULL。CHANGE_SLOT で移動先スロットを推奨する場合のみ（任意・ヒント扱い） |
| `target_slot_date` | DATE | YES | NULL | CHANGE_DATE で希望先日付を記入する場合のみ（任意） |
| `reason` | VARCHAR(1000) | NO | — | 依頼理由（必須。「通院が入った」「親戚の結婚式」等、管理者が判断するための背景情報） |
| `status` | VARCHAR(20) | NO | 'OPEN' | 依頼状態（`OPEN` = 提出済み・審査待ち / `ACCEPTED` = 管理者が受諾（スロットを実際に修正した）/ `REJECTED` = 管理者が却下 / `WITHDRAWN` = 依頼者本人が取下） |
| `reviewed_by` | BIGINT UNSIGNED | YES | NULL | FK → users。ON DELETE SET NULL。審査した管理者 |
| `reviewed_at` | DATETIME | YES | NULL | 審査日時 |
| `admin_note` | VARCHAR(1000) | YES | NULL | 管理者コメント（REJECTED 時は必須運用。依頼者に Toast で通知） |
| `version` | BIGINT | NO | 0 | 楽観的ロック（@Version） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_shift_change_requests_schedule (schedule_id, status, created_at DESC)  -- 管理者画面の未処理一覧
INDEX idx_shift_change_requests_requester (requester_user_id, status)            -- 依頼者の未解決一覧
INDEX idx_shift_change_requests_slot (slot_id, status)                           -- スロット単位の関連依頼
INDEX idx_shift_change_requests_type (schedule_id, request_type, status)         -- 種別別の集計
```

**制約・備考**
- **CHECK 制約**:
  - `CHECK (request_type IN ('SWAP_SELF','CHANGE_DATE','CHANGE_SLOT','CANCEL_SELF','OTHER'))`
  - `CHECK (status IN ('OPEN','ACCEPTED','REJECTED','WITHDRAWN'))`
- **受付対象ステータス**: 対象スケジュールの `status` が `DRAFT / COLLECTING / ADJUSTING` のいずれかである場合のみ新規作成可能。`PUBLISHED / ARCHIVED` は `shift_swap_requests`（交代）の管轄となるため、本テーブルでは受け付けない（Service 層で 409 Conflict）
- **提出可能期間**: `shift_schedules.status` が `PUBLISHED` に遷移するまで提出可能。`request_deadline`（希望提出締切）とは別軸で、あくまで公開前ならいつでも変更依頼を出せる
- **ステータスライフサイクル**:
  - `OPEN` → `ACCEPTED`（管理者が実際にスロットを手動修正して受諾）
  - `OPEN` → `REJECTED`（管理者が理由付きで却下）
  - `OPEN` → `WITHDRAWN`（依頼者本人が取下）
  - `ACCEPTED / REJECTED / WITHDRAWN` からの再遷移不可（1 回きりのワークフロー）
- **承認時の実スロット変更**: `ACCEPTED` への遷移は本テーブルの更新だけでは完結しない。管理者は UI 上でスロットを実際に修正（D&D で人を差し替え・時間帯移動・削除）し、その操作完了後に「この依頼を受諾した」ボタンで本テーブルを `ACCEPTED` に遷移させる。**テーブル側では実スロットの変更内容を記録しない**（監査ログで追跡する設計）
- **通知**:
  - `OPEN` 作成時: 管理者全員（ADMIN + DEPUTY_ADMIN の MANAGE_SHIFTS 保有者）にプッシュ + アプリ内通知
  - `ACCEPTED / REJECTED` 時: 依頼者にプッシュ + アプリ内通知。`admin_note` を本文に含める
  - `WITHDRAWN` 時: 管理者に通知（未処理一覧から消えることを知らせる）
- **監査ログ** (F10.3 連携):
  - `SHIFT_CHANGE_REQUEST_CREATED`（`requester_user_id`, `schedule_id`, `slot_id`, `request_type` を記録）
  - `SHIFT_CHANGE_REQUEST_REVIEWED`（`reviewed_by`, `status`（ACCEPTED/REJECTED）, `admin_note` を記録）
  - `SHIFT_CHANGE_REQUEST_WITHDRAWN`（`requester_user_id` を記録）
- **レートリミット**: 同一ユーザーが 1 つのスケジュールに対して同時に `OPEN` 状態の変更依頼を持てる上限は **5 件**（スパム防止）。超過時は 429
- **ロール制限**: 依頼作成は MEMBER / DEPUTY_ADMIN（自分に関する範囲のみ）。ADMIN 自身は直接スロットを修正すればよいので依頼作成 API を使う必要はないが、技術的には許可（管理者も依頼を出せる仕様）。SUPPORTER / GUEST は作成不可（403）

#### `member_availability_defaults`

メンバーの週間デフォルト勤務可否プロファイル。曜日×時間帯の可否を事前登録し、希望収集開始時に初期値として自動セットする。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users。ON DELETE CASCADE |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams。ON DELETE CASCADE |
| `day_of_week` | TINYINT UNSIGNED | NO | — | 曜日（0=月曜, 1=火曜, ..., 6=日曜） |
| `start_time` | TIME | YES | NULL | 可否の開始時刻（NULL = 終日） |
| `end_time` | TIME | YES | NULL | 可否の終了時刻（NULL = 終日） |
| `preference` | VARCHAR(20) | NO | — | デフォルト希望度（**v2 5段階**: PREFERRED / AVAILABLE / WEAK_REST / STRONG_REST / ABSOLUTE_REST） |
| `note` | VARCHAR(200) | YES | NULL | 補足（例: 「大学の授業のため」） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_availability_user_team_day_time (user_id, team_id, day_of_week, start_time, end_time)
INDEX idx_availability_team (team_id, user_id)                        -- チーム別の可否一覧
```

**制約・備考**
- `start_time` / `end_time` が両方 NULL の場合は終日の可否を表す。両方指定の場合は時間帯別の可否
- 同一曜日に複数レコード可能（例: 月曜 09:00-12:00 = AVAILABLE, 月曜 13:00-17:00 = STRONG_REST）
- CHECK 制約: `preference` も `shift_requests` と同じ5値に限定（`CHECK (preference IN ('PREFERRED','AVAILABLE','WEAK_REST','STRONG_REST','ABSOLUTE_REST'))`）
- プロファイルの適用: `DRAFT → COLLECTING` 遷移時に、各メンバーのデフォルト可否をスロットにマッチングし、`shift_requests` に初期値として INSERT。メンバーは COLLECTING 中に変更可能
- チーム単位で管理（同一ユーザーがチーム A では月曜可、チーム B では月曜不可というケースに対応）
- **v1 → v2 データ移行（後方互換）**: 既存の `UNAVAILABLE` レコードは `STRONG_REST` に変換する。`V3.077__migrate_shift_request_preference_v2.sql` で同一トランザクション内で処理

#### `shift_hourly_rates`

メンバーの時給設定。チームごとに異なる時給を登録可能。確定シフトの給与概算を自動計算してメンバーに表示する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users。ON DELETE CASCADE |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams。ON DELETE CASCADE |
| `hourly_rate` | DECIMAL(10,2) | NO | — | 時給（円）。例: 1200.00 |
| `effective_from` | DATE | NO | — | 適用開始日。時給改定時に新レコードを追加 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_hourly_rates_user_team_date (user_id, team_id, effective_from)  -- 同一日に複数時給を防止
INDEX idx_hourly_rates_team (team_id, user_id)                                 -- チーム別の時給一覧（管理者用）
```

**制約・備考**
- 時給は履歴管理。改定時は新しい `effective_from` のレコードを追加し、過去の時給は保持（過去シフトの給与概算に使用）
- 現在の時給 = `effective_from <= TODAY` の最新レコード
- 時給の閲覧権限: **本人 + ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）のみ**。他メンバーの時給は非公開
- 時給未設定のメンバーには給与概算を表示しない（`null` として返却）
- 給与概算の計算: スロットの勤務時間（hours）× 適用時給。深夜跨ぎスロットの勤務時間は実時間で計算

#### `shift_assignments`【v2 新規】

自動割当の実行結果を保存する監査・差し戻し用のテーブル。`shift_slots.assigned_user_ids` は「現在の割当状態」を示すキャッシュ的な JSON 配列であるのに対し、本テーブルは「誰がいつどの戦略で割り当てたか」を個別レコードとして履歴保持する。手動割当も自動割当も同じテーブルに記録する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `slot_id` | BIGINT UNSIGNED | NO | — | FK → shift_slots。ON DELETE CASCADE |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users。ON DELETE CASCADE |
| `status` | VARCHAR(20) | NO | 'PROPOSED' | 割当状態（PROPOSED / CONFIRMED / REVOKED） |
| `assigned_by_strategy` | VARCHAR(40) | NO | — | 採用された割当戦略（`MANUAL` / `GREEDY_V1` / `CSP_V1` など） |
| `score` | INT | YES | NULL | 戦略が算出したスコア値。`MANUAL` のときは NULL |
| `run_id` | BIGINT UNSIGNED | YES | NULL | FK → shift_assignment_runs。ON DELETE SET NULL。自動割当実行のグループ ID |
| `assigned_by` | BIGINT UNSIGNED | YES | NULL | FK → users。ON DELETE SET NULL。手動割当時の実施管理者 ID |
| `note` | VARCHAR(200) | YES | NULL | 任意コメント（例: 「希望優先で割当」） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_shift_assignments_slot (slot_id, status)                         -- スロット別の最新割当
INDEX idx_shift_assignments_run (run_id, status)                           -- 実行グループ別の集計
INDEX idx_shift_assignments_user_date (user_id, created_at DESC)           -- ユーザー別の履歴
UNIQUE KEY uq_shift_assignments_slot_user_active (slot_id, user_id, status)  -- 同一スロット×同一ユーザー×同一状態の重複防止
```

**制約・備考**
- ステータス: `PROPOSED`（自動割当の提案）→ `CONFIRMED`（管理者が確定）/ `REVOKED`（取り消し）
- `PROPOSED` 状態の割当は `shift_slots.assigned_user_ids` には反映されない。管理者が UI で確認・修正してから「確定」アクションで `CONFIRMED` に遷移し、同時に `shift_slots.assigned_user_ids` を一括更新する
- `REVOKED` 状態は履歴として残す（監査証跡）。物理削除は行わない
- `assigned_by_strategy` の列挙値は Java 側の `AssignmentStrategyType` Enum（`MANUAL / GREEDY_V1 / CSP_V1`）と一致させる。CHECK 制約で値を限定
- `run_id` が NULL の場合は手動割当（D&D 操作・PUT `/slots/{id}` 等）。非 NULL の場合は自動割当バッチ経由
- `score` はデバッグ・将来のアルゴリズム検証用。負の値（-∞ を除く）を格納。`ABSOLUTE_REST` に該当するユーザーはそもそも割当されないため、`score` が極端な負値になることはない
- UNIQUE 制約は `(slot_id, user_id, status)` 三項組で、同一スロット・同一ユーザーに対して `CONFIRMED` 状態が複数同時に存在しないことを担保する（`REVOKED` は重複可）

#### `member_work_constraints`【v2 新規】

メンバー単位の**任意**勤務制約。チーム単位のデフォルト値と、個別オーバーライドの両方をサポート。全項目が NULL 可能で、設定されていない制約は単純に無視される（オプトイン方式）。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams。ON DELETE CASCADE |
| `user_id` | BIGINT UNSIGNED | YES | NULL | FK → users。ON DELETE CASCADE。NULL = チームのデフォルト値（全メンバー適用） |
| `max_monthly_hours` | DECIMAL(6,2) | YES | NULL | 月次労働時間上限（h）。例: 100.00。NULL = 制約なし |
| `max_monthly_days` | TINYINT UNSIGNED | YES | NULL | 月次勤務日数上限。例: 23。NULL = 制約なし |
| `max_consecutive_days` | TINYINT UNSIGNED | YES | NULL | 連続勤務日数上限。例: 5。NULL = 制約なし |
| `max_night_shifts_per_month` | TINYINT UNSIGNED | YES | NULL | 夜勤上限（月）。例: 4。NULL = 制約なし。夜勤判定は `start_time >= 22:00 OR end_time <= 06:00` |
| `min_rest_hours_between_shifts` | DECIMAL(4,2) | YES | NULL | シフト間の最低休息時間（h）。例: 11.00（法定準拠目安）。NULL = 制約なし |
| `note` | VARCHAR(500) | YES | NULL | 運用メモ（例: 「学生のため月80時間まで」） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_member_work_constraints_team_user (team_id, user_id)  -- チーム×ユーザーで一意（user_id NULL はチームデフォルト）
INDEX idx_member_work_constraints_team (team_id)
```

**制約・備考**
- UNIQUE 制約: `team_id + user_id` の組で一意。ただし MySQL の `NULL != NULL` により `user_id IS NULL` のレコードは複数作成可能な動作となるため、**チームデフォルト（`user_id IS NULL`）は Service 層で1チーム1件制御**する
- 解決順序: メンバー向け個別レコード（`user_id = {userId}`）→ チームデフォルト（`user_id IS NULL`）→ NULL（制約なし）の順で参照。同一項目を個別 NULL で明示的に「上書きして無効化」したい場合のため、個別レコードが存在すれば優先する仕様
- 制約の反映先:
  - 自動割当アルゴリズム（§5.10 Strategy）でハード制約 or ソフト制約として考慮（全項目ソフト、`ABSOLUTE_REST` と連動しない）
  - 公開直前チェック（`PATCH /publish`）で警告表示
  - D&D UI で手動割当した際に超過したら黄色ハイライト
- 全項目 NULL のレコードは意味がないため、Service 層で INSERT を拒否（400 エラー）
- 夜勤判定は `slot.start_time >= 22:00 OR slot.end_time <= 06:00`（22時以降に開始、または6時以前に終了）を OR 条件で評価。時刻比較は JST ローカル時刻ベース
- `min_rest_hours_between_shifts` の判定は「前のシフトの終了時刻」と「次のシフトの開始時刻」の差。深夜跨ぎスロット（`end_time < start_time`）は翌日跨ぎとして計算

#### `shift_assignment_runs`【v2 新規】

自動割当バッチの実行ログ。「誰がいつ実行した」「どの戦略で」「何件提案したか」「どんな警告が出たか」を記録する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `schedule_id` | BIGINT UNSIGNED | NO | — | FK → shift_schedules。ON DELETE CASCADE |
| `strategy` | VARCHAR(40) | NO | — | 使用した戦略（`GREEDY_V1` / `CSP_V1` 等） |
| `status` | VARCHAR(20) | NO | 'RUNNING' | 実行状態（RUNNING / SUCCEEDED / FAILED / CANCELLED） |
| `triggered_by` | BIGINT UNSIGNED | YES | NULL | FK → users。ON DELETE SET NULL。実行した管理者 ID |
| `started_at` | DATETIME | NO | CURRENT_TIMESTAMP | 実行開始日時 |
| `finished_at` | DATETIME | YES | NULL | 実行終了日時 |
| `duration_ms` | INT UNSIGNED | YES | NULL | 実行時間（ミリ秒） |
| `slots_total` | INT UNSIGNED | NO | 0 | 対象スロット数 |
| `slots_filled` | INT UNSIGNED | NO | 0 | 充足したスロット数 |
| `warnings_json` | JSON | YES | NULL | 警告の集計（欠員・制約違反等） |
| `parameters_json` | JSON | YES | NULL | 実行時パラメータ（スコア重み・制約ON/OFF等） |
| `error_message` | VARCHAR(1000) | YES | NULL | 失敗時のエラーメッセージ |
| `visual_review_confirmed_by` | BIGINT UNSIGNED | YES | NULL | **【v2.1 新規】** FK → users。ON DELETE SET NULL。目視確認を承認した管理者 |
| `visual_review_confirmed_at` | DATETIME | YES | NULL | **【v2.1 新規】** 目視確認承認日時。NULL = 未確認（この状態では PUBLISHED に遷移不可） |
| `visual_review_note` | VARCHAR(500) | YES | NULL | **【v2.1 新規】** 目視確認時のメモ（「繁忙期なので追加調整済み」「有資格者が 17 時台に不在だったため手動で差し替え」等） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_shift_assignment_runs_schedule (schedule_id, started_at DESC)
INDEX idx_shift_assignment_runs_status (status, started_at DESC)
INDEX idx_shift_assignment_runs_visual_review (schedule_id, visual_review_confirmed_at)  -- 【v2.1】未確認 run の検出用
```

**制約・備考**
- 同一スケジュールに対して複数回の実行が可能。最新の成功実行の `shift_assignments` のみを UI に反映
- `RUNNING` 状態は通常数秒以内に完了するが、タイムアウト（60秒）を超えた場合は別バッチが `FAILED` に更新（stale run 検知）
- `warnings_json` のスキーマ例:
  ```json
  {
    "vacancy": [
      { "slot_id": 101, "required": 3, "assigned": 2 }
    ],
    "constraint_violations": [
      { "user_id": 10, "type": "MAX_MONTHLY_HOURS", "expected": 100, "actual": 105 }
    ]
  }
  ```
- 監査ログ（F10.3）との連携: `SHIFT_AUTO_ASSIGN_EXECUTED` イベントに `run_id` を含めて記録
- **【v2.1】目視確認カラムの運用**:
  - 自動割当 (`POST /auto-assign`) を 1 回でも実行したスケジュールは、その後 `ADJUSTING → PUBLISHED` への遷移時に「最新成功 run の `visual_review_confirmed_at IS NOT NULL`」であることを必須チェック
  - 自動割当を使わず手動のみで組んだスケジュールは、`shift_assignment_runs` レコードが存在しないので本チェックをスキップ（ただし UI 上では「目視確認しましたか？」ダイアログは表示。DB レベルの強制はしない）
  - 確定 (`/auto-assign/confirm`) と目視確認は別軸。確定 = PROPOSED を `assigned_user_ids` に反映する操作、目視確認 = 結果全体を管理者が見て OK と判断する操作。通常は確定後に目視確認を行う運用だが、確定せず全て手動で組み直した場合でも目視確認のみ記録することを許容
  - **API**: `POST /api/v1/shifts/assignment-runs/{runId}/confirm-visual-review` で確認を記録（§4 参照）
  - **監査ログ**: `SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED`（`run_id`, `schedule_id`, `visual_review_confirmed_by`, `visual_review_note` を記録）

### ER図（テキスト形式）
```
teams (1) ──── (N) shift_schedules
teams (1) ──── (N) shift_positions
teams (1) ──── (N) member_work_constraints
shift_schedules (1) ──── (N) shift_slots
shift_schedules (1) ──── (N) shift_requests
shift_schedules (1) ──── (N) shift_assignment_runs
shift_schedules (1) ──── (N) shift_change_requests【v2.1】
shift_positions (1) ──── (N) shift_slots（position_id; 任意）
shift_slots (1) ──── (N) shift_requests（slot_id; 任意）
shift_slots (1) ──── (N) shift_swap_requests
shift_slots (1) ──── (N) shift_change_requests（slot_id; 任意）【v2.1】
shift_slots (1) ──── (N) shift_assignments
shift_assignment_runs (1) ──── (N) shift_assignments（run_id; 自動割当分のみ）

users (1) ──── (N) shift_requests（user_id）
users (1) ──── (N) shift_schedules（created_by）
users (1) ──── (N) shift_swap_requests（requester_id / accepter_id / target_user_id【v2.1】 / claimed_by【v2.1】）
users (1) ──── (N) shift_change_requests（requester_user_id / target_user_id / reviewed_by）【v2.1】
users (1) ──── (N) member_availability_defaults（user_id）
users (1) ──── (N) shift_hourly_rates（user_id）
users (1) ──── (N) shift_assignments（user_id / assigned_by）
users (1) ──── (N) member_work_constraints（user_id; 任意）
users (1) ──── (N) shift_assignment_runs（triggered_by / visual_review_confirmed_by【v2.1】）
```

---


---

*前: [README.md](README.md) | 次: [02_api_design.md](02_api_design.md)*
