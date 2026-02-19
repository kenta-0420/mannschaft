# F01: 認証・ユーザー管理

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 1
> **最終更新**: 2026-02-19

---

## 1. 概要

プラットフォーム全体の入口となる認証基盤。メールアドレス＋パスワードによる基本認証に加え、
ソーシャルログイン（Google / LINE / Apple）・2要素認証（TOTP）・JWT によるステートレス認証を提供する。
退会・凍結・非アクティブアーカイブなどのユーザーライフサイクル管理もここで担う。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全ユーザーの凍結・強制退会・ロール付与 |
| ADMIN | 担当チーム/組織のメンバー招待・ロール変更 |
| DEPUTY_ADMIN | ADMIN が許可した範囲のみ |
| MEMBER | 自分のプロフィール編集・退会申請 |
| SUPPORTER | 自分のプロフィール編集・退会申請 |
| GUEST | 閲覧のみ（認証後） |

### 対象レベル
- [ ] 組織 (Organization) — ロール付与のスコープとして関与
- [ ] チーム (Team) — ロール付与のスコープとして関与
- [x] 個人 (Personal) — 主体

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `users` | ユーザーマスター | あり（退会猶予期間管理） |
| `refresh_tokens` | Refresh Token 管理・デバイス紐付け | なし（revoked_at で無効化） |
| `oauth_accounts` | ソーシャルログイン連携 | なし |
| `two_factor_auth` | TOTP 秘密鍵・バックアップコード | なし |
| `password_reset_tokens` | パスワードリセットトークン | なし |

### テーブル定義

#### `users`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `email` | VARCHAR(255) | NO | — | UNIQUE。ログインID |
| `password_hash` | VARCHAR(255) | YES | NULL | bcrypt。OAuthのみの場合はNULL |
| `last_name` | VARCHAR(50) | NO | — | 姓 |
| `first_name` | VARCHAR(50) | NO | — | 名 |
| `last_name_kana` | VARCHAR(50) | YES | NULL | 姓（カナ）|
| `first_name_kana` | VARCHAR(50) | YES | NULL | 名（カナ）|
| `avatar_url` | VARCHAR(500) | YES | NULL | S3 Pre-signed URL ではなくオブジェクトキーを保存 |
| `phone_number` | VARCHAR(20) | YES | NULL | 任意。将来のSMS認証用 |
| `status` | ENUM | NO | `ACTIVE` | `ACTIVE` / `FROZEN` / `ARCHIVED` |
| `last_login_at` | DATETIME | YES | NULL | アーカイブ判定・非アクティブ検出に使用 |
| `archived_at` | DATETIME | YES | NULL | 非アクティブアーカイブ日時 |
| `deleted_at` | DATETIME | YES | NULL | 論理削除（退会申請日）。30日後に個人情報を物理削除 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_users_email (email)
INDEX idx_users_status_last_login (status, last_login_at)  -- 非アクティブアーカイブバッチ用
INDEX idx_users_deleted_at (deleted_at)                    -- 退会後30日の物理削除バッチ用
```

**備考**
- `avatar_url` はオブジェクトキー（例: `avatars/user_123.webp`）を保存し、表示時に Pre-signed URL を生成する
- 電子印鑑の SVG は `users` には持たず、`user_seals` テーブルに分離する（F01 スコープ外）
- `status=FROZEN` は SYSTEM_ADMIN による凍結。ログイン不可・既存セッション即時無効化

---

#### `refresh_tokens`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `token_hash` | VARCHAR(64) | NO | — | SHA-256 ハッシュ。トークン本体は返却のみ、DBには保存しない |
| `device_fingerprint` | VARCHAR(64) | YES | NULL | IP + User-Agent の SHA-256 ハッシュ（デバイスバインディング用） |
| `ip_address` | VARCHAR(45) | YES | NULL | 発行時のIPアドレス（IPv6対応） |
| `user_agent` | VARCHAR(500) | YES | NULL | 発行時のUser-Agent |
| `expires_at` | DATETIME | NO | — | 発行から7日後 |
| `revoked_at` | DATETIME | YES | NULL | ローテーション・ログアウト時に設定 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_refresh_tokens_hash (token_hash)
INDEX idx_refresh_tokens_user_id (user_id)         -- ユーザーの全トークン取得（ログアウト全デバイス等）
INDEX idx_refresh_tokens_expires_at (expires_at)   -- 期限切れトークン削除バッチ用
```

**備考**
- Refresh Token のローテーション時は旧トークンに `revoked_at` を設定し、新トークンを INSERT する
- ログアウト時: 該当トークンに `revoked_at` を設定 ＋ Access Token を Redis ブラックリストへ追加
- 1ユーザーが複数デバイスでログイン可能（行数に上限は設けない。期限切れ行はバッチで削除）

---

#### `oauth_accounts`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `provider` | ENUM | NO | — | `GOOGLE` / `LINE` / `APPLE` |
| `provider_user_id` | VARCHAR(255) | NO | — | プロバイダー側のユーザーID |
| `provider_email` | VARCHAR(255) | YES | NULL | プロバイダーから取得したメールアドレス（参考値） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_oauth_provider_uid (provider, provider_user_id)
INDEX idx_oauth_user_id (user_id)
```

**備考**
- 1ユーザーが複数プロバイダーを連携可能（LINE と Google を両方連携する等）
- 既存メールアドレスの `users` レコードと `provider_email` が一致する場合は自動連携を行う

---

#### `two_factor_auth`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users。UNIQUE（1ユーザー1レコード） |
| `totp_secret` | VARCHAR(255) | NO | — | AES-256 暗号化済み TOTP 秘密鍵 |
| `backup_codes` | JSON | NO | — | ハッシュ済みバックアップコード 8件の配列 |
| `is_enabled` | BOOLEAN | NO | false | true になるのは初回 TOTP 認証成功後 |
| `verified_at` | DATETIME | YES | NULL | 初回認証成功日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_2fa_user_id (user_id)
```

**備考**
- `totp_secret` の暗号化キーは環境変数で管理（`AES_ENCRYPTION_KEY`）
- バックアップコードは使用済みになったら JSON 配列から該当エントリを削除する
- SYSTEM_ADMIN・ADMIN は 2FA 有効化を必須とする。未設定のまま管理操作を行おうとした場合は 2FA 設定画面へリダイレクト

---

#### `password_reset_tokens`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `token_hash` | VARCHAR(64) | NO | — | SHA-256 ハッシュ |
| `expires_at` | DATETIME | NO | — | 発行から30分後 |
| `used_at` | DATETIME | YES | NULL | 使用済みフラグ代わり |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_prt_token_hash (token_hash)
INDEX idx_prt_user_id (user_id)
INDEX idx_prt_expires_at (expires_at)  -- 期限切れトークン削除バッチ用
```

---

### ER図（テキスト形式）
```
users (1) ──── (N) refresh_tokens
users (1) ──── (N) oauth_accounts
users (1) ──── (0..1) two_factor_auth
users (1) ──── (N) password_reset_tokens
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| POST | `/api/v1/auth/register` | 不要 | ユーザー登録 |
| POST | `/api/v1/auth/login` | 不要 | メール＋パスワードログイン |
| POST | `/api/v1/auth/logout` | 必要 | ログアウト（トークン無効化） |
| POST | `/api/v1/auth/refresh` | 不要（Refresh Token） | Access Token 再発行 |
| POST | `/api/v1/auth/oauth/{provider}` | 不要 | ソーシャルログイン（Google/LINE/Apple） |
| POST | `/api/v1/auth/password-reset/request` | 不要 | パスワードリセットメール送信 |
| POST | `/api/v1/auth/password-reset/confirm` | 不要 | パスワード再設定 |
| POST | `/api/v1/auth/2fa/setup` | 必要 | TOTP セットアップ（秘密鍵・QRコード取得） |
| POST | `/api/v1/auth/2fa/verify` | 必要 | TOTP 初回認証（有効化） |
| POST | `/api/v1/auth/2fa/validate` | 必要 | ログイン後の TOTP コード検証 |
| GET | `/api/v1/users/me` | 必要 | 自分のプロフィール取得 |
| PUT | `/api/v1/users/me` | 必要 | 自分のプロフィール更新 |
| DELETE | `/api/v1/users/me` | 必要 | 退会申請（論理削除・30日猶予） |
| POST | `/api/v1/users/me/avatar` | 必要 | アバター画像アップロード用 Pre-signed URL 取得 |

### リクエスト／レスポンス仕様

#### `POST /api/v1/auth/login`

**リクエストボディ**
```json
{
  "email": "user@example.com",
  "password": "P@ssw0rd!"
}
```

**レスポンス（200 OK）— 2FA 未設定または無効のユーザー**
```json
{
  "data": {
    "access_token": "eyJ...",
    "token_type": "Bearer",
    "expires_in": 900
  }
}
```
※ Refresh Token は `Set-Cookie` ではなくレスポンスボディで返す（Cookie 格納禁止のため）

**レスポンス（200 OK）— 2FA 必要なユーザー**
```json
{
  "data": {
    "mfa_required": true,
    "mfa_session_token": "tmp_xxxx"
  }
}
```
※ `mfa_session_token` は短命（5分）の一時トークン。TOTP 検証後に本 Access Token を発行する

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 401 | メールアドレスまたはパスワード不一致 |
| 423 | アカウントロック中（5回失敗で30分ロック） |
| 429 | レートリミット超過 |

---

#### `POST /api/v1/auth/refresh`

**リクエストボディ**
```json
{
  "refresh_token": "eyJ..."
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "expires_in": 900
  }
}
```
※ Refresh Token もローテーションして新しいものを返す。旧トークンは即時無効化

---

## 5. ビジネスロジック

### ログインフロー
```
1. メール＋パスワードで認証
2. bcrypt 検証（失敗 → ログイン試行カウンタをRedisでインクリメント）
3. 5回失敗 → アカウントを30分ロック（Redisで管理）
4. 認証成功 → 2FA が有効かチェック
   4a. 2FA 無効: Access Token + Refresh Token を即時発行
   4b. 2FA 有効: mfa_session_token を発行 → /auth/2fa/validate へ
5. Refresh Token を DB に保存・Access Token を返却
6. last_login_at を更新（archived_at があればクリアし、アーカイブを解除）
```

### Refresh Token ローテーション
```
1. Refresh Token を受け取り token_hash で DB を検索
2. revoked_at が設定済み → 不正使用の疑い → 同一ユーザーの全トークンを失効させログアウト
3. デバイスバインディング検証（device_fingerprint の不一致 → 警告ログ + 要再認証）
4. 新 Access Token + 新 Refresh Token を発行
5. 旧 Refresh Token に revoked_at を設定
```

### 退会フロー
```
1. DELETE /users/me を受け付け deleted_at を現在日時に設定（論理削除）
2. 全 Refresh Token を失効、Access Token を Redis ブラックリストへ
3. 30日間は猶予期間（ログイン可能・キャンセル可能）
4. 30日経過後バッチが起動:
   - 個人情報カラム（email/名前/電話番号/avatar_url）を NULL またはダミー値へ上書き
   - oauth_accounts・two_factor_auth・password_reset_tokens を物理削除
   - payment_records 等の決済履歴は tax_law に基づき7年間保持
```

### アカウント凍結
```
- SYSTEM_ADMIN が PATCH /system-admin/users/{id}/freeze を実行
- users.status = FROZEN に更新
- 当該ユーザーの全 Refresh Token を失効
- Access Token を Redis ブラックリストへ追加（有効期限内の強制ログアウト）
```

---

## 6. セキュリティ考慮事項

- **パスワードハッシュ**: bcrypt、コストファクター **12**（サーバー性能に応じて調整）
- **パスワードポリシー**: 8文字以上・英大文字/小文字/数字/記号のうち3種以上・ユーザーIDと同一禁止
- **レートリミット**:
  - `POST /auth/login`: 同一IPで1分間10回・5回失敗でアカウント30分ロック
  - `POST /auth/password-reset/request`: 同一IPで1分間3回（メール送信の悪用防止）
  - `POST /auth/2fa/validate`: 同一IPで1分間10回
- **JWT**:
  - Access Token: 15分・署名アルゴリズム RS256（公開鍵/秘密鍵ペア）
  - Refresh Token: 7日・DB 管理でいつでも失効可能
  - トークン本体は DB に保存しない。DB には SHA-256 ハッシュのみ保存
- **TOTP 秘密鍵**: AES-256 で暗号化して保存（暗号化キーは `AES_ENCRYPTION_KEY` 環境変数）
- **デバイスバインディング**: Refresh Token 発行時の IP + User-Agent を記録。次回使用時に不一致なら警告ログ + 再認証要求（推奨）
- **認可チェック**: 全 API の入り口で `currentUser.id` とリクエストの対象リソースの所有者を検証

---

## 7. Flywayマイグレーション

```
V1.001__create_users_table.sql
V1.002__create_refresh_tokens_table.sql
V1.003__create_oauth_accounts_table.sql
V1.004__create_two_factor_auth_table.sql
V1.005__create_password_reset_tokens_table.sql
V1.006__seed_system_admin_user.sql          -- 初期 SYSTEM_ADMIN アカウント（ApplicationRunner 経由でパスワードハッシュ化）
```

**マイグレーション上の注意点**
- `V1.006` の SYSTEM_ADMIN 作成は Flyway SQL ではなく `ApplicationRunner` で実装し、パスワードのハッシュ化を Spring 側で行う（SQL に平文パスワードを書かない）

---

## 8. 未解決事項

- [ ] **メールアドレス確認フロー**: ユーザー登録後にメール認証（confirm メール送信）を必須とするか？　しない場合は登録直後からログイン可能
- [ ] **`mfa_session_token` の管理**: 2FA の一時トークンを Redis で管理するか、JWT で発行するか
- [ ] **JWT の署名アルゴリズム**: RS256（非対称）vs HS256（対称）。マイクロサービス化の予定がなければ HS256 でも十分
- [ ] **全デバイスログアウト API**: `DELETE /auth/sessions` のようなエンドポイントを提供するか（ユーザーのセキュリティ設定画面から操作）
- [ ] **ログイン試行ログの DB 保存**: Redisのみで管理するか、audit_logs テーブルにも記録するか（監査要件次第）
- [ ] **バックアップコード再生成**: 2FA のバックアップコードを使い切った場合の再生成フローを設計する

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-02-19 | 初版作成 |
