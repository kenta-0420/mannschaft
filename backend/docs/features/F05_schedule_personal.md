# F05: 個人スケジュール管理

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 3
> **最終更新**: 2026-03-01
> **関連ドキュメント**: 組織・チームスケジュール → `F05_schedule_shared.md` / 外部連携 → `F08_external_integration.md`（予定）

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
  "recurrence_rule": null
}
```

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
    "status": "SCHEDULED",
    "parent_schedule_id": null,
    "recurrence_rule": null,
    "is_exception": false,
    "created_at": "2026-03-01T10:00:00Z",
    "updated_at": "2026-03-01T10:00:00Z"
  }
}
```

> - `attendance_required`・`min_view_role`・`visibility`・`attendance_status` 等の固定フィールドはレスポンスに含めない（個人スコープでは意味を持たないため）
> - リクエストボディにこれらが含まれていた場合は無視する（400 にはしない）

#### `GET /api/v1/me/schedules`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `from` | ISO 8601 date | 当月1日 | 開始日（inclusive）|
| `to` | ISO 8601 date | 当月末日 | 終了日（inclusive）|

> - `from`〜`to` の期間は最長 12ヶ月。超過する場合は 400 Bad Request を返す
> - 返却レコードは `start_at` 昇順

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
4. Google カレンダー同期（user_calendar_sync_settings で個人スコープの
   同期が有効な場合のみ @Async で実施）
5. audit_logs に PERSONAL_SCHEDULE_CREATED を記録
6. 201 Created を返す
```

### 繰り返し予定の展開

繰り返しルール（`recurrence_rule`）の展開ロジック・バッチ補完・Idempotency は `F05_schedule_shared.md` Section 5 の「繰り返しスケジュール自動展開バッチ」と同一のロジックを流用する。

### 完全非公開の保証

- `user_id IS NOT NULL` のスケジュールは、`user_id = current_user_id` の確認なしに API から取得・変更できない
- `GET /teams/{id}/schedules` 等のチーム・組織系エンドポイントのクエリには `user_id IS NULL` を必須条件として付与し、個人スコープのレコードが混入しないようにする
- `GET /my/calendar`（横断ビュー）のみ `user_id = current_user_id` のレコードを含めて返す（`F05_schedule_shared.md` Section 4 の当該エンドポイントを更新予定）

---

## 6. セキュリティ考慮事項

- **オーナーチェック必須**: 全エンドポイントで `schedules.user_id = current_user_id` を確認。スケジュール ID の連番推測による他人の個人予定へのアクセスを 403 で防止する（存在有無も返さない）
- **チーム/組織スコープへの混入防止**: チーム・組織系エンドポイントのクエリには `user_id IS NULL` を必須条件として付与する
- **固定フィールドの強制**: `attendance_required`・`min_view_role` 等を固定値に強制するアプリ層バリデーションを実施し、個人予定が出欠対象として扱われることを防ぐ
- **ADMIN も閲覧不可**: 個人予定は ADMIN であっても `user_id = current_user_id` でない限り 403 を返す。SYSTEM_ADMIN 向けの強制閲覧エンドポイントは設けない（プライバシーポリシー上の設計判断）

---

## 7. 未解決事項

- [ ] Google → アプリ への双方向同期（Google Calendar Webhook Push Notifications）の実装フェーズを確定する（Phase 3 同時か Phase 4+ か）
- [ ] `GET /my/calendar` のレスポンスに個人スケジュールを含める形への更新（`F05_schedule_shared.md` Section 4 を修正）
- [ ] 個人スケジュールを Google カレンダー同期する際、`user_calendar_sync_settings` に scope_type = 'PERSONAL' を追加するか確定する（現在 scope_type は TEAM / ORGANIZATION のみ）

---

## 8. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-01 | 初版作成: `schedules` テーブル再利用・完全非公開方針（オーナーチェックのみ）を採用。F05_schedule_attendance.md を F05_schedule_shared.md にリネームして分割 |
