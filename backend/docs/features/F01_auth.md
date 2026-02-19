# F01: 認証・ユーザー管理

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 1
> **最終更新**: 2026-02-19

---

## 1. 概要

プラットフォーム全体の入口となる認証基盤。メールアドレス＋パスワードによる基本認証に加え、
ソーシャルログイン（Google / LINE / Apple）・2要素認証（TOTP）・WebAuthn（指紋/顔認証）・
JWT によるステートレス認証を提供する。
ログイン状態の永続化（"30日間保持"）・全デバイスログアウト・退会・凍結・
非アクティブアーカイブなどのユーザーライフサイクル管理もここで担う。

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
| `email_verification_tokens` | メールアドレス確認トークン | なし |
| `refresh_tokens` | Refresh Token 管理・デバイス紐付け・永続化フラグ | なし |
| `oauth_accounts` | ソーシャルログイン連携 | なし |
| `two_factor_auth` | TOTP 秘密鍵・バックアップコード | なし |
| `password_reset_tokens` | パスワードリセットトークン | なし |
| `webauthn_credentials` | WebAuthn 公開鍵資格情報（指紋/顔認証） | なし |

---

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
| `avatar_url` | VARCHAR(500) | YES | NULL | S3 オブジェクトキーを保存（表示時に Pre-signed URL 生成）|
| `phone_number` | VARCHAR(20) | YES | NULL | 任意。将来のSMS認証用 |
| `status` | ENUM | NO | `PENDING_VERIFICATION` | `PENDING_VERIFICATION` / `ACTIVE` / `FROZEN` / `ARCHIVED` |
| `last_login_at` | DATETIME | YES | NULL | アーカイブ判定・非アクティブ検出 |
| `archived_at` | DATETIME | YES | NULL | 非アクティブアーカイブ日時 |
| `deleted_at` | DATETIME | YES | NULL | 退会申請日。30日後に個人情報を物理削除 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_users_email (email)
INDEX idx_users_status_last_login (status, last_login_at)  -- 非アクティブアーカイブバッチ用
INDEX idx_users_deleted_at (deleted_at)                    -- 退会後30日の物理削除バッチ用
```

**status の遷移**
```
PENDING_VERIFICATION
  └─（メール認証完了）──→ ACTIVE
       ├─（SYSTEM_ADMIN 凍結）──→ FROZEN ──（凍結解除）──→ ACTIVE
       ├─（退会申請）──→ deleted_at 付与 ──（30日後バッチ）──→ 個人情報物理削除
       └─（6ヶ月非アクティブ）──→ ARCHIVED ──（ログイン）──→ ACTIVE
```

**備考**
- `avatar_url` はオブジェクトキーを保存し、表示時に Pre-signed URL を生成する
- 電子印鑑 SVG は `user_seals` テーブルに分離（F01 スコープ外）
- `status=FROZEN` は SYSTEM_ADMIN による凍結。ログイン不可・既存セッション即時無効化

---

#### `email_verification_tokens`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `token_hash` | VARCHAR(64) | NO | — | SHA-256 ハッシュ |
| `expires_at` | DATETIME | NO | — | 発行から24時間後 |
| `used_at` | DATETIME | YES | NULL | 使用済みフラグ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_evt_token_hash (token_hash)
INDEX idx_evt_user_id (user_id)
INDEX idx_evt_expires_at (expires_at)  -- 期限切れトークン削除バッチ用
```

**備考**
- 確認メールのリンク: `https://example.com/verify-email?token=<raw_token>`
- `PENDING_VERIFICATION` のまま24時間経過したユーザーは再送信を促す
- 再送信時は既存トークンを無効化（`used_at` を設定）して新トークンを発行

---

#### `refresh_tokens`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `token_hash` | VARCHAR(64) | NO | — | SHA-256 ハッシュ（トークン本体は返却のみ） |
| `remember_me` | BOOLEAN | NO | false | true: 30日有効 / false: 7日有効 |
| `device_fingerprint` | VARCHAR(64) | YES | NULL | IP + User-Agent の SHA-256 ハッシュ |
| `ip_address` | VARCHAR(45) | YES | NULL | 発行時のIPアドレス（IPv6対応） |
| `user_agent` | VARCHAR(500) | YES | NULL | 発行時のUser-Agent |
| `expires_at` | DATETIME | NO | — | remember_me=false: 7日後 / true: 30日後 |
| `revoked_at` | DATETIME | YES | NULL | ローテーション・ログアウト時に設定 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_rt_token_hash (token_hash)
INDEX idx_rt_user_id (user_id)
INDEX idx_rt_expires_at (expires_at)  -- 期限切れトークン削除バッチ用
```

**ログイン永続化の仕組み**
- フロントエンドは Refresh Token を常に `localStorage` に保存（ブラウザを閉じても保持）
- "30日間ログインを保持する" チェックボックスの ON/OFF を `remember_me` フラグでバックエンドに伝達
- `remember_me=false`（デフォルト）: 7日間操作なしで自動ログアウト
- `remember_me=true`: 30日間保持。Access Token（15分）が切れるたびに Refresh Token で自動更新
- WebAuthn 登録済みデバイスでは `remember_me=true` をデフォルト推奨とし、指紋で素早く再ログイン可能

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
- 1ユーザーが複数プロバイダーを連携可能
- 既存メールアドレスの `users` レコードと `provider_email` が一致する場合は自動連携

---

#### `two_factor_auth`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users。UNIQUE |
| `totp_secret` | VARCHAR(255) | NO | — | AES-256 暗号化済み TOTP 秘密鍵 |
| `backup_codes` | JSON | NO | — | ハッシュ済みバックアップコード8件の配列 |
| `is_enabled` | BOOLEAN | NO | false | 初回 TOTP 認証成功後に true |
| `verified_at` | DATETIME | YES | NULL | 初回認証成功日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_2fa_user_id (user_id)
```

**バックアップコード再生成フロー**
1. `POST /auth/2fa/backup-codes/regenerate` を受け付け
2. 現在のパスワードまたは TOTP コードで本人確認
3. 8件の新しいバックアップコードを生成・ハッシュ化
4. `backup_codes` カラムを上書き更新
5. 新しいコード（平文）をレスポンスで一度だけ返す（再表示不可）

---

#### `password_reset_tokens`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `token_hash` | VARCHAR(64) | NO | — | SHA-256 ハッシュ |
| `expires_at` | DATETIME | NO | — | 発行から30分後 |
| `used_at` | DATETIME | YES | NULL | 使用済みフラグ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_prt_token_hash (token_hash)
INDEX idx_prt_user_id (user_id)
INDEX idx_prt_expires_at (expires_at)
```

---

#### `webauthn_credentials`

WebAuthn (FIDO2) による生体認証（Touch ID / Face ID / Android 指紋）の公開鍵資格情報を管理する。
ライブラリ: `com.webauthn4j:webauthn4j-spring-security`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `credential_id` | VARCHAR(500) | NO | — | WebAuthn Credential ID（Base64URL）。UNIQUE |
| `public_key` | TEXT | NO | — | COSE 形式の公開鍵 |
| `sign_count` | BIGINT UNSIGNED | NO | 0 | リプレイ攻撃検出用。認証のたびにインクリメント |
| `device_name` | VARCHAR(100) | YES | NULL | ユーザーが設定するデバイス名（例: "iPhone 15"） |
| `aaguid` | VARCHAR(36) | YES | NULL | Authenticator の種別識別子 |
| `last_used_at` | DATETIME | YES | NULL | 最終使用日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_wc_credential_id (credential_id)
INDEX idx_wc_user_id (user_id)
```

**WebAuthn の利用シナリオ**
- **初回**: メール＋パスワードで通常ログイン → "このデバイスで指紋認証を使いますか？" → 登録
- **2回目以降**: メールアドレス入力 → 指紋/顔認証 → ログイン完了（パスワード入力不要）
- **複数デバイス登録可能**: 1ユーザーが iPhone・MacBook・Android 等を個別に登録
- **削除**: セキュリティ設定画面からデバイスごとに登録解除可能

---

### ER図（テキスト形式）
```
users (1) ──── (N) email_verification_tokens
users (1) ──── (N) refresh_tokens
users (1) ──── (N) oauth_accounts
users (1) ──── (0..1) two_factor_auth
users (1) ──── (N) password_reset_tokens
users (1) ──── (N) webauthn_credentials
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| POST | `/api/v1/auth/register` | 不要 | ユーザー登録（メール確認メール送信） |
| POST | `/api/v1/auth/verify-email` | 不要 | メールアドレス確認（トークン検証）|
| POST | `/api/v1/auth/verify-email/resend` | 不要 | 確認メール再送信 |
| POST | `/api/v1/auth/login` | 不要 | メール＋パスワードログイン |
| POST | `/api/v1/auth/logout` | 必要 | ログアウト（現デバイスのトークン無効化） |
| DELETE | `/api/v1/auth/sessions` | 必要 | 全デバイスログアウト |
| GET | `/api/v1/auth/sessions` | 必要 | ログイン中デバイス一覧 |
| POST | `/api/v1/auth/refresh` | 不要 | Access Token 再発行（Refresh Token 使用）|
| POST | `/api/v1/auth/oauth/{provider}` | 不要 | ソーシャルログイン（Google/LINE/Apple）|
| POST | `/api/v1/auth/password-reset/request` | 不要 | パスワードリセットメール送信 |
| POST | `/api/v1/auth/password-reset/confirm` | 不要 | パスワード再設定 |
| POST | `/api/v1/auth/2fa/setup` | 必要 | TOTP セットアップ（秘密鍵・QRコード取得）|
| POST | `/api/v1/auth/2fa/verify` | 必要 | TOTP 初回認証（有効化）|
| POST | `/api/v1/auth/2fa/validate` | 必要（mfa_session_token）| ログイン時の TOTP コード検証 |
| POST | `/api/v1/auth/2fa/backup-codes/regenerate` | 必要 | バックアップコード再生成 |
| POST | `/api/v1/auth/webauthn/register/begin` | 必要 | WebAuthn 登録開始（challenge 取得）|
| POST | `/api/v1/auth/webauthn/register/complete` | 必要 | WebAuthn 登録完了（公開鍵保存）|
| POST | `/api/v1/auth/webauthn/login/begin` | 不要 | WebAuthn ログイン開始（challenge 取得）|
| POST | `/api/v1/auth/webauthn/login/complete` | 不要 | WebAuthn ログイン完了（署名検証）|
| DELETE | `/api/v1/auth/webauthn/credentials/{id}` | 必要 | WebAuthn デバイス登録解除 |
| GET | `/api/v1/users/me` | 必要 | 自分のプロフィール取得 |
| PUT | `/api/v1/users/me` | 必要 | 自分のプロフィール更新 |
| DELETE | `/api/v1/users/me` | 必要 | 退会申請（論理削除・30日猶予）|
| POST | `/api/v1/users/me/avatar` | 必要 | アバター画像アップロード用 Pre-signed URL 取得 |

---

### リクエスト／レスポンス仕様（主要エンドポイント）

#### `POST /api/v1/auth/login`

**リクエストボディ**
```json
{
  "email": "user@example.com",
  "password": "P@ssw0rd!",
  "remember_me": true
}
```

**レスポンス（200 OK）— 2FA 無効ユーザー**
```json
{
  "data": {
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "token_type": "Bearer",
    "expires_in": 900
  }
}
```

**レスポンス（200 OK）— 2FA 必要ユーザー**
```json
{
  "data": {
    "mfa_required": true,
    "mfa_session_token": "tmp_xxxx"
  }
}
```
※ `mfa_session_token` は Redis に5分間保持する一時トークン。`/auth/2fa/validate` に渡す。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 認証失敗（メールアドレスまたはパスワード不一致）|
| 403 | メール未確認（PENDING_VERIFICATION）|
| 423 | アカウントロック中（5回失敗で30分）|
| 429 | レートリミット超過（同一IP: 1分10回）|

---

#### `GET /api/v1/auth/sessions`

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "ip_address": "203.0.113.1",
      "user_agent": "Mozilla/5.0 (iPhone...)",
      "remember_me": true,
      "created_at": "2026-02-10T12:00:00Z",
      "last_used_at": "2026-02-19T08:30:00Z",
      "is_current": true
    }
  ]
}
```
※ `last_used_at` は Refresh Token の最終ローテーション日時から算出する。

---

#### `DELETE /api/v1/auth/sessions`（全デバイスログアウト）

- 当該ユーザーの全 `refresh_tokens` に `revoked_at` を設定
- 全 Access Token を Redis ブラックリストへ追加（JTI ベースで管理）
- レスポンス: `204 No Content`

---

## 5. ビジネスロジック

### ユーザー登録フロー
```
1. POST /auth/register 受付
2. email の重複チェック（UNIQUE 制約 + 存在確認）
3. password を bcrypt（cost 12）でハッシュ化
4. users に status=PENDING_VERIFICATION で INSERT
5. email_verification_tokens に有効期限24時間のトークンを生成・保存
6. 確認メールを送信（ApplicationEvent → MailService）
7. 201 Created を返す（トークンは返さない）
```

### ログインフロー
```
1. POST /auth/login 受付
2. email で users を検索（存在しない場合も同一レスポンスで返す: タイミング攻撃防止）
3. status チェック:
   - PENDING_VERIFICATION → 403（メール未確認）
   - FROZEN → 423
   - ARCHIVED → ログイン許可（ログイン成功後に archived_at をクリア）
4. Redis でロック状態を確認（5回失敗で30分ロック）
5. bcrypt でパスワード検証（失敗 → Redisカウンタをインクリメント・audit_logs に記録）
6. 2FA が有効か確認:
   6a. 2FA 無効: Access Token + Refresh Token を発行して返す
   6b. 2FA 有効: mfa_session_token を Redis に保存（TTL 5分）して返す
7. ログイン成功時: last_login_at 更新・Redisカウンタリセット・audit_logs に記録
```

### Refresh Token ローテーション
```
1. token_hash で DB を検索
2. revoked_at 設定済み → 不正使用の疑い → 同一ユーザーの全トークンを失効・audit_logs に記録
3. device_fingerprint が不一致 → 警告ログ + 再認証要求
4. 新 Access Token（HS256・15分）+ 新 Refresh Token を発行
5. 旧 Refresh Token に revoked_at を設定
```

### 退会フロー
```
1. DELETE /users/me 受付・パスワード再確認
2. users.deleted_at を現在日時に設定
3. 全 Refresh Token を失効・Access Token を Redis ブラックリストへ
4. 30日後バッチ（Phase 10 以降で実装）:
   - email / 氏名 / 電話番号 / avatar_url を NULL に上書き
   - oauth_accounts / two_factor_auth / webauthn_credentials を物理削除
   - 決済履歴（payment_records 等）は税法準拠で7年間保持
```

---

## 6. セキュリティ考慮事項

| 項目 | 設定値・方針 |
|------|------------|
| パスワードハッシュ | bcrypt・コストファクター **12** |
| パスワードポリシー | 8文字以上・英大文字/小文字/数字/記号のうち3種以上・メールアドレスと同一禁止 |
| JWT 署名 | **HS256**（共通鍵・環境変数 `JWT_SECRET` で管理） |
| Access Token 有効期限 | 15分 |
| Refresh Token 有効期限 | 7日（remember_me=true: 30日） |
| ログイン失敗制限 | 同一IP: 1分10回・5回失敗でアカウント30分ロック（Redis 管理） |
| パスワードリセット | 同一IP: 1分3回まで |
| TOTP 秘密鍵暗号化 | AES-256（`AES_ENCRYPTION_KEY` 環境変数）|
| WebAuthn リプレイ攻撃対策 | `sign_count` が前回値以下なら認証拒否 |
| デバイスバインディング | Refresh Token 使用時に device_fingerprint 不一致 → 警告 + 再認証 |
| 2FA 必須 | SYSTEM_ADMIN・ADMIN は 2FA 有効化を必須化。未設定のまま管理操作 → 2FA 設定画面へリダイレクト |
| タイミング攻撃対策 | 存在しないメールアドレスでも bcrypt 相当の処理時間を確保（ダミーハッシュを保持）|

---

## 7. Flywayマイグレーション

```
V1.001__create_users_table.sql
V1.002__create_email_verification_tokens_table.sql
V1.003__create_refresh_tokens_table.sql
V1.004__create_oauth_accounts_table.sql
V1.005__create_two_factor_auth_table.sql
V1.006__create_password_reset_tokens_table.sql
V1.007__create_webauthn_credentials_table.sql
V1.008__seed_system_admin_user.sql
```

**V1.008 の注意点**
- Flyway SQL には平文パスワードを書かない
- `ApplicationRunner`（`@Profile("!test")`）で SYSTEM_ADMIN を作成し、Spring の `PasswordEncoder` でハッシュ化する
- 環境変数 `INITIAL_ADMIN_EMAIL` / `INITIAL_ADMIN_PASSWORD` から読み込む

---

## 8. 未解決事項

*設計確定につき、当初の未解決事項はすべて解消。*

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-02-19 | 初版作成 |
| 2026-02-19 | メール確認フロー・WebAuthn・ログイン永続化・全デバイスログアウト・audit_logs 連携・バックアップコード再生成を追加。未解決事項を全解消 |
