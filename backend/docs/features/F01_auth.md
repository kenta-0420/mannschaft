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
| `email_change_tokens` | メールアドレス変更確認トークン | なし |
| `oauth_link_tokens` | OAuth連携統合承認トークン（衝突時の確認メール用） | なし |
| `mfa_recovery_tokens` | 2FA完全ロックアウト時の回復トークン | なし |
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
- フロントエンドは登録完了後に「確認メールを送信した旨・送信先・有効期限・再送信ボタン（60秒クールダウン）」を画面に表示すること（FRONTEND_CODING_CONVENTION §13 参照）

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
| `last_used_at` | DATETIME | YES | NULL | このトークンが最後にローテーションに使われた日時。セッション一覧の「最終アクティブ」に使用 |
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
- 既存メールアドレスの `users` レコードと `provider_email` が一致する場合は **自動連携しない**。統合確認メールを送信し、ユーザーが承認した時点で連携を記録する（`oauth_link_tokens` 参照）

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

#### `email_change_tokens`

メールアドレス変更の2段階確認フロー用。変更先アドレスへ送る確認トークンを管理する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `new_email` | VARCHAR(255) | NO | — | 変更先メールアドレス（確認完了前は仮保持）|
| `token_hash` | VARCHAR(64) | NO | — | SHA-256 ハッシュ |
| `expires_at` | DATETIME | NO | — | 発行から24時間後 |
| `used_at` | DATETIME | YES | NULL | 使用済みフラグ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_ect_token_hash (token_hash)
INDEX idx_ect_user_id (user_id)
INDEX idx_ect_expires_at (expires_at)  -- 期限切れトークン削除バッチ用
```

**備考**
- 確認メールのリンク: `https://example.com/settings/email/confirm?token=<raw_token>`
- 変更リクエスト中に再度リクエストした場合は既存トークンを無効化して再発行
- 変更完了後、旧メールアドレスにも「メールアドレスが変更されました」通知を送信（不正変更の検出）
- フロントエンドは 202 受信後に「確認メールを送信した旨・送信先（新メールアドレス）・有効期限」を画面に表示すること（FRONTEND_CODING_CONVENTION §13 参照）

---

#### `oauth_link_tokens`

OAuth ログイン時に同一メールの既存アカウントと衝突した場合、既存アカウントへ送信する「統合承認メール」のトークンを管理する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（既存アカウント）|
| `provider` | ENUM | NO | — | `GOOGLE` / `LINE` / `APPLE` |
| `provider_user_id` | VARCHAR(255) | NO | — | プロバイダー側のユーザーID（承認後に oauth_accounts へ移動）|
| `provider_email` | VARCHAR(255) | YES | NULL | プロバイダーから取得したメールアドレス（参考値）|
| `token_hash` | VARCHAR(64) | NO | — | SHA-256 ハッシュ |
| `expires_at` | DATETIME | NO | — | 発行から24時間後 |
| `used_at` | DATETIME | YES | NULL | 使用済みフラグ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_olt_token_hash (token_hash)
INDEX idx_olt_user_id (user_id)
INDEX idx_olt_expires_at (expires_at)  -- 期限切れトークン削除バッチ用
```

**備考**
- 承認リンク: `https://example.com/auth/oauth/link/confirm?token=<raw_token>`
- 再度 OAuth ログインを試みた場合は既存トークンを無効化して再発行
- 承認後: `oauth_accounts` に `(user_id, provider, provider_user_id)` を INSERT し、通常ログイン処理へ進む
- フロントエンドは 202（oauth_conflict: true）受信後に「既存アカウントへ確認メールを送信した旨・次のアクション」をトースト通知で表示すること（FRONTEND_CODING_CONVENTION §13 参照）

---

#### `mfa_recovery_tokens`

TOTP デバイス紛失・バックアップコード全消費による完全ロックアウト時に、メールアドレスを使った
2FA バイパス回復フロー用のトークンを管理する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `token_hash` | VARCHAR(64) | NO | — | SHA-256 ハッシュ |
| `expires_at` | DATETIME | NO | — | 発行から1時間後 |
| `used_at` | DATETIME | YES | NULL | 使用済みフラグ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_mrt_token_hash (token_hash)
INDEX idx_mrt_user_id (user_id)
INDEX idx_mrt_expires_at (expires_at)  -- 期限切れトークン削除バッチ用
```

**備考**
- 発行レートリミット: 同一ユーザーが24時間に3回を超えて申請した場合は拒否
- 1ユーザーにつき有効なトークンは常に1件のみ。再申請時は既存トークンを無効化して再発行
- 使用後: `two_factor_auth.is_enabled` を `false` に更新し、ユーザーに2FA再設定を促す
- フロントエンドは回復メール送信後に「送信した旨・1時間有効」を画面に表示すること（FRONTEND_CODING_CONVENTION §13 参照）

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
users (1) ──── (N) oauth_link_tokens      ← 衝突時の統合承認フロー用
users (1) ──── (N) mfa_recovery_tokens   ← 2FA完全ロックアウト回復用
users (1) ──── (0..1) two_factor_auth
users (1) ──── (N) password_reset_tokens
users (1) ──── (N) email_change_tokens
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
| POST | `/api/v1/auth/oauth/link/confirm` | 不要 | OAuth連携統合承認（確認メールのトークンで承認）|
| GET | `/api/v1/users/me/oauth` | 必要 | 連携済みOAuthプロバイダー一覧取得 |
| DELETE | `/api/v1/users/me/oauth/{provider}` | 必要 | OAuthプロバイダー連携解除 |
| POST | `/api/v1/auth/password-reset/request` | 不要 | パスワードリセットメール送信 |
| POST | `/api/v1/auth/password-reset/confirm` | 不要 | パスワード再設定 |
| POST | `/api/v1/auth/2fa/setup` | 必要 | TOTP セットアップ（秘密鍵・QRコード取得）|
| POST | `/api/v1/auth/2fa/verify` | 必要 | TOTP 初回認証（有効化）|
| POST | `/api/v1/auth/2fa/validate` | 必要（mfa_session_token）| ログイン時の TOTP コード検証 |
| POST | `/api/v1/auth/2fa/backup-codes/regenerate` | 必要 | バックアップコード再生成 |
| POST | `/api/v1/auth/2fa/recovery/request` | 不要（mfa_session_token）| 2FAロックアウト回復メール送信 |
| POST | `/api/v1/auth/2fa/recovery/confirm` | 不要 | 回復トークン検証・2FAバイパスログイン |
| POST | `/api/v1/auth/webauthn/register/begin` | 必要 | WebAuthn 登録開始（challenge 取得）|
| POST | `/api/v1/auth/webauthn/register/complete` | 必要 | WebAuthn 登録完了（公開鍵保存）|
| POST | `/api/v1/auth/webauthn/login/begin` | 不要 | WebAuthn ログイン開始（challenge 取得）|
| POST | `/api/v1/auth/webauthn/login/complete` | 不要 | WebAuthn ログイン完了（署名検証）|
| GET | `/api/v1/auth/webauthn/credentials` | 必要 | 登録済みデバイス一覧取得 |
| PATCH | `/api/v1/auth/webauthn/credentials/{id}` | 必要 | デバイス名変更 |
| DELETE | `/api/v1/auth/webauthn/credentials/{id}` | 必要 | WebAuthn デバイス登録解除 |
| GET | `/api/v1/users/me` | 必要 | 自分のプロフィール取得 |
| PUT | `/api/v1/users/me` | 必要 | 自分のプロフィール更新 |
| PATCH | `/api/v1/users/me/password` | 必要 | パスワード変更（ログイン中・現パスワード確認あり）|
| PATCH | `/api/v1/users/me/email` | 必要 | メールアドレス変更リクエスト（変更先アドレスへ確認メール送信）|
| POST | `/api/v1/users/me/email/confirm` | 不要 | メールアドレス変更確認（トークン検証・更新完了）|
| DELETE | `/api/v1/users/me` | 必要 | 退会申請（論理削除・30日猶予）|
| POST | `/api/v1/users/me/withdrawal/cancel` | 必要 | 退会申請キャンセル（30日猶予期間中のみ）|
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
    "expires_in": 900,
    "pending_deletion_until": null
  }
}
```
※ 猶予期間中の退会申請ユーザーは `pending_deletion_until: "2026-03-21T10:00:00Z"` が入る。フロントエンドはこの値が non-null の場合、「退会申請中」バナーとキャンセルボタンを表示する。

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
※ `last_used_at` は `refresh_tokens.last_used_at`（ローテーション時に更新）を返す。一度もローテーションしていない場合（ログイン直後）は `created_at` にフォールバックする。

---

#### `DELETE /api/v1/auth/sessions`（全デバイスログアウト）

- 当該ユーザーの全 `refresh_tokens` に `revoked_at` を設定
- Redis に無効化タイムスタンプを設定（`user_invalidated_at:{user_id}` = 現在 Unix timestamp、TTL 15分）
- JWT 検証時に `iat < user_invalidated_at` なら 401 を返す（既存の全 Access Token が即時無効化）
- レスポンス: `204 No Content`

---

#### `POST /api/v1/users/me/withdrawal/cancel`

**リクエストボディ**
- なし

**レスポンス（200 OK）**
```json
{
  "data": {
    "message": "退会申請をキャンセルしました。引き続きご利用いただけます。"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 409 | 退会申請が存在しない（`deleted_at` が NULL）|
| 410 | 猶予期間（30日）が経過済み — 物理削除バッチ実行後 |

---

#### `GET /api/v1/auth/webauthn/credentials`

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "device_name": "iPhone 15",
      "aaguid": "adce0002-35bc-c60a-648b-0b25f1f05503",
      "last_used_at": "2026-02-19T09:00:00Z",
      "created_at": "2026-01-10T12:00:00Z"
    },
    {
      "id": 2,
      "device_name": "MacBook Pro",
      "aaguid": null,
      "last_used_at": "2026-02-18T22:00:00Z",
      "created_at": "2026-01-20T15:30:00Z"
    }
  ]
}
```
※ `public_key` / `credential_id` / `sign_count` はセキュリティ上レスポンスに含めない。

---

#### `PATCH /api/v1/auth/webauthn/credentials/{id}`

**リクエストボディ**
```json
{
  "device_name": "仕事用 MacBook"
}
```

**バリデーション**
- `device_name`: 必須・1〜100文字

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 2,
    "device_name": "仕事用 MacBook",
    "updated_at": "2026-02-19T10:00:00Z"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 404 | 指定 ID が存在しない、または他ユーザーのデバイス |

---

#### `GET /api/v1/users/me/oauth`

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "provider": "GOOGLE",
      "provider_email": "user@gmail.com",
      "connected_at": "2026-01-15T10:00:00Z"
    },
    {
      "provider": "LINE",
      "provider_email": null,
      "connected_at": "2026-02-01T08:30:00Z"
    }
  ]
}
```
※ `provider_user_id` はセキュリティ上レスポンスに含めない。

---

#### `DELETE /api/v1/users/me/oauth/{provider}`

**パスパラメータ**
- `provider`: `GOOGLE` / `LINE` / `APPLE`

**レスポンス（204 No Content）**
- ボディなし

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 404 | 指定プロバイダーが連携されていない |
| 409 | 連携解除するとログイン手段が失われる（パスワード未設定 かつ 他の連携プロバイダーも存在しない）|

---

#### `PATCH /api/v1/users/me/password`

**リクエストボディ**
```json
{
  "current_password": "OldP@ssw0rd!",
  "new_password": "NewP@ssw0rd!"
}
```

**バリデーション**
- `current_password`: 必須・空文字禁止
- `new_password`: 必須・パスワードポリシー準拠（8文字以上・英大文字/小文字/数字/記号のうち3種以上・メールアドレスと同一禁止）
- `current_password == new_password` の場合はエラー（同じパスワードへの変更禁止）

**レスポンス（204 No Content）**
- ボディなし

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（ポリシー違反・現在と同じパスワード） |
| 401 | `current_password` が不一致 |
| 403 | OAuth のみのアカウント（`password_hash` が NULL）— パスワード設定が必要 |
| 429 | レートリミット超過（1分5回まで） |

**備考**
- パスワード変更成功後、現在のデバイス（リクエスト元の Refresh Token）を除く全セッションを失効させる
- `audit_logs` にパスワード変更イベントを記録する

---

#### `POST /api/v1/auth/2fa/recovery/request`

**前提**: ログイン時の TOTP 検証ステップで `mfa_session_token` を保持している状態から呼び出す。

**リクエストボディ**
```json
{
  "mfa_session_token": "tmp_xxxx"
}
```

**レスポンス（202 Accepted）**
```json
{
  "data": {
    "message": "回復メールを登録済みのメールアドレスに送信しました。1時間以内に手続きを完了してください。"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `mfa_session_token` が無効・期限切れ |
| 429 | 24時間に3回を超えた申請 |

---

#### `POST /api/v1/auth/2fa/recovery/confirm`

**リクエストボディ**
```json
{
  "token": "<raw_token>"
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "token_type": "Bearer",
    "expires_in": 900,
    "mfa_disabled": true
  }
}
```
※ `mfa_disabled: true` はフロントエンドへの合図。「2FAが無効化されました。セキュリティのため再設定を推奨します」の案内を表示する。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | トークン形式不正・期限切れ（1時間）・使用済み |

---

#### `POST /api/v1/auth/oauth/{provider}`（OAuth衝突時レスポンス）

同一メールアドレスの既存アカウントが存在する場合、ログインは完了せず以下を返す。

**レスポンス（202 Accepted）— 衝突時のみ**
```json
{
  "data": {
    "oauth_conflict": true,
    "message": "このメールアドレスには既存のアカウントがあります。確認メールを送信しました。メール内のリンクから連携を承認してください。"
  }
}
```

**レスポンス（200 OK）— 通常ログイン成功（既連携 or 新規作成）**
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

---

#### `POST /api/v1/auth/oauth/link/confirm`（連携承認）

**リクエストボディ**
```json
{
  "token": "<raw_token>"
}
```

**レスポンス（200 OK）— 承認完了・ログイン**
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
※ 承認と同時にログイン済み状態になる。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | トークン形式不正・期限切れ（24時間）・使用済み |
| 409 | 承認時点で同一 (provider, provider_user_id) が既に別アカウントと連携済み（レアケース）|

---

#### `PATCH /api/v1/users/me/email`（変更リクエスト）

**リクエストボディ**
```json
{
  "new_email": "new@example.com",
  "current_password": "P@ssw0rd!"
}
```

**バリデーション**
- `new_email`: 必須・正しいメール形式・現在のメールアドレスと異なること・他ユーザーと重複しないこと
- `current_password`: 必須（OAuth 専用アカウントは 403 — パスワード設定が必要）

**レスポンス（202 Accepted）**
```json
{
  "data": {
    "message": "確認メールを new@example.com に送信しました。24時間以内に確認してください。"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | メール形式不正・現在と同じメールアドレス |
| 401 | `current_password` が不一致 |
| 403 | OAuth のみのアカウント（`password_hash` が NULL）|
| 409 | 変更先メールアドレスが既に他ユーザーに使用されている |
| 429 | レートリミット超過（1分3回まで）|

---

#### `POST /api/v1/users/me/email/confirm`（変更確認・完了）

**リクエストボディ**
```json
{
  "token": "<raw_token>"
}
```

**レスポンス（204 No Content）**
- ボディなし。以降は新しいメールアドレスでログイン可能

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | トークン形式不正・期限切れ・使用済み |
| 409 | トークン発行後に変更先メールが他ユーザーに使われた（競合）|

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
   - deleted_at が設定済み（猶予期間中）→ ログイン許可。レスポンスに `pending_deletion_until`（deleted_at + 30日）を含める
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
4. 旧 Refresh Token の last_used_at を現在日時に更新
5. 新 Access Token（HS256・15分）+ 新 Refresh Token を発行
6. 旧 Refresh Token に revoked_at を設定
```

### JWT ペイロード仕様（Access Token）

Refresh Token は DB 管理の opaque token（`token_hash` で識別）であり、JWT ではない。
JWT を使用するのは **Access Token のみ**。

**ペイロード例**
```json
{
  "sub":   "12345",
  "jti":   "550e8400-e29b-41d4-a716-446655440000",
  "roles": ["MEMBER"],
  "exp":   1708380000,
  "iat":   1708379100,
  "iss":   "mannschaft"
}
```

**クレーム定義**

| クレーム | 型 | 説明 |
|---------|---|------|
| `sub` | String | ユーザーID（`users.id` を文字列化）。Spring Security の `principal` として扱う |
| `jti` | String | UUID v4。**単一デバイスログアウト**時に Redis ブラックリストへ登録するキー |
| `roles` | String[] | プラットフォームレベルのロール。**`SYSTEM_ADMIN` のみ特別扱い**。それ以外は `["USER"]` 固定 |
| `exp` | Number | 有効期限（Unix timestamp）。発行時刻 + 900秒（15分）|
| `iat` | Number | 発行日時（Unix timestamp）|
| `iss` | String | 固定値 `"mannschaft"` |

**`roles` クレームの設計方針**

組織・チーム内のロール（ADMIN / MEMBER / SUPPORTER 等）は **コンテキスト依存**（どの組織のADMINか）のため JWT に埋め込まない。
リクエスト処理時に DB から動的に解決する。

```
JWT の roles: ["USER"]
  └─ Service 層で users ↔ organization_members / team_members を JOIN して
     「この操作に対してこのユーザーがこの組織/チームで何のロールを持つか」を都度解決
```

SYSTEM_ADMIN のみ JWT に持つことで、プラットフォーム管理APIのフィルタリングを DB ラウンドトリップなしに行える。

**Access Token 無効化戦略（2方式の使い分け）**

| 方式 | Redis キー | TTL | 使用するイベント |
|------|-----------|-----|----------------|
| JTI ブラックリスト | `blacklist:jti:{jti}` | `exp - 現在時刻`（秒）| 単一デバイスログアウト（`POST /auth/logout`）|
| ユーザー無効化タイムスタンプ | `user_invalidated_at:{user_id}` | 15分（Access Token の最大寿命）| 全デバイスログアウト・パスワード変更・メールアドレス変更・退会・アカウント凍結 |

**JWT 検証フロー（フィルター層）**
```
1. JTI が blacklist:jti:{jti} に存在する → 401
2. user_invalidated_at:{sub} が存在し、iat < その値 → 401
3. 上記いずれも該当しない → 通過
```

**ユーザー無効化タイムスタンプの TTL 根拠**
- Access Token の有効期限は最長 15分。無効化タイムスタンプを設定した 15分後には、それ以前に発行された全 Access Token が自然失効するため、Redis キーも不要になる。TTL = 900秒 で自動回収する。

**パスワード変更後の現デバイス挙動**
- ユーザー無効化タイムスタンプは全 Access Token を無効化するため、現デバイスのトークンも失効する。
- フロントエンドは 401 受信後、保持している Refresh Token で自動再取得（Refresh Token は `revoked_at` が未設定のため有効）。ユーザーには透過的に見える。

### OAuth ログインフロー（衝突時を含む）
```
1. POST /auth/oauth/{provider} 受付（認可コード受け取り）
2. プロバイダーの Token エンドポイントへ認可コードを送信し、アクセストークンを取得
3. プロバイダーのユーザー情報エンドポイントから (provider_user_id, email 等) を取得
4. oauth_accounts テーブルで (provider, provider_user_id) を検索:
   ├─ 【既連携】レコードが存在する → 紐づく users を取得 → 通常ログイン処理（手順8）
   └─ 【未連携】レコードが存在しない → 手順5へ
5. provider_email で users テーブルを検索:
   ├─ 【新規】ユーザーが存在しない
   │    → users に status=ACTIVE で新規作成（メール確認不要）
   │    → oauth_accounts に INSERT
   │    → 通常ログイン処理（手順8）
   └─ 【衝突】同一メールの既存ユーザーが存在する
        → 既存の未使用 oauth_link_tokens があれば無効化
        → oauth_link_tokens に有効期限24時間のトークンを生成・保存
        → 既存アカウントのメールアドレスへ「統合確認メール」を送信
        → audit_logs に OAUTH_LINK_REQUESTED を記録
        → 202 Accepted（oauth_conflict: true）を返す ← ここで処理終了
6. 【衝突後】ユーザーが確認メールのリンクをクリック
   → POST /auth/oauth/link/confirm へ
7. トークンで oauth_link_tokens を検索 → 期限・使用済みチェック
8. oauth_accounts に (user_id, provider, provider_user_id) を INSERT
9. oauth_link_tokens.used_at を設定
10. audit_logs に OAUTH_LINKED を記録（衝突フロー経由の場合）
11. 通常ログイン処理（Access Token + Refresh Token 発行）
```

**注意**: 衝突フローでは既存アカウントの `password_hash` の有無を問わない。
パスワードなし（OAuth 専用）の既存アカウントであっても同じフローを適用する。

### 2FA 完全ロックアウト回復フロー

**対象ケース**: TOTP デバイス紛失 かつ バックアップコードも全て使用済み・紛失 → ログイン不能状態

```
【回復メール送信: POST /auth/2fa/recovery/request】
1. mfa_session_token を Redis で検証（存在しない・期限切れ → 400）
2. mfa_session_token から user_id を特定
3. 24時間以内の mfa_recovery_tokens 発行数を確認（3件超 → 429）
4. 未使用の既存 mfa_recovery_tokens があれば used_at を設定して無効化
5. mfa_recovery_tokens に有効期限1時間のトークンを生成・保存
6. 登録メールアドレスへ回復メールを送信（ApplicationEvent → MailService）
   件名:「【重要】2段階認証の回復リンク」
   本文: 回復リンク（1時間有効）＋「このメールに心当たりがない場合は〇〇」の警告
7. audit_logs に MFA_RECOVERY_REQUESTED を記録
8. 202 Accepted を返す

【回復確認: POST /auth/2fa/recovery/confirm】
1. token_hash で mfa_recovery_tokens を検索
2. used_at 設定済み・expires_at 超過 → 400
3. mfa_recovery_tokens.used_at を現在日時に設定
4. two_factor_auth.is_enabled を false に更新（2FA 無効化）
5. Access Token + Refresh Token を発行
6. 登録メールアドレスに「2FAが無効化されました」通知メールを送信
7. audit_logs に MFA_RECOVERY_COMPLETED を記録
8. 200 OK（mfa_disabled: true）を返す
```

**回復後のユーザー体験**
```
回復ログイン成功
  └─ フロントが mfa_disabled: true を検知
       └─ 「2段階認証が無効化されました。セキュリティのために再設定を推奨します」バナーを表示
            ├─ 「今すぐ設定する」→ /settings/security/2fa/setup へ誘導
            └─ 「後で設定する」→ 閉じる（ADMIN は設定を強制: セキュリティ設定画面へリダイレクト）
```

**セキュリティ上の考慮**
- 回復フローはメールアドレスの所有確認に依存する。メール自体が漏洩している場合は防げない
- 24時間3回の発行制限とレートリミットで自動化攻撃を抑止する
- 回復使用後は audit_logs に残るため、不審な回復があれば管理者が検知できる

### OAuth アカウント管理フロー

```
【一覧取得: GET /users/me/oauth】
1. oauth_accounts テーブルから user_id に紐づく全レコードを取得
2. provider / provider_email / created_at のみを返す（provider_user_id は除外）

【連携解除: DELETE /users/me/oauth/{provider}】
1. リクエスト受付（Access Token で認証済みユーザー）
2. oauth_accounts で (user_id, provider) を検索 → 存在しない場合は 404
3. ログイン手段の残存チェック（409 条件）:
   - users.password_hash が NULL（パスワード未設定）
     かつ oauth_accounts の残件数が 1件のみ（= 解除するとログイン不能）
     → 409 を返す
4. oauth_accounts のレコードを物理削除
5. audit_logs に OAUTH_UNLINKED（provider 名を記録）を記録
6. 204 No Content を返す
```

**ログイン手段ゼロ防止ルール**
| password_hash | 他の連携数 | 解除可否 |
|--------------|-----------|---------|
| あり | 0 以上 | ✅ 可（パスワードでログイン可能）|
| なし | 1 以上 | ✅ 可（他プロバイダーでログイン可能）|
| なし | 0（= 解除対象のみ）| ❌ 不可（409）|

### WebAuthn デバイス管理フロー

```
【一覧取得: GET /auth/webauthn/credentials】
1. webauthn_credentials から user_id に紐づく全レコードを取得
2. last_used_at の降順で返す（最近使ったデバイスが上に来る）
3. public_key / credential_id / sign_count は除外して返す

【デバイス名変更: PATCH /auth/webauthn/credentials/{id}】
1. webauthn_credentials で (id, user_id) を検索 → 存在しない場合は 404
2. device_name を更新
3. 200 OK を返す

【登録解除: DELETE /auth/webauthn/credentials/{id}】
1. webauthn_credentials で (id, user_id) を検索 → 存在しない場合は 404
2. レコードを物理削除
3. audit_logs に WEBAUTHN_CREDENTIAL_REMOVED（device_name を記録）を記録
4. 204 No Content を返す
```

**登録解除時の注意**
- WebAuthn が唯一のログイン手段であっても削除を許容する（パスワード再設定フローで復旧可能なため）
- ただし最後の WebAuthn デバイスを削除した場合、フロントエンドは「生体認証が無効になります」の警告を表示する

### WebAuthn ログインフロー
```
【ログイン開始: POST /auth/webauthn/login/begin】
1. email を受け取り users を検索（存在しない場合は 404 を返す）
2. status チェック（FROZEN → 423 等）
3. challenge（ランダム 32バイト）を生成し Redis に一時保存（TTL 5分）
4. challenge + 登録済み credential_id 一覧をレスポンスで返す
5. ブラウザが WebAuthn API を呼び出し、生体認証デバイスで署名を生成

【ログイン完了: POST /auth/webauthn/login/complete】
6. Redis から challenge を取得・使用済みチェック（リプレイ攻撃防止）
7. credential_id で webauthn_credentials を検索（存在しない場合は 401）
8. 公開鍵で署名を検証（失敗 → 401・audit_logs に WEBAUTHN_LOGIN_FAILED を記録）
9. sign_count が DB の値以下 → リプレイ攻撃の疑い → 認証拒否・audit_logs に記録
10. webauthn_credentials.sign_count + last_used_at を更新
11. 2FA優先順位ルールにより TOTP チェックをスキップ（下記参照）
12. Access Token + Refresh Token を発行して返す
13. users.last_login_at 更新・audit_logs に WEBAUTHN_LOGIN を記録
```

### 2FA優先順位

| 認証手段 | TOTP 要否 | 理由 |
|---------|----------|------|
| WebAuthn（生体認証）でログイン成功 | **不要（スキップ）** | WebAuthn は「デバイス所持 + 生体情報」を同時に検証する。パスワード認証 + TOTPと同等以上の強度があり、2FA済みとみなす |
| パスワード認証 + WebAuthn 未使用 | **必要**（2FA有効の場合） | パスワード単体は1要素。TOTP で2要素目を確認する |
| パスワードでログイン + WebAuthn 登録済みでも使用しなかった場合 | **必要**（2FA有効の場合） | WebAuthnを選ばなかった場合はTOTPを要求する。強力な認証を促すため |

**実装上の注意**: WebAuthn ログイン完了エンドポイント（`/auth/webauthn/login/complete`）では、
`two_factor_auth.is_enabled` が `true` でも TOTP チェックを行わずに直接トークンを発行する。

### TOTP コード検証フロー（再利用防止含む）

TOTP は 30秒ごとに新しいコードを生成する。同一ウィンドウ内で同じコードを複数回使用できないよう、
Redis で使用済みコードを追跡する。

```
【対象エンドポイント】
- POST /auth/2fa/validate     （ログイン時の TOTP 検証）
- POST /auth/2fa/verify       （初回有効化時の検証）
- POST /auth/2fa/backup-codes/regenerate  （バックアップコード再生成時の本人確認）

【検証フロー】
1. two_factor_auth テーブルから totp_secret（AES-256 復号）を取得
2. 時刻ベースで有効なコードを生成（現ウィンドウ ± 1ウィンドウ = 計3ウィンドウを許容）
   ※ ±1ウィンドウ（30秒）の許容はサーバー・デバイス間のクロックスキュー対策
3. 送信されたコードと照合（不一致 → 401）
4. Redis で再利用チェック: `totp_used:{user_id}:{totp_code}` が存在する → 400（再利用拒否）
5. 検証成功: Redis に `totp_used:{user_id}:{totp_code}` を登録（TTL 90秒）
   ※ TTL 90秒 = 3ウィンドウ分（コードが有効でありうる最大時間）
6. 以降の処理（トークン発行 / 2FA有効化 / バックアップコード再生成）へ進む
```

**バックアップコードの再利用防止**
バックアップコードは `two_factor_auth.backup_codes`（JSON 配列）に格納されており、使用時に配列から削除するため DB レベルで自動的に使い捨てとなる。Redis 管理は不要。

**Redis キー形式**
```
totp_used:{user_id}:{6桁コード}  →  値: "1"、TTL: 90秒
```

### メールアドレス変更フロー
```
【変更リクエスト: PATCH /users/me/email】
1. リクエスト受付（Access Token で認証済みユーザー）
2. OAuth 専用アカウントの場合は 403 を返す
3. current_password を bcrypt で検証（失敗 → 401）
4. new_email の形式・重複チェック（UNIQUE 制約）
5. 未完了の email_change_tokens が残っている場合は used_at を設定して無効化
6. email_change_tokens に有効期限24時間のトークンを生成・保存
7. new_email に確認メールを送信（ApplicationEvent → MailService）
8. audit_logs に EMAIL_CHANGE_REQUESTED を記録
9. 202 Accepted を返す（メールアドレスはまだ変更されない）

【変更確認: POST /users/me/email/confirm】
1. トークンで email_change_tokens を検索
2. used_at 設定済み（使用済み）または expires_at 超過 → 400
3. new_email が現時点で他ユーザーに使用されていないか再チェック（競合 → 409）
4. users.email を new_email に更新
5. email_change_tokens.used_at を設定
6. 全 Refresh Token を失効（メールアドレス変更は重要なセキュリティイベント）
7. Redis に `user_invalidated_at:{user_id}` = 現在 Unix timestamp（TTL 900秒）を設定 → 全 Access Token を即時無効化
8. 旧メールアドレスに「メールアドレスが変更されました」通知メールを送信
9. audit_logs に EMAIL_CHANGED を記録（旧アドレスも記録）
10. 204 No Content を返す
```

### パスワード変更フロー
```
1. PATCH /users/me/password 受付（Access Token で認証済みユーザー）
2. users.password_hash が NULL の場合は 403 を返す（OAuth のみのアカウント）
3. current_password を bcrypt で検証（失敗 → 401。失敗回数は Redis でカウント・ログイン失敗カウンタとは別管理）
4. new_password のポリシー検証（8文字以上・3種以上・メールアドレスと同一禁止・現在と同一禁止）
5. new_password を bcrypt（cost 12）でハッシュ化
6. users.password_hash を更新
7. 現デバイス（リクエスト元の token_hash）以外の全 refresh_tokens に revoked_at を設定
8. Redis に `user_invalidated_at:{user_id}` = 現在 Unix timestamp（TTL 900秒）を設定 → 全 Access Token を即時無効化
   ※ 現デバイスの Access Token も失効するが、フロントは 401 後に Refresh Token で自動再取得（透過的）
9. audit_logs にパスワード変更イベントを記録（event_type: PASSWORD_CHANGED）
10. 204 No Content を返す
```

### 退会フロー
```
1. DELETE /users/me 受付・パスワード再確認
2. users.deleted_at を現在日時に設定
3. 全 Refresh Token を失効・Redis に `user_invalidated_at:{user_id}` を設定（TTL 900秒）
4. audit_logs に WITHDRAWAL_REQUESTED を記録
5. 30日後バッチ（Phase 10 以降で実装）:
   - email / 氏名 / 電話番号 / avatar_url を NULL に上書き
   - oauth_accounts / two_factor_auth / webauthn_credentials を物理削除
   - 決済履歴（payment_records 等）は税法準拠で7年間保持
```

### 退会申請キャンセルフロー
```
1. POST /users/me/withdrawal/cancel 受付（Access Token で認証済みユーザー）
   ※ 退会後はセッションが失効しているため、ユーザーは一度再ログインが必要
   ※ ログイン時に deleted_at が設定済みの場合は pending_deletion_until を返し、
      フロントエンドが「退会申請中」バナーとキャンセルボタンを表示する
2. users.deleted_at が NULL → 409（退会申請が存在しない）
3. deleted_at + 30日 < 現在時刻 → 410（猶予期間超過・物理削除済み）
4. users.deleted_at を NULL にクリア
5. audit_logs に WITHDRAWAL_CANCELLED を記録
6. 200 OK を返す
```

**猶予期間中のユーザー体験**
```
退会申請
  └─ 全セッション失効
       └─ ユーザーが気が変わる
            └─ 再ログイン（deleted_at ありでも許可）
                 └─ レスポンスに pending_deletion_until が入る
                      └─ フロントが「退会申請中バナー」を表示
                           └─ 「キャンセルする」→ POST /users/me/withdrawal/cancel
                                └─ deleted_at = NULL → 通常利用に戻る
```

---

## 6. セキュリティ考慮事項

| 項目 | 設定値・方針 |
|------|------------|
| パスワードハッシュ | bcrypt・コストファクター **12** |
| パスワードポリシー | 8文字以上・英大文字/小文字/数字/記号のうち3種以上・メールアドレスと同一禁止 |
| JWT 署名 | **HS256**（共通鍵・環境変数 `JWT_SECRET` で管理） |
| JWT クレーム | `sub`（ユーザーID）/ `jti`（UUID v4・単一デバイスログアウト用）/ `roles`（プラットフォームロール）/ `exp` / `iat` / `iss` の6クレームのみ。個人情報は含めない |
| Access Token 無効化 | 単一ログアウト → JTI ブラックリスト / 全デバイス・パスワード変更・凍結等 → `user_invalidated_at:{user_id}`（iat との比較）。TTL 900秒で自動回収 |
| Access Token 有効期限 | 15分 |
| Refresh Token 有効期限 | 7日（remember_me=true: 30日） |
| ログイン失敗制限 | 同一IP: 1分10回・5回失敗でアカウント30分ロック（Redis 管理） |
| パスワードリセット | 同一IP: 1分3回まで |
| TOTP 秘密鍵暗号化 | AES-256（`AES_ENCRYPTION_KEY` 環境変数）|
| TOTP 再利用防止 | 検証成功時に `totp_used:{user_id}:{code}` を Redis へ登録（TTL 90秒）。同一コードの再送信を拒否 |
| 2FA 完全ロックアウト回復 | `mfa_recovery_tokens` を使ったメールベース回復。有効期限1時間・24時間3回制限。回復後は `is_enabled=false` に強制リセットし、2FA 再設定を促す |
| TOTP クロックスキュー | サーバー・デバイス間の時刻ずれを考慮し ±1ウィンドウ（±30秒、計3ウィンドウ）を許容 |
| WebAuthn リプレイ攻撃対策 | `sign_count` が前回値以下なら認証拒否 |
| デバイスバインディング | Refresh Token 使用時に device_fingerprint 不一致 → 警告 + 再認証 |
| 2FA 必須 | SYSTEM_ADMIN・ADMIN は 2FA 有効化を必須化。未設定のまま管理操作 → 2FA 設定画面へリダイレクト。**WebAuthn 登録済みであれば TOTP の代替として 2FA 要件を満たす** |
| WebAuthn による 2FA スキップ | WebAuthn ログイン成功時は TOTP 入力を免除。パスワードログインで WebAuthn を使わなかった場合は TOTP が引き続き必要 |
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
V1.008__create_email_change_tokens_table.sql
V1.009__create_oauth_link_tokens_table.sql
V1.010__create_mfa_recovery_tokens_table.sql
V1.011__seed_system_admin_user.sql
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
| 2026-02-19 | `PATCH /users/me/password`（パスワード変更API）を追加。API仕様・パスワード変更フロー・他デバイスセッション失効方針を定義 |
| 2026-02-19 | `PATCH /users/me/email` + `POST /users/me/email/confirm`（メールアドレス変更2段階フロー）を追加。`email_change_tokens` テーブル新設。旧アドレスへの通知・全セッション失効・競合チェックを定義 |
| 2026-02-19 | OAuth衝突方針を定義。`oauth_link_tokens` テーブル新設。同一メール既存アカウントとの自動連携を禁止し、統合確認メール → 承認フロー（`POST /auth/oauth/link/confirm`）を追加。`oauth_accounts` 備考の矛盾を修正 |
| 2026-02-19 | メール送信フィードバックUIルール（FRONTEND_CODING_CONVENTION §13）に対応する参照注記を各APIに追加 |
| 2026-02-19 | WebAuthn ログインフロー・2FA優先順位を追加。WebAuthn 成功時は TOTP をスキップする方針を定義。セキュリティ考慮事項を更新 |
| 2026-02-19 | JWT ペイロード仕様を追加。標準クレーム（sub/jti/roles/exp/iat/iss）を定義。Refresh Token は opaque token（非JWT）と明記。Redis ブラックリストのキー形式・TTL戦略を定義 |
| 2026-02-19 | Access Token 無効化戦略を整理。全デバイスログアウト等は `user_invalidated_at:{user_id}` + iat 比較方式に統一。単一デバイスログアウトのみ JTI ブラックリストを使用。各フローの記述を一括修正 |
| 2026-02-19 | TOTP コード検証フローを追加。`totp_used:{user_id}:{code}`（TTL 90秒）で再利用防止。±1ウィンドウのクロックスキュー許容を明記 |
| 2026-02-19 | OAuth アカウント管理API（`GET /users/me/oauth`・`DELETE /users/me/oauth/{provider}`）を追加。連携解除時のログイン手段残存チェック（409）を定義 |
| 2026-02-19 | WebAuthn デバイス管理API（`GET /auth/webauthn/credentials`・`PATCH /auth/webauthn/credentials/{id}`）を追加。既存の DELETE と合わせてデバイス管理を完結 |
| 2026-02-19 | 退会申請キャンセルAPI（`POST /users/me/withdrawal/cancel`）を追加。猶予期間中の再ログイン許可・`pending_deletion_until` レスポンスフィールド・キャンセルフローを定義 |
| 2026-02-19 | 2FA完全ロックアウト回復フローを追加。`mfa_recovery_tokens` テーブル新設。メールベース回復（1時間有効・24時間3回制限）・回復後の2FA強制リセット・ADMIN向け再設定強制を定義 |
| 2026-02-19 | `refresh_tokens.last_used_at` カラムを追加。ローテーション時に更新し、セッション一覧の「最終アクティブ」に直接使用。`GET /auth/sessions` の備考の矛盾を解消 |
