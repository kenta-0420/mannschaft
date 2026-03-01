# F08: 外部連携（Google Calendar / iCal 個人同期）

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 3
> **最終更新**: 2026-03-01
> **関連ドキュメント**: スケジュール管理（共有）→ `F05_schedule_shared.md` / 個人スケジュール → `F05_schedule_personal.md`

---

## 1. 概要

外部カレンダーアプリとのスケジュール連携機能。以下2種類のアプローチを提供する。

| 方式 | 対象アプリ | 仕組み | 方向 |
|------|-----------|--------|------|
| **Google Calendar OAuth 同期** | Google カレンダー | OAuth 2.0 認可 → API でイベントを push | アプリ→Google（一方向）|
| **iCal 購読 URL** | Apple カレンダー、Google カレンダー、Outlook 等 | ユーザーが URL を登録 → 外部アプリが定期 pull | アプリ→外部（read-only）|

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
| `user_ical_tokens` | iCal 購読 URL 用シークレットトークン（1ユーザー1トークン）| users |

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

---

#### `user_ical_tokens`

iCal 購読 URL 用のシークレットトークンを管理するテーブル。1ユーザーにつき1つのトークンのみ有効。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `token` | VARCHAR(64) | NO | — | URL-safe なランダムトークン（32 バイト = 64 hex 文字、暗号学的乱数）|
| `is_active` | BOOLEAN | NO | TRUE | FALSE にすると URL が無効化される（再生成まで購読不可）|
| `last_polled_at` | DATETIME | YES | NULL | 外部カレンダーが最後に iCal URL をポーリングした日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_uit_user_id (user_id)   -- 1ユーザー1トークン保証
UNIQUE KEY uq_uit_token (token)       -- トークンからユーザーを逆引き
```

**制約・備考**
- トークンは `SecureRandom` 等の暗号学的乱数で生成する（`Math.random()` 禁止）
- トークン再生成時は同一レコードを UPDATE する（履歴は保持しない）
- ユーザー退会時は `ON DELETE CASCADE` で物理削除
- `last_polled_at` はポーリング検知・レート制限の補助情報として使用する

---

### ER図（テキスト形式）
```
users (1) ──── (1) user_google_calendar_connections
users (1) ──── (N) user_calendar_sync_settings
users (1) ──── (N) user_schedule_google_events
schedules (1) ──── (N) user_schedule_google_events
users (1) ──── (1) user_ical_tokens
```

---

## 4. API設計

### エンドポイント一覧

#### Google Calendar OAuth 同期
| メソッド | パス | 認証 | 説明 |
|---------|------|------|------|
| GET | `/api/v1/me/google-calendar/status` | 必要 | Google カレンダー連携状態確認 |
| POST | `/api/v1/me/google-calendar/connect` | 必要 | Google Calendar OAuth 連携（認可コード受取）|
| DELETE | `/api/v1/me/google-calendar/disconnect` | 必要 | Google Calendar 連携解除 |
| GET | `/api/v1/me/calendar-sync-settings` | 必要 | チーム・組織別の同期設定一覧 |
| PUT | `/api/v1/me/teams/{id}/calendar-sync` | 必要 | チームの Google カレンダー同期 ON/OFF |
| PUT | `/api/v1/me/organizations/{id}/calendar-sync` | 必要 | 組織の Google カレンダー同期 ON/OFF |

#### iCal 購読 URL
| メソッド | パス | 認証 | 説明 |
|---------|------|------|------|
| GET | `/api/v1/me/ical/token` | 必要 | iCal 購読 URL・トークン取得（未発行なら自動生成）|
| POST | `/api/v1/me/ical/token/regenerate` | 必要 | トークン再生成（旧 URL を即時無効化）|
| DELETE | `/api/v1/me/ical/token` | 必要 | トークン削除（購読 URL を永久無効化）|
| GET | `/ical/{token}.ics` | 不要（トークンが認証を兼ねる）| iCal ファイル配信（外部カレンダーがポーリング）|

> `/ical/{token}.ics` は `/api/v1/` プレフィックスなしの専用ルート。外部カレンダーアプリがヘッダーなしで直接アクセスするため。

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

---

### iCal 購読 URL API 仕様

#### `GET /api/v1/me/ical/token`

iCal 購読 URL を返す。トークン未発行の場合は自動生成して返す。

**レスポンス（200 OK）**
```json
{
  "data": {
    "token": "a3f8c2e1d4b7...",
    "ical_url": "https://app.mannschaft.example.com/ical/a3f8c2e1d4b7....ics",
    "webcal_url": "webcal://app.mannschaft.example.com/ical/a3f8c2e1d4b7....ics",
    "is_active": true,
    "last_polled_at": "2026-03-01T08:00:00Z"
  }
}
```

> - `webcal_url` は Apple カレンダーで「インターネットアカウントを追加」する際に使用するスキーム（ブラウザで踏むと Apple カレンダーが自動起動する）
> - `last_polled_at = null` はまだ外部カレンダーからのポーリングが来ていないことを示す

---

#### `POST /api/v1/me/ical/token/regenerate`

トークンを再生成する。旧トークンの URL は即時無効化される。

**レスポンス（200 OK）**: 新しいトークン情報（`GET /api/v1/me/ical/token` と同形式）

> 再生成後は外部カレンダーアプリ側でも新しい URL を再登録する必要がある

---

#### `DELETE /api/v1/me/ical/token`

トークンを削除する（購読 URL を永久無効化）。

**レスポンス（204 No Content）**

> 再び購読 URL が必要な場合は `GET /api/v1/me/ical/token` で新規発行する

---

#### `GET /ical/{token}.ics`

iCal 形式（RFC 5545 準拠）のスケジュールを返す。外部カレンダーアプリが定期的にポーリングする。

**レスポンスヘッダー**
```
Content-Type: text/calendar; charset=utf-8
Content-Disposition: attachment; filename="mannschaft.ics"
ETag: "{schedules の最終更新に基づくハッシュ}"
Last-Modified: "{最新スケジュールの updated_at}"
```

**レスポンスボディ例（iCalendar 形式）**
```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Mannschaft//Mannschaft//JA
CALNAME:Mannschaft スケジュール
X-WR-TIMEZONE:Asia/Tokyo
BEGIN:VTIMEZONE
TZID:Asia/Tokyo
...
END:VTIMEZONE
BEGIN:VEVENT
UID:schedule-10@mannschaft.app
SUMMARY:[FC Tokyo Youth] 火木練習
DTSTART;TZID=Asia/Tokyo:20260401T190000
DTEND;TZID=Asia/Tokyo:20260401T210000
LOCATION:駒沢公園
DESCRIPTION:
DTSTAMP:20260301T010000Z
LAST-MODIFIED:20260301T010000Z
STATUS:CONFIRMED
END:VEVENT
BEGIN:VEVENT
UID:schedule-500@mannschaft.app
SUMMARY:歯医者の予約
DTSTART;TZID=Asia/Tokyo:20260410T140000
DTEND;TZID=Asia/Tokyo:20260410T150000
LOCATION:〇〇歯科
DTSTAMP:20260301T010000Z
LAST-MODIFIED:20260301T010000Z
STATUS:CONFIRMED
END:VEVENT
END:VCALENDAR
```

**VEVENT フィールド定義**
| フィールド | 値 | 補足 |
|-----------|---|------|
| `UID` | `schedule-{id}@mannschaft.app` | スケジュール ID が一意性を保証 |
| `SUMMARY` | チーム/組織スケジュール: `[{scope_name}] {title}` / 個人スケジュール: `{title}` | |
| `DTSTART` / `DTEND` | `all_day = TRUE` の場合は DATE 形式（`DTSTART;VALUE=DATE:`）、否の場合は TZID 付き DATETIME | |
| `STATUS` | `status = CANCELLED` → `CANCELLED`、その他 → `CONFIRMED` | |
| `DESCRIPTION` | `description` フィールドの内容（NULL の場合は省略）| |
| `LOCATION` | `location` フィールドの内容（NULL の場合は省略）| |

**配信スコープ**
- 配信期間: 過去1ヶ月〜未来12ヶ月（未解決事項参照）
- 配信対象: `GET /my/calendar` と同じ横断ビュー（個人 + 参加中の全チーム/組織スケジュール）
- visibility フィルタ: ユーザーの現在ロールを動的に評価し `min_view_role` 条件を満たすスケジュールのみ配信

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 404 | トークンが存在しない / `is_active = FALSE` |
| 429 | レート制限超過（未解決事項参照）|

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

### iCal ファイル生成フロー

```
[GET /ical/{token}.ics 受付時]
1. {token} をキーに user_ical_tokens を検索
2. レコードが存在しない または is_active = FALSE → 404 を返す
3. レート制限チェック（last_polled_at との差分。詳細は未確定）
4. user_ical_tokens.last_polled_at を現在時刻に UPDATE
5. user_id からユーザーのロール情報を取得
6. GET /my/calendar と同一のクエリで schedules を取得（期間: 過去1ヶ月〜未来12ヶ月）
   - ロールに基づく min_view_role フィルタを適用（Google Calendar 同期と同一ロジック）
7. 条件付きリクエスト対応（ETag / If-None-Match チェック。一致すれば 304 を返す）
8. schedules を RFC 5545 形式に変換して VCALENDAR を生成
   - VEVENT の UID: schedule-{id}@mannschaft.app
   - SUMMARY: チーム/組織は "[{scope_name}] {title}"、個人は "{title}"
   - status = CANCELLED → STATUS:CANCELLED、それ以外 → STATUS:CONFIRMED
   - all_day = TRUE → DTSTART;VALUE=DATE:、FALSE → DTSTART;TZID=Asia/Tokyo:
9. Content-Type: text/calendar でレスポンスを返す

[トークン生成（GET /api/v1/me/ical/token で未発行時）]
1. SecureRandom で 32 バイトのランダム値を生成（hex エンコードで 64 文字）
2. user_ical_tokens に INSERT（user_id, token, is_active = TRUE）
3. iCal URL と webcal URL を組み立ててレスポンスを返す

[トークン再生成（POST /api/v1/me/ical/token/regenerate）]
1. 新しいトークン（32 バイト乱数）を生成
2. user_ical_tokens を UPDATE（token = 新トークン、last_polled_at = NULL、updated_at = 現在時刻）
3. 旧トークンは即時無効化（UPDATE により参照不可）
4. 新しい iCal URL をレスポンスとして返す
```

---

## 6. Flyway マイグレーション

```
V3.013__create_user_google_calendar_connections_table.sql
V3.014__create_user_calendar_sync_settings_table.sql
V3.015__create_user_schedule_google_events_table.sql
V3.017__add_personal_sync_to_calendar_connections.sql   -- user_google_calendar_connections.personal_sync_enabled カラム追加
V3.018__create_user_ical_tokens_table.sql
```

**マイグレーション上の注意点**
- V3.013 は V1.005（users）完了後に実行
- V3.014 は V1.005（users）完了後
- V3.015 は V1.005（users）および V3.007（schedules）完了後
- V3.017 は V3.013（user_google_calendar_connections）完了後
- V3.018 は V1.005（users）完了後

---

## 7. 未解決事項

- [ ] Google Calendar 同期エラー時の最大リトライ回数・最終失敗時のユーザー通知方法を確定する（現状: `user_schedule_google_events` 備考では「最大3回再試行」と仮定定義）
- [ ] Google Calendar 管理者レベル共有連携（`schedules.google_calendar_event_id` カラムの用途）は F09 で別途設計し、個人同期との役割分担を確定する
- [ ] **[iCal]** iCal 配信期間の範囲を確定する（現状: 過去1ヶ月〜未来12ヶ月と仮定定義。外部カレンダーは初回購読時に全期間を取得するため、過去の遡及範囲が多いと生成コストが上がる）
- [ ] **[iCal]** レート制限の実装方法を確定する（外部カレンダーアプリの過剰ポーリング対策。`last_polled_at` による DB チェック vs Redis カウンター vs Nginx 設定）
- [ ] **[iCal]** ETag / If-None-Match による条件付きリクエスト対応を実装するか確定する（実装するとポーリング時のDB負荷を大幅に削減できる）
- [ ] **[iCal]** `webcal://` スキームの対応方法を確定する（`https://` → `webcal://` のリダイレクト対応 vs フロントエンドで URL を両方表示するだけ）

---

## 8. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-01 | iCal 購読 URL 機能を追加: `user_ical_tokens` テーブル・トークン管理 API 3本・iCal ファイル配信エンドポイント（`GET /ical/{token}.ics`）・iCal 生成フロー・Flyway V3.018 を追加。配信スコープは横断ビュー（個人+全チーム/組織）の1URL固定、visibility はユーザーロールの動的評価 |
| 2026-03-01 | 初版作成: F05_schedule_shared.md から Google Calendar 個人同期関連を分離。テーブル定義・API 仕様・ビジネスロジック・Flyway マイグレーションを移管 |
