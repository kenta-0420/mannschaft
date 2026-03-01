# F08: 外部連携（Google Calendar 個人同期）

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 3
> **最終更新**: 2026-03-01
> **関連ドキュメント**: スケジュール管理（共有）→ `F05_schedule_shared.md` / 個人スケジュール → `F05_schedule_personal.md`

---

## 1. 概要

ユーザーが個人 Google カレンダーへのスケジュール自動同期を設定・管理する機能。チーム・組織のスケジュールおよび個人スケジュール（`F05_schedule_personal.md`）を、ユーザーが指定した Google カレンダーに自動反映する（アプリ→Google 一方向）。

Google→アプリ の双方向同期（Google Calendar Webhook Push Notifications）は Phase 4+ で実装予定。

> 管理者レベルの Google カレンダー共有連携（チーム・組織のカレンダーを共有公開する機能）は F09 で別途設計する。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| 本人（任意ロール） | 自分の Google Calendar 連携設定・同期 ON/OFF |
| 他者・ADMIN | アクセス不可（個人設定のため）|

### 対象レベル
- [x] 組織 (Organization)（スケジュールの同期対象として）
- [x] チーム (Team)（スケジュールの同期対象として）
- [x] 個人 (Personal)（個人スケジュールの同期設定として）

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 依存 |
|-----------|------|------|
| `user_google_calendar_connections` | ユーザーの Google Calendar OAuth 連携情報 | users |
| `user_calendar_sync_settings` | チーム・組織別の Google カレンダー同期設定 | users |
| `user_schedule_google_events` | スケジュール↔Google Calendar イベント ID マッピング | users, schedules |

### テーブル定義

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
| `personal_sync_enabled` | BOOLEAN | NO | FALSE | 個人スケジュール（`schedules.user_id IS NOT NULL`）を Google カレンダーへ同期するか（アプリ→Google 一方向）。`is_active = FALSE` の場合は同期されない |
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
- 個人スケジュールの同期設定は `user_calendar_sync_settings` ではなく本カラム（`personal_sync_enabled`）で管理する。チーム・組織は scope_id が必要だが個人スコープに scope_id は存在しないため

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
- 同期エラー（API エラー・トークン期限切れ等）が発生した場合はバッチで最大3回再試行（上限回数は未確定・Section 7 参照）

---

### ER図（テキスト形式）
```
users (1) ──── (1) user_google_calendar_connections
users (1) ──── (N) user_calendar_sync_settings
users (1) ──── (N) user_schedule_google_events
schedules (1) ──── (N) user_schedule_google_events
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|------|------|------|
| GET | `/api/v1/me/google-calendar/status` | 必要 | Google カレンダー連携状態確認 |
| POST | `/api/v1/me/google-calendar/connect` | 必要 | Google Calendar OAuth 連携（認可コード受取）|
| DELETE | `/api/v1/me/google-calendar/disconnect` | 必要 | Google Calendar 連携解除 |
| GET | `/api/v1/me/calendar-sync-settings` | 必要 | チーム・組織別の同期設定一覧 |
| PUT | `/api/v1/me/teams/{id}/calendar-sync` | 必要 | チームの Google カレンダー同期 ON/OFF |
| PUT | `/api/v1/me/organizations/{id}/calendar-sync` | 必要 | 組織の Google カレンダー同期 ON/OFF |

### リクエスト／レスポンス仕様

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

#### `DELETE /api/v1/me/google-calendar/disconnect`

Google Calendar 連携を解除する。Google 側での認可取り消し（revoke）も実施する。解除後は全チーム・組織の同期が停止する（`user_google_calendar_connections.is_active = FALSE`）。

**レスポンス（204 No Content）**

> - 再連携は `POST /me/google-calendar/connect` から再実施
> - `user_calendar_sync_settings` のレコードは削除しない（再連携時に設定を復元するため）

---

#### `GET /api/v1/me/calendar-sync-settings`

ユーザーが所属する全チーム・組織の Google カレンダー同期設定一覧を返す。

**レスポンス（200 OK）**
```json
{
  "data": {
    "is_connected": true,
    "google_account_email": "user@gmail.com",
    "sync_settings": [
      {
        "scope_type": "TEAM",
        "scope_id": 1,
        "scope_name": "FC Tokyo Youth",
        "is_enabled": true
      },
      {
        "scope_type": "ORGANIZATION",
        "scope_id": 3,
        "scope_name": "東京FC",
        "is_enabled": false
      }
    ]
  }
}
```

> - `is_connected = false` の場合、`sync_settings` の `is_enabled` は全て `false` として返す
> - 退会済みチーム・組織の設定は含まない（所属中のみ）

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

#### `PUT /api/v1/me/organizations/{id}/calendar-sync`

組織の Google カレンダー同期を ON/OFF 切り替える。初回 ON 時に今日以降の未同期スケジュールを一括同期する。

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
    "organization_id": 3,
    "is_enabled": true,
    "backfill_count": 5
  }
}
```

> `backfill_count`: 初回 ON 時に遡り同期したスケジュール件数。既に同期済みまたは OFF 切替時は 0

エラーレスポンスはチーム版（`PUT /me/teams/{id}/calendar-sync`）と同様。

---

## 5. ビジネスロジック

### Google カレンダー個人同期フロー

```
[連携開始]
1. POST /api/v1/me/google-calendar/connect を受付
2. Google OAuth 2.0 認可コードを Google Token Endpoint に送信
3. アクセストークン・リフレッシュトークンを取得
4. 両トークンを AES-256-GCM で暗号化して user_google_calendar_connections に UPSERT
5. 200 OK を返す

[同期有効化]
1. PUT /api/v1/me/teams/{id}/calendar-sync または PUT /api/v1/me/organizations/{id}/calendar-sync
   （is_enabled: true）を受付（以降のフローは両スコープ共通。scope_type が TEAM / ORGANIZATION で異なるのみ）
2. user_google_calendar_connections.is_active = TRUE か確認（未連携は 422）
3. user_calendar_sync_settings を UPSERT（is_enabled = true）
4. バックフィル対象件数を事前に取得（@Async 開始前・同期処理とは別に実施）:
   a. 当該チームの schedules（start_at >= NOW()、deleted_at IS NULL）を取得
   b. user_schedule_google_events に未登録のスケジュールを抽出し、件数を backfill_count として保持
5. 200 OK を返す（backfill_count を含む; バックフィル処理は @Async で非同期継続）
6. @Async: 手順 4-b の未登録スケジュールを Google Calendar にイベント作成
   → 成功したものを user_schedule_google_events に INSERT

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

## 6. Flyway マイグレーション

```
V3.013__create_user_google_calendar_connections_table.sql
V3.014__create_user_calendar_sync_settings_table.sql
V3.015__create_user_schedule_google_events_table.sql
V3.017__add_personal_sync_to_calendar_connections.sql   -- user_google_calendar_connections.personal_sync_enabled カラム追加
```

**マイグレーション上の注意点**
- V3.013 は V1.005（users）完了後に実行
- V3.014 は V1.005（users）完了後
- V3.015 は V1.005（users）および V3.007（schedules）完了後
- V3.017 は V3.013（user_google_calendar_connections）完了後

---

## 7. 未解決事項

- [ ] Google Calendar 同期エラー時の最大リトライ回数・最終失敗時のユーザー通知方法を確定する（現状: `user_schedule_google_events` 備考では「最大3回再試行」と仮定定義）
- [ ] Google Calendar 管理者レベル共有連携（`schedules.google_calendar_event_id` カラムの用途）は F09 で別途設計し、個人同期との役割分担を確定する

---

## 8. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-01 | 初版作成: F05_schedule_shared.md から Google Calendar 個人同期関連を分離。テーブル定義・API 仕様・ビジネスロジック・Flyway マイグレーションを移管 |
