# F05: 個人スケジュール管理

> **ステータス**: 🟢 設計完了
> **実装フェーズ**: Phase 3
> **最終更新**: 2026-03-12
> **関連ドキュメント**: 組織・チームスケジュール → `F05_schedule_shared.md` / 外部連携 → `F08_external_integration.md`

---

## 1. 概要

ユーザーが個人用の予定をアプリ内で直接作成・編集・削除できる機能。チーム・組織スケジュールとは独立した個人スコープの予定管理。繰り返し予定をサポートし、個人の Google カレンダーへの同期（アプリ→Google 一方向、将来的に双方向）にも対応する。

**個人予定は完全非公開**（本人以外は閲覧・編集不可）であり、出欠確認・アンケート等の多人数向けロジックは適用しない。`GET /my/calendar` の横断ビューには個人予定も含まれ、チーム・組織スケジュールと統合表示される。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| 本人（任意ロール） | 自分の個人予定の作成・編集・削除・閲覧 |
| 他者（ADMIN 含む） | アクセス不可（403）|

### 対象レベル
- [ ] 組織 (Organization)
- [ ] チーム (Team)
- [x] 個人 (Personal)

---

## 3. DB設計

### 基本方針

既存の `schedules` テーブルを再利用する。`user_id` カラムを追加し、`team_id`・`organization_id`・`user_id` の三者うちいずれか1つのみが非 NULL であることを DB レベルの CHECK 制約で保証する（詳細は `F05_schedule_shared.md` Section 3 参照）。

**採用理由:**
- `user_schedule_google_events` テーブルが `schedule_id` で参照しているため変更不要（Google 同期が流用可能）
- 繰り返しルール（`recurrence_rule` JSON）・展開バッチがそのまま流用できる
- `GET /my/calendar` で `team_id OR organization_id OR user_id` の単一クエリで横断取得でき UNION 不要

### 個人スコープ専用カラム

`schedules` テーブルに以下のカラムを追加する。個人スコープ（`user_id IS NOT NULL`）でのみ使用し、チーム・組織スコープでは NULL 固定とする。

| カラム | 型 | デフォルト | 説明 |
|--------|---|-----------|------|
| `color` | `VARCHAR(7) NULL` | `NULL` | カレンダー表示用カラーラベル（例: `#FF5733`）。UI で予定を視覚的に分類する用途 |

### 個人スコープでの固定値フィールド

`user_id IS NOT NULL` のスケジュールに対し、以下フィールドは固定値とし変更不可（アプリ層でバリデーション。リクエストに含まれた場合は無視する）:

| フィールド | 固定値 | 理由 |
|-----------|--------|------|
| `attendance_required` | `FALSE` | 個人予定に出欠確認は不要 |
| `attendance_status` | `READY` | `schedule_attendances` が生成されないため |
| `min_view_role` | `'ADMIN_ONLY'` | アクセス制御はオーナーチェックで実施（フィールド値は意味なし）|
| `visibility` | `'MEMBERS_ONLY'` | 個人スコープでは visibility 概念が無意味 |
| `min_response_role` | `'ADMIN_ONLY'` | 同上 |
| `comment_option` | `'HIDDEN'` | 個人予定に補足コメント欄は不要 |

### personal_schedule_reminders テーブル

個人予定に対するリマインダー通知設定。1予定につき最大3件まで設定可能。

```
personal_schedule_reminders
├── id                    BIGINT UNSIGNED AUTO_INCREMENT PK
├── schedule_id           BIGINT UNSIGNED NOT NULL  FK → schedules(id) ON DELETE CASCADE
├── remind_before_minutes INT UNSIGNED NOT NULL     -- 予定開始の何分前に通知するか（例: 10, 60, 1440）
├── notified              BOOLEAN NOT NULL DEFAULT FALSE  -- 通知送信済みフラグ
├── created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
└── UNIQUE KEY uq_reminder (schedule_id, remind_before_minutes)
```

> - バッチ処理で `remind_at = (start_at - remind_before_minutes) <= NOW() AND notified = FALSE` を検出し、アプリ内通知を発火
> - 繰り返し予定の場合、展開された各子スケジュールに対して親のリマインダー設定を継承する
> - 通知チャネル: アプリ内通知（`notifications` テーブルへの INSERT）。プッシュ通知は Phase 4+ で対応

### Flyway マイグレーション（shared との共有）

```sql
V3.016__add_user_id_to_schedules.sql
  -- schedules テーブルへの変更:
  --   user_id BIGINT UNSIGNED NULL ADD COLUMN
  --   FK: user_id → users (ON DELETE CASCADE)
  --   INDEX idx_sch_user_start (user_id, start_at)
  --   CHECK chk_schedule_scope (三者XOR制約)
```

> V3.016 は V3.007（schedules）および V1.005（users）完了後に実行。F05_schedule_shared.md のマイグレーション一覧と共有する。

```sql
V3.018__add_personal_schedule_columns.sql
  -- schedules テーブルへの変更:
  --   color VARCHAR(7) NULL ADD COLUMN
  -- personal_schedule_reminders テーブルの作成
```

> V3.018 は V3.016 完了後に実行。

---

## 4. API設計

### エンドポイント一覧

| メソッド | パス | 認証 | 説明 |
|---------|------|------|------|
| POST | `/api/v1/me/schedules` | 必要 | 個人予定の新規作成 |
| GET | `/api/v1/me/schedules` | 必要 | 個人予定の一覧取得 |
| GET | `/api/v1/me/schedules/{id}` | 必要 | 個人予定の詳細取得 |
| PATCH | `/api/v1/me/schedules/{id}` | 必要 | 個人予定の更新（繰り返しは `?update_scope` で範囲指定）|
| DELETE | `/api/v1/me/schedules/{id}` | 必要 | 個人予定の削除（繰り返しは `?update_scope` で範囲指定）|
| DELETE | `/api/v1/me/schedules/batch` | 必要 | 個人予定の一括削除（最大50件）|

> 繰り返しの `update_scope`（THIS_ONLY / THIS_AND_FOLLOWING / ALL）の動作は `F05_schedule_shared.md` Section 5 のロジックを準用する。

### リクエスト/レスポンス仕様

#### `POST /api/v1/me/schedules`

**リクエストボディ**
```json
{
  "title": "歯医者の予約",
  "description": null,
  "location": "〇〇歯科",
  "start_at": "2026-04-10T14:00:00",
  "end_at": "2026-04-10T15:00:00",
  "all_day": false,
  "event_type": "OTHER",
  "color": "#4A90D9",
  "reminders": [10, 60],
  "recurrence_rule": null
}
```

> **`event_type` の許可値（個人スコープ）**: 共有スケジュールと同じ enum（`PRACTICE`, `MATCH`, `MEETING`, `EVENT`, `OTHER`）に加え、個人スコープ専用の `PERSONAL`（デフォルト値）を使用可能。チーム練習を個人カレンダーにメモする等の用途で共有用の値も選択可。
>
> **`reminders`**: 予定開始の何分前に通知するかを配列で指定（最大3件）。省略時はリマインダーなし。

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 500,
    "title": "歯医者の予約",
    "description": null,
    "location": "〇〇歯科",
    "start_at": "2026-04-10T14:00:00",
    "end_at": "2026-04-10T15:00:00",
    "all_day": false,
    "event_type": "OTHER",
    "color": "#4A90D9",
    "status": "SCHEDULED",
    "parent_schedule_id": null,
    "recurrence_rule": null,
    "is_exception": false,
    "reminders": [10, 60],
    "google_synced": false,
    "created_at": "2026-03-01T10:00:00Z",
    "updated_at": "2026-03-01T10:00:00Z"
  }
}
```

> **`google_synced`**: `user_schedule_google_events` テーブルに対応レコードが存在する場合 `true`。Google カレンダー同期状態の確認・トラブルシューティングに使用

> - `attendance_required`・`min_view_role`・`visibility`・`attendance_status` 等の固定フィールドはレスポンスに含めない（個人スコープでは意味を持たないため）
> - リクエストボディにこれらが含まれていた場合は無視する（400 にはしない）

#### `GET /api/v1/me/schedules`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 当月1日 | 開始日（inclusive）|
| `to` | ISO 8601 date | 当月末日 | 終了日（inclusive）|
| `q` | string | — | キーワード検索（`title` と `location` を部分一致検索）|
| `event_type` | string | — | `event_type` でフィルタ（例: `PERSONAL`, `PRACTICE`）|
| `cursor` | string | — | ページネーションカーソル（前回レスポンスの `next_cursor`）|
| `size` | int | `50` | 1ページあたりの取得件数（最大 100）|

> - `from`〜`to` の期間は最長 12ヶ月。超過する場合は 400 Bad Request を返す
> - 返却レコードは `start_at` 昇順
> - カーソルは `start_at` + `id` の複合キーでエンコード

**レスポンス（200 OK）**
```json
{
  "data": [ ... ],
  "meta": {
    "size": 50,
    "has_next": true,
    "next_cursor": "eyJzIjoiMjAyNi0wNC0xMFQxNDowMDowMCIsImlkIjo1MDB9"
  }
}
```

#### `PATCH /api/v1/me/schedules/{id}`

**レスポンス（200 OK）**: 更新後の個人予定オブジェクト（POST レスポンスと同一スキーマ）を返す。

#### `DELETE /api/v1/me/schedules/{id}`

**レスポンス**: `204 No Content`（ボディなし）

#### `DELETE /api/v1/me/schedules/batch`

**リクエストボディ**
```json
{
  "ids": [500, 501, 502]
}
```

> - 最大50件。超過する場合は 400 Bad Request
> - 他人の予定 ID が含まれていた場合は該当 ID をスキップ（部分成功）
> - 繰り返し予定が含まれている場合、該当の単一インスタンスのみ削除（`update_scope = THIS_ONLY` 相当）

**レスポンス（200 OK）**
```json
{
  "data": {
    "deleted_count": 3,
    "skipped_count": 0
  }
}
```

**エラーレスポンス共通**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | `schedules.user_id` が current_user_id と不一致（他人の予定へのアクセス試行）|
| 404 | スケジュールが存在しない / 論理削除済み |
| 422 | 個人スコープに許可されない操作（`attendance_required = TRUE` の設定等）|

---

## 5. ビジネスロジック

### 認可ロジック（オーナーチェック）

全エンドポイントで `schedules.user_id = current_user_id` を必須確認する。不一致の場合はスケジュールの存在有無を返さず 403 を返す（存在確認による情報漏洩を防ぐ）。

### 個人予定作成フロー（単発）

```
1. POST /api/v1/me/schedules を受付
2. バリデーション（start_at < end_at、title 必須 等）
3. schedules に INSERT:
   - user_id = current_user_id、team_id = NULL、organization_id = NULL
   - 固定値: attendance_required = FALSE、attendance_status = READY、
     min_view_role = 'ADMIN_ONLY'、visibility = 'MEMBERS_ONLY'、
     min_response_role = 'ADMIN_ONLY'、comment_option = 'HIDDEN'
4. Google カレンダー同期（user_google_calendar_connections.is_active = TRUE かつ
   personal_sync_enabled = TRUE の場合のみ @Async で実施）
5. audit_logs に PERSONAL_SCHEDULE_CREATED を記録
6. 201 Created を返す
```

### 個人予定更新フロー

```
1. PATCH /api/v1/me/schedules/{id} を受付
2. オーナーチェック（user_id = current_user_id）
3. バリデーション（start_at < end_at 等）
4. schedules を UPDATE（固定フィールドはリクエストに含まれていても無視）
5. reminders の差分更新（personal_schedule_reminders の INSERT/DELETE）
6. Google カレンダー同期（有効時のみ @Async で更新イベント送信）
7. audit_logs に PERSONAL_SCHEDULE_UPDATED を記録
   - metadata: { "update_scope": "THIS_ONLY" | "THIS_AND_FOLLOWING" | "ALL" }（繰り返し予定の場合）
8. 200 OK + 更新後オブジェクトを返す
```

### 個人予定削除フロー

```
1. DELETE /api/v1/me/schedules/{id} を受付
2. オーナーチェック（user_id = current_user_id）
3. schedules を論理削除（deleted_at = NOW()）
4. Google カレンダー同期（有効時のみ @Async で削除イベント送信）
5. audit_logs に PERSONAL_SCHEDULE_DELETED を記録
   - metadata: { "update_scope": "THIS_ONLY" | "THIS_AND_FOLLOWING" | "ALL" }（繰り返し予定の場合）
6. 204 No Content を返す
```

### 監査ログイベント種別

| イベント | 記録タイミング | metadata |
|---------|--------------|----------|
| `PERSONAL_SCHEDULE_CREATED` | POST 成功時 | `{ "schedule_id", "title", "event_type" }` |
| `PERSONAL_SCHEDULE_UPDATED` | PATCH 成功時 | `{ "schedule_id", "update_scope", "changed_fields": [...] }` |
| `PERSONAL_SCHEDULE_DELETED` | DELETE 成功時 | `{ "schedule_id", "update_scope" }` |
| `PERSONAL_SCHEDULE_BATCH_DELETED` | バッチ DELETE 成功時 | `{ "deleted_count", "skipped_count" }` |

### 繰り返し予定の展開

繰り返しルール（`recurrence_rule`）の展開ロジック・バッチ補完・Idempotency は `F05_schedule_shared.md` Section 5 の「繰り返しスケジュール自動展開バッチ」と同一のロジックを流用する。

### リマインダー通知バッチ

```
1. 毎分実行のバッチで以下を検索:
   SELECT r.*, s.title, s.start_at, s.user_id
   FROM personal_schedule_reminders r
   JOIN schedules s ON r.schedule_id = s.id
   WHERE r.notified = FALSE
     AND s.deleted_at IS NULL
     AND s.user_id IS NOT NULL
     AND DATE_SUB(s.start_at, INTERVAL r.remind_before_minutes MINUTE) <= NOW()
2. 対象リマインダーごとに notifications テーブルへ INSERT
   - type: PERSONAL_SCHEDULE_REMINDER
   - message: "{title} が {remind_before_minutes}分後に開始します"
3. notified = TRUE に UPDATE
```

> - バッチは冪等（`notified = FALSE` 条件で重複通知を防止）
> - 繰り返し予定の子スケジュール展開時に、親のリマインダー設定を子にコピーする

### 完全非公開の保証

- `user_id IS NOT NULL` のスケジュールは、`user_id = current_user_id` の確認なしに API から取得・変更できない
- `GET /teams/{id}/schedules` 等のチーム・組織系エンドポイントのクエリには `user_id IS NULL` を必須条件として付与し、個人スコープのレコードが混入しないようにする
- `GET /my/calendar`（横断ビュー）のみ `user_id = current_user_id` のレコードを含めて返す（`scope_type = "PERSONAL"` として統合表示。`F05_schedule_shared.md` Section 4 参照）

---

## 6. セキュリティ考慮事項

- **オーナーチェック必須**: 全エンドポイントで `schedules.user_id = current_user_id` を確認。スケジュール ID の連番推測による他人の個人予定へのアクセスを 403 で防止する（存在有無も返さない）
- **チーム/組織スコープへの混入防止**: チーム・組織系エンドポイントのクエリには `user_id IS NULL` を必須条件として付与する
- **固定フィールドの強制**: `attendance_required`・`min_view_role` 等を固定値に強制するアプリ層バリデーションを実施し、個人予定が出欠対象として扱われることを防ぐ
- **ADMIN も閲覧不可**: 個人予定は ADMIN であっても `user_id = current_user_id` でない限り 403 を返す。SYSTEM_ADMIN 向けの強制閲覧エンドポイントは設けない（プライバシーポリシー上の設計判断）
- **個人予定数のソフトリミット**: ユーザーあたりアクティブ個人予定（`deleted_at IS NULL AND user_id = current_user_id`）は最大 **1,000件**。上限到達時は `422 Unprocessable Entity`（`error: "PERSONAL_SCHEDULE_LIMIT_REACHED"`）を返す。繰り返し予定の展開済み子スケジュールもカウント対象
- **一括削除の上限**: `DELETE /batch` は1リクエストあたり最大50件。超過時は 400 Bad Request

---

## 7. 未解決事項

- [x] Google → アプリ への双方向同期（Google Calendar Webhook Push Notifications）の実装フェーズを確定する → **Phase 4+** に決定。Phase 3 は app→Google 一方向同期のみ実装。双方向同期は Webhook 受信サーバーの運用が複雑なため後回し
- [x] `GET /my/calendar` のレスポンスに個人スケジュールを含める形への更新 → `scope_type = "PERSONAL"`・`scope_id = null`・`scope_name = "個人"` として統合。`min_view_role` / `min_response_role` / `my_response` は null を返す（`F05_schedule_shared.md` Section 4 更新済み）
- [x] 個人スケジュールを Google カレンダー同期する際の設定管理方法を確定 → `user_calendar_sync_settings` への scope_type='PERSONAL' 追加は不採用（`scope_id` が個人スコープには存在しないため）。代わりに `user_google_calendar_connections.personal_sync_enabled BOOLEAN DEFAULT FALSE` を追加（`F05_schedule_shared.md` Section 3 更新済み・Flyway V3.017 追加）

---

## 8. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-12 | 設計改善10件反映: ① 一括削除API追加（`DELETE /batch`、最大50件）。② カラーラベル（`color VARCHAR(7)`）追加。③ リマインダー通知（`personal_schedule_reminders` テーブル新設、最大3件/予定、バッチ毎分実行）。④ `GET /me/schedules` にキーワード検索（`q`）・`event_type` フィルタ追加。⑤ カーソルベースページネーション導入（`cursor` + `size` + `has_next` + `next_cursor`）。⑥ レスポンスに `google_synced` フラグ追加。⑦ `event_type` に個人スコープ専用 `PERSONAL` を追加・バリデーション明記。⑧ PATCH（200 + 更新後オブジェクト）・DELETE（204）・バッチDELETE のレスポンス仕様明記。⑨ 監査ログイベント種別網羅（CREATED/UPDATED/DELETED/BATCH_DELETED + metadata）。⑩ 個人予定ソフトリミット1,000件追加。Flyway V3.018 追加 |
| 2026-03-01 | 未解決事項を全件解決: ① Google→App 双方向同期を Phase 4+ に決定（Phase 3 は一方向のみ）。② `GET /my/calendar` に個人スケジュールを統合（`F05_schedule_shared.md` Section 4 更新）。③ 個人 Google 同期設定を `user_google_calendar_connections.personal_sync_enabled` で管理に確定（`user_calendar_sync_settings` への PERSONAL 追加は不採用）。作成フロー step 4 を `personal_sync_enabled` 参照に修正 |
| 2026-03-11 | ステータスを 🟢 設計完了 に変更（未解決事項が全件解決済み）。最終更新日を更新 |
| 2026-03-01 | 初版作成: `schedules` テーブル再利用・完全非公開方針（オーナーチェックのみ）を採用。F05_schedule_attendance.md を F05_schedule_shared.md にリネームして分割 |
