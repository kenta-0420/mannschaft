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
| `is_active` | BOOLEAN | NO | TRUE | 連携が有効かどうか（AUTH_ERROR または手動解除で FALSE）|
| `personal_sync_enabled` | BOOLEAN | NO | FALSE | 個人スケジュール（`schedules.user_id IS NOT NULL`）を Google カレンダーへ同期するか（アプリ→Google 一方向）。`is_active = FALSE` の場合は同期されない |
| `last_sync_error_type` | ENUM('AUTH_ERROR', 'QUOTA_EXCEEDED', 'NETWORK_ERROR', 'SERVER_ERROR') | YES | NULL | 直近の同期エラー種別。同期成功時に NULL にリセット |
| `last_sync_error_message` | TEXT | YES | NULL | ユーザー向けエラー詳細（設定画面の Google 連携セクションに表示）。同期成功時に NULL にリセット |
| `last_sync_error_at` | DATETIME | YES | NULL | 最後のエラー発生日時（全リトライ失敗後に記録）。同期成功時に NULL にリセット |
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
- 同期エラー（API エラー・トークン期限切れ等）が発生した場合は最大3回まで指数バックオフで再試行（1分後→10分後→1時間後）。詳細は Section 5「Google Calendar 同期エラーハンドリングフロー」参照

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
| POST | `/api/v1/me/google-calendar/sync` | 必要 | 手動再同期（未同期・失敗スケジュールを再プッシュ）|

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

#### `POST /api/v1/me/google-calendar/sync`

同期エラーで失敗したスケジュールを手動で再同期する。`is_active = TRUE` の場合のみ実行可能。

**レスポンス（202 Accepted）**
```json
{
  "data": {
    "backfill_count": 3,
    "message": "同期を開始しました。完了まで数分かかる場合があります。"
  }
}
```

> - `backfill_count`: 未同期スケジュール（`user_schedule_google_events` に未登録）のスケジュール数（@Async 開始前に事前算出）
> - 同期処理は @Async で非同期実行。成功後に `last_sync_error_*` をクリアする

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 422 | `is_active = FALSE`（AUTH_ERROR による連携解除済み。`/me/google-calendar/connect` で再連携が必要）|

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
    "subscribe_url": "https://app.mannschaft.example.com/ical/a3f8c2e1d4b7....ics?action=subscribe",
    "webcal_url": "webcal://app.mannschaft.example.com/ical/a3f8c2e1d4b7....ics",
    "is_active": true,
    "last_polled_at": "2026-03-01T08:00:00Z"
  }
}
```

> - `subscribe_url`: フロントエンドの「カレンダーに登録」ボタンのリンク先。バックエンドが `302 → webcal://` にリダイレクトし、OS/ブラウザが標準カレンダーアプリを自動起動する
> - `ical_url`: 外部カレンダーアプリの定期ポーリング先（`https://` のまま）。手動コピー用としても画面に表示する
> - `webcal_url`: 技術ユーザーがカレンダーアプリに直接貼り付ける用（`webcal://` スキーム）
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

**`?action=subscribe` クエリパラメータ（カレンダー登録ショートカット）**

クエリパラメータに `action=subscribe` が含まれる場合、iCal データは返さず即座に `webcal://` スキームへリダイレクトする。フロントエンドの「カレンダーに登録」ボタンはこの URL（`subscribe_url`）を使用する。

| 項目 | 値 |
|-----|---|
| ステータス | `302 Found` |
| `Location` | `webcal://{host}/ical/{token}.ics`（`?action=subscribe` は除去） |
| `Cache-Control` | `no-store`（ブラウザにリダイレクト先をキャッシュさせない） |
| レート制限 | **スキップ**（iCal 生成・DB クエリなし）|

> - iOS / macOS: `webcal://` を検知してカレンダー.app が自動起動し「購読しますか？」ダイアログを表示
> - Android / Windows: `webcal://` の自動起動が効かない場合のため、画面に `ical_url`（`https://`）も手動コピー用として併記する
> - 外部カレンダーアプリの定期ポーリングは `webcal://` ではなく `ical_url`（`https://`）に対して行われるため、通常の iCal 配信フローに影響しない

**レスポンスヘッダー（通常リクエスト）**
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
- **配信期間**: `start_at >= TODAY - 2ヶ月` かつ `start_at <= TODAY + 12ヶ月`
- **配信対象**: `GET /my/calendar` と同じ横断ビュー（個人 + 参加中の全チーム/組織スケジュール）
- **visibility フィルタ**: ユーザーの現在ロールを動的に評価し `min_view_role` 条件を満たすスケジュールのみ配信
- **件数上限**: 最大 500件。超過した場合は未来のスケジュールを優先して返す（詳細は Section 5 参照）
- **キャッシュ**: Redis TTL 15分（`ical:{token}` に iCal 文字列 + ETag を保存）。キャッシュヒット時は DB クエリ省略。TTL 自然失効で最大15分の更新ラグが発生する（許容範囲と判断）
- **将来の拡張**: 過去遡及範囲は Phase 4+ の「プロ設定」でユーザー選択式への拡張を検討。初期リリースでは固定

**レスポンスヘッダー（通常時）**
```
Content-Type: text/calendar; charset=utf-8
ETag: "{SHA256(MAX(updated_at) | COUNT(*)) の hex 64文字}"
Last-Modified: "{対象スケジュール群の最大 updated_at (RFC 7231 形式)}"
```

> - ETag はスケジュール群の最終更新日時と件数から計算（iCal 文字列の生成前に取得可能）
> - クライアントが `If-None-Match: "{ETag}"` を送信し一致した場合 → **304 Not Modified**（ボディなし・iCal 生成なし）
> - Apple Calendar・Google Calendar・Outlook はいずれも ETag / Last-Modified を尊重し、304 受信時はデータ転送を省略する

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 304 | クライアントのキャッシュが最新（`If-None-Match` が現在の ETag と一致）|
| 404 | トークンが存在しない / `is_active = FALSE` |
| 429 | レート制限超過（`Retry-After: 300` ヘッダーを返す）|

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

### Google Calendar 同期エラーハンドリングフロー

```
[エラー種別の判定と即時対応]
- 401 / 403（認証エラー）    → AUTH_ERROR: リトライなし。即座に失敗処理へ進む
- 429（クォータ超過）        → QUOTA_EXCEEDED: リトライ対象
- 5xx（Google サーバーエラー）→ SERVER_ERROR: リトライ対象
- タイムアウト・接続失敗      → NETWORK_ERROR: リトライ対象

[リトライ戦略（QUOTA_EXCEEDED / SERVER_ERROR / NETWORK_ERROR の場合）]
- Spring @Retryable を使用:
    maxAttempts = 4（初回1回 + 再試行3回）
    backoff = @Backoff(delay = 60_000, multiplier = 10.0, maxDelay = 3_600_000)
    → 試行タイミング: 即時 → 1分後 → 10分後 → 1時間後
- ※ @Async と @Retryable の同一メソッド適用は Spring AOP の制約により不可。
      リトライ対象の同期ロジックは別サービスクラスに切り出して @Retryable を付与する

[全リトライ失敗時 / AUTH_ERROR 時（@Recover または catch で処理）]
1. user_google_calendar_connections を UPDATE:
   - last_sync_error_type = '{エラー種別}'
   - last_sync_error_message = '{ユーザー向けメッセージ}'（例: "Google カレンダーとの同期に失敗しました。設定画面から再同期を試みてください"）
   - last_sync_error_at = NOW()
   - AUTH_ERROR の場合のみ: is_active = FALSE
2. アプリ内通知を送信（通知システムの設計は別途 Feature Doc で定義）
   - 通知内容: "{チーム名} のスケジュールが Google カレンダーに同期できませんでした"
   - AUTH_ERROR の場合: "Google カレンダーの連携が切れました。再連携してください"

[同期成功時]
- last_sync_error_type / last_sync_error_message / last_sync_error_at を NULL にリセット
  （設定画面のエラー表示をクリアするため）

[手動再同期（POST /api/v1/me/google-calendar/sync）]
1. is_active = FALSE なら 422 を返す（AUTH_ERROR のため再連携が先決）
2. 未同期スケジュールを検出（backfill ロジックと同一: schedules と user_schedule_google_events を比較）
3. backfill_count を返す（202 Accepted）
4. @Async で未同期スケジュールを Google Calendar に再プッシュ
   - リトライ戦略（上記と同様）で実行
   - 成功後: last_sync_error_* を NULL にリセット
   - 失敗後: last_sync_error_* を更新
```

---

### iCal ファイル生成フロー

```
[GET /ical/{token}.ics?action=subscribe 受付時（webcal:// リダイレクト）]
1. {token} をキーに user_ical_tokens を検索
   - レコードが存在しない または is_active = FALSE → 404 を返す
2. 302 Found を返す:
   - Location: webcal://{host}/ical/{token}.ics（?action=subscribe は除去）
   - Cache-Control: no-store
   ※ レート制限チェック・iCal 生成・DB 更新は一切行わない

[GET /ical/{token}.ics 受付時（通常 iCal 配信）]
1. {token} をキーに user_ical_tokens を検索
2. レコードが存在しない または is_active = FALSE → 404 を返す
3. Redis レート制限チェック（key: `ical_ratelimit:{token}`、TTL: 5分）
   - key が存在する → 429 を返す（`Retry-After: 300` ヘッダー付き）
   - key が存在しない → SET（TTL 5分）して step 4 へ進む
   ※ Nginx `limit_req_zone` による IP 単位のバースト保護（後述）と二層構成
4. Redis キャッシュ確認（key: `ical:{token}`）→ {icalString, etag} を取得
   - HIT:
     a. `If-None-Match` ヘッダーが stored etag と一致 → 304 Not Modified を返す（iCal 生成なし・DB クエリなし）
     b. 一致しない または `If-None-Match` なし → cached iCal 文字列を返す（step 11 へ）
   - MISS: step 5 へ進む
5. user_id からユーザーのロール情報を取得
6. 軽量メタデータクエリ（ETag 早期算出・iCal 生成前に実行）:
   SELECT MAX(s.updated_at), COUNT(*) FROM schedules s WHERE [visibility filter + date range]
   → ETag = SHA256("{max_updated_at}|{count}") を計算
   → `If-None-Match` ヘッダーが一致する場合 → 304 Not Modified を返す（iCal 生成スキップ）
7. user_ical_tokens.last_polled_at を現在時刻に UPDATE（実際に iCal 生成が必要な場合のみ）
8. スケジュールを2段階クエリで取得（min_view_role フィルタを適用）:
   a. 未来スケジュール: start_at >= TODAY かつ start_at <= TODAY + 12ヶ月
      ORDER BY start_at ASC  LIMIT 500
   b. 過去スケジュール: start_at >= TODAY - 2ヶ月 かつ start_at < TODAY
      ORDER BY start_at DESC  LIMIT MAX(0, 500 - 手順aの件数)
   c. a + b をマージして start_at ASC で再ソート（合計最大 500件）
9. schedules を RFC 5545 形式に変換して VCALENDAR を生成
   - VEVENT の UID: schedule-{id}@mannschaft.app
   - SUMMARY: チーム/組織は "[{scope_name}] {title}"、個人は "{title}"
   - status = CANCELLED → STATUS:CANCELLED、それ以外 → STATUS:CONFIRMED
   - all_day = TRUE → DTSTART;VALUE=DATE:、FALSE → DTSTART;TZID=Asia/Tokyo:
10. {icalString, etag} を Redis にキャッシュ（key: `ical:{token}`、TTL: 15分）
11. ETag・Last-Modified ヘッダーを付与し、Content-Type: text/calendar; charset=utf-8 で返す

[トークン再生成・削除時のキャッシュ処理]
- POST /api/v1/me/ical/token/regenerate:
  旧トークンの Redis キャッシュを明示削除（`DEL ical:{旧token}`、`DEL ical_ratelimit:{旧token}`）
- DELETE /api/v1/me/ical/token: 同様に両キーを削除

[トークン生成（GET /api/v1/me/ical/token で未発行時）]
1. SecureRandom で 32 バイトのランダム値を生成（hex エンコードで 64 文字）
2. user_ical_tokens に INSERT（user_id, token, is_active = TRUE）
3. ical_url・subscribe_url・webcal_url を組み立ててレスポンスを返す
   - ical_url     = https://{host}/ical/{token}.ics
   - subscribe_url = https://{host}/ical/{token}.ics?action=subscribe
   - webcal_url   = webcal://{host}/ical/{token}.ics

[トークン再生成（POST /api/v1/me/ical/token/regenerate）]
1. 新しいトークン（32 バイト乱数）を生成
2. user_ical_tokens を UPDATE（token = 新トークン、last_polled_at = NULL、updated_at = 現在時刻）
3. 旧トークンは即時無効化（UPDATE により参照不可）
4. 新しい iCal URL をレスポンスとして返す
```

---

### iCal レート制限・通信最適化

```
[レート制限の二層構成]

層1 - Nginx（IP 単位のバースト保護）:
  limit_req_zone $binary_remote_addr zone=ical:10m rate=30r/m;
  → 1 IP あたり 30 リクエスト/分（同一 NAT IP の複数ユーザーを考慮した緩い上限）
  → バースト攻撃・クローラーをアプリ到達前に遮断

層2 - Redis（トークン単位の持続レート制限）:
  key:   ical_ratelimit:{token}
  TTL:   300秒（5分）
  操作:  EXISTS → 429 の場合はここで返す / 存在しない場合は SET EX 300 して続行
  → Apple Calendar（15分間隔）/ Google Calendar・Outlook（1時間以上）の標準ポーリングに対し
     1回/5分は十分な余裕を持つ設定
  → 同一トークンからの過剰ポーリング（バグ・手動リロード連打等）を個別に制御

[ETag によるデータ転送最適化]
  ETag 値: SHA256("{MAX(visible_schedules.updated_at)}|{COUNT(visible_schedules)}") → hex 64文字
  算出タイミング: iCal 文字列の生成前（軽量 SELECT クエリ1本で取得可能）
  保存先:  Redis（`ical:{token}` に iCal 文字列と etag をペアで保存）
  動作:
    ① キャッシュ HIT 時: stored etag と If-None-Match を比較 → 一致で 304（DB クエリなし）
    ② キャッシュ MISS 時: 軽量クエリで etag を算出 → 一致で 304（iCal 生成なし）
                          → 不一致の場合のみ iCal を生成してキャッシュに保存
  効果:
    - スケジュールが更新されない限り、ポーリングごとの iCal 生成・データ転送量ゼロ
    - キャッシュ期限切れ後も iCal 生成をスキップできる（従来は生成後に比較していた）
    - Apple Calendar は ETag を正しく処理することを確認済み（標準動作）

[標準的なカレンダーアプリとの対応表]
  アプリ            | 標準ポーリング間隔 | レート制限（5分/回）| ETag 対応 |
  ------------------|-------------------|---------------------|-----------|
  Apple Calendar    | 15分              | ◎ 問題なし           | ○        |
  Google Calendar   | 1〜24時間         | ◎ 問題なし           | ○        |
  Outlook           | 1〜3時間          | ◎ 問題なし           | ○        |
  Thunderbird       | 30分〜1時間       | ◎ 問題なし           | ○        |
```

---

## 6. Flyway マイグレーション

```
V3.013__create_user_google_calendar_connections_table.sql
V3.014__create_user_calendar_sync_settings_table.sql
V3.015__create_user_schedule_google_events_table.sql
V3.017__add_personal_sync_to_calendar_connections.sql   -- user_google_calendar_connections.personal_sync_enabled カラム追加
V3.018__create_user_ical_tokens_table.sql
V3.019__add_sync_error_columns_to_calendar_connections.sql
  -- user_google_calendar_connections に追加:
  --   last_sync_error_type ENUM('AUTH_ERROR','QUOTA_EXCEEDED','NETWORK_ERROR','SERVER_ERROR') NULL
  --   last_sync_error_message TEXT NULL
  --   last_sync_error_at DATETIME NULL
```

**マイグレーション上の注意点**
- V3.013 は V1.005（users）完了後に実行
- V3.014 は V1.005（users）完了後
- V3.015 は V1.005（users）および V3.007（schedules）完了後
- V3.017 は V3.013（user_google_calendar_connections）完了後
- V3.018 は V1.005（users）完了後
- V3.019 は V3.013（user_google_calendar_connections）完了後

---

## 7. 未解決事項

- [x] Google Calendar 同期エラー時の最大リトライ回数・最終失敗時のユーザー通知方法を確定する: リトライ3回（指数バックオフ 1分→10分→1時間）。AUTH_ERROR のみ即時停止・`is_active = FALSE`。全失敗時に `user_google_calendar_connections.last_sync_error_*` を記録してアプリ内通知。`POST /me/google-calendar/sync` で手動再同期可能（Section 4・Section 5・Section 6 参照）
- [ ] Google Calendar 管理者レベル共有連携（`schedules.google_calendar_event_id` カラムの用途）は F09 で別途設計し、個人同期との役割分担を確定する
- [x] **[iCal]** iCal 配信期間の範囲を確定する: 過去2ヶ月〜未来12ヶ月・最大500件（未来優先の2段階クエリ）・Redis TTL 15分キャッシュ（Section 4・Section 5 参照）
- [x] **[iCal]** レート制限の実装方法を確定する: Nginx（IP 単位・30req/分）+ Redis（トークン単位・1req/5分、key: `ical_ratelimit:{token}` TTL 300秒）の二層構成。Apple/Google/Outlook の標準ポーリング間隔は全て許容範囲（Section 5 参照）
- [x] **[iCal]** ETag / If-None-Match による条件付きリクエスト対応: SHA256(iCal 文字列) を ETag として Redis キャッシュと共に保存。ETag 一致時は 304 返却でデータ転送量ゼロ（Section 4・Section 5 参照）
- [x] **[iCal]** `webcal://` スキームの対応方法を確定する: `GET /ical/{token}.ics?action=subscribe` を新設し `302 Location: webcal://` にリダイレクト（iCal 生成・レート制限なし）。フロントの「カレンダーに登録」ボタンは `subscribe_url` を使用。`ical_url`（`https://`）も手動コピー用として画面に併記（Android など自動起動非対応環境のフォールバック）。API レスポンスに `subscribe_url` フィールドを追加（Section 4・Section 5 参照）

---

## 8. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-03 | iCal `webcal://` スキーム対応を確定: `GET /ical/{token}.ics?action=subscribe` を新設し `302 Location: webcal://` にリダイレクト（レート制限・iCal 生成なし）。API レスポンスに `subscribe_url` を追加。フォールバック用に `ical_url`（`https://`）を画面に手動コピー用として併記（Android 等向け） |
| 2026-03-01 | iCal レート制限・通信最適化を確定: Nginx IP 単位（30req/分）+ Redis トークン単位（1req/5分・`ical_ratelimit:{token}` TTL 300秒）の二層レート制限。ETag = SHA256(iCal 文字列) を Redis キャッシュに併記し `If-None-Match` 一致時に 304 返却。`last_polled_at` 更新をキャッシュ MISS 時のみに限定してDB負荷削減。主要カレンダーアプリ4種との対応表を追記 |
| 2026-03-01 | iCal 配信期間・パフォーマンス仕様を確定: 過去2ヶ月〜未来12ヶ月・最大500件（未来優先の2段階クエリでマージ）・Redis TTL 15分キャッシュ（`ical:{token}` キー）・トークン再生成/削除時の明示的キャッシュ削除・Phase 4+ 拡張（プロ設定）を明記 |
| 2026-03-01 | Google Calendar 同期エラーハンドリングを確定: リトライ3回（指数バックオフ 1min→10min→1hr）・AUTH_ERROR は即時停止・`user_google_calendar_connections` にエラー追跡カラム 3 つ追加（Flyway V3.019）・手動再同期 `POST /me/google-calendar/sync` を追加・Spring @Retryable + @Recover の実装方針を明記 |
| 2026-03-01 | iCal 購読 URL 機能を追加: `user_ical_tokens` テーブル・トークン管理 API 3本・iCal ファイル配信エンドポイント（`GET /ical/{token}.ics`）・iCal 生成フロー・Flyway V3.018 を追加。配信スコープは横断ビュー（個人+全チーム/組織）の1URL固定、visibility はユーザーロールの動的評価 |
| 2026-03-01 | 初版作成: F05_schedule_shared.md から Google Calendar 個人同期関連を分離。テーブル定義・API 仕様・ビジネスロジック・Flyway マイグレーションを移管 |
