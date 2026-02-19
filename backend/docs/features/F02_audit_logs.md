# F02: 監査ログ (Audit Logs)

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 1
> **最終更新**: 2026-02-19

---

## 1. 概要

プラットフォーム上で発生したセキュリティ上重要な操作・イベントを記録する監査ログ基盤。
不正アクセスの検出・インシデント調査・コンプライアンス対応を目的とする。
全機能から参照される横断的インフラであり、独立した feature doc として管理する。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全ユーザーのログ参照・エクスポート |
| ADMIN | 自組織・チームのメンバーに関するログ参照（Phase 3 以降）|
| MEMBER 以下 | 自分自身のログのみ参照（Phase 3 以降）|

### 対象レベル
- [x] 組織 (Organization)
- [x] チーム (Team)
- [x] 個人 (Personal)

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `audit_logs` | 全イベントの監査ログ | なし（イミュータブル）|

### テーブル定義

#### `audit_logs`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | YES | NULL | 操作を行ったユーザー（FK → users SET NULL on delete）。システムバッチの場合は NULL |
| `event_type` | VARCHAR(100) | NO | — | イベント種別（下記カタログ参照）|
| `ip_address` | VARCHAR(45) | YES | NULL | 操作時のIPアドレス（IPv6対応）。バッチ処理は NULL |
| `user_agent` | VARCHAR(500) | YES | NULL | 操作時の User-Agent。バッチ処理は NULL |
| `metadata` | JSON | YES | NULL | イベント固有の補足情報（例: 旧メールアドレス・変更前後の値）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_al_user_id (user_id)
INDEX idx_al_event_type (event_type)
INDEX idx_al_created_at (created_at)           -- 保持期限バッチ・時系列クエリ用
INDEX idx_al_user_event (user_id, event_type)  -- ユーザー別イベント絞り込み用
```

**制約・備考**
- `updated_at` は持たない（監査ログはイミュータブル。UPDATE / DELETE 禁止）
- `user_id` は `SET NULL`（退会後も記録を残す。metadata に email 等を退避する場合がある）
- `metadata` には個人情報を最小限にとどめる（必要な識別子のみ）

---

### イベントタイプカタログ

#### 認証・セッション (F01)
| event_type | トリガー | metadata 例 |
|-----------|---------|------------|
| `LOGIN_SUCCESS` | パスワードログイン成功 | `{}` |
| `LOGIN_FAILED` | パスワード認証失敗 | `{"reason": "INVALID_PASSWORD"}` |
| `WEBAUTHN_LOGIN` | WebAuthn ログイン成功 | `{"credential_id": "..."}` |
| `WEBAUTHN_LOGIN_FAILED` | WebAuthn 署名検証失敗 | `{"reason": "INVALID_SIGNATURE"}` |
| `WEBAUTHN_CREDENTIAL_REMOVED` | WebAuthn デバイス登録解除 | `{"device_name": "iPhone 15"}` |
| `LOGOUT` | ログアウト | `{}` |
| `LOGOUT_ALL_SESSIONS` | 全デバイスログアウト | `{}` |
| `TOKEN_REUSE_DETECTED` | Refresh Token の不正再利用検出 | `{"revoked_token_hash": "..."}` |

#### アカウント管理 (F01)
| event_type | トリガー | metadata 例 |
|-----------|---------|------------|
| `PASSWORD_CHANGED` | パスワード変更 | `{}` |
| `EMAIL_CHANGE_REQUESTED` | メールアドレス変更申請 | `{"new_email": "new@example.com"}` |
| `EMAIL_CHANGED` | メールアドレス変更完了 | `{"old_email": "old@example.com", "new_email": "new@example.com"}` |
| `WITHDRAWAL_REQUESTED` | 退会申請 | `{}` |
| `WITHDRAWAL_CANCELLED` | 退会申請キャンセル | `{}` |
| `PENDING_USER_CLEANED_UP` | PENDING_VERIFICATION ユーザーの自動削除（バッチ）| `{"email": "user@example.com"}` |

#### OAuth 連携 (F01)
| event_type | トリガー | metadata 例 |
|-----------|---------|------------|
| `OAUTH_LINK_REQUESTED` | OAuth 衝突時の統合確認メール送信 | `{"provider": "GOOGLE"}` |
| `OAUTH_LINKED` | OAuth 連携完了 | `{"provider": "GOOGLE"}` |
| `OAUTH_UNLINKED` | OAuth 連携解除 | `{"provider": "GOOGLE"}` |

#### 2FA (F01)
| event_type | トリガー | metadata 例 |
|-----------|---------|------------|
| `MFA_ENABLED` | TOTP 有効化完了 | `{}` |
| `MFA_DISABLED` | TOTP 無効化 | `{}` |
| `MFA_BACKUP_CODES_REGENERATED` | バックアップコード再生成 | `{}` |
| `MFA_RECOVERY_REQUESTED` | 2FA ロックアウト回復メール申請 | `{}` |
| `MFA_RECOVERY_COMPLETED` | 2FA ロックアウト回復完了 | `{}` |

#### 今後追加予定（Phase 拡大に伴い追記）
| event_type | 概要 |
|-----------|------|
| `TEAM_MEMBER_INVITED` | チームへのメンバー招待 |
| `TEAM_MEMBER_ROLE_CHANGED` | チーム内ロール変更 |
| `TEAM_MEMBER_REMOVED` | チームからのメンバー除名 |
| `SYSTEM_ADMIN_ACTION` | SYSTEM_ADMIN による特権操作 |

---

### ER図（テキスト形式）
```
users (1) ──── (N) audit_logs   ※ user_id は SET NULL（退会後もログ保持）
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/admin/audit-logs` | 必要（SYSTEM_ADMIN）| 全ログ一覧（フィルタ・ページング）|
| GET | `/api/v1/users/me/audit-logs` | 必要 | 自分のログ一覧（Phase 3 以降）|

### リクエスト／レスポンス仕様

#### `GET /api/v1/admin/audit-logs`

**クエリパラメータ**
| パラメータ | 型 | 説明 |
|-----------|---|------|
| `user_id` | Long | 特定ユーザーに絞り込み |
| `event_type` | String | 特定イベントに絞り込み |
| `from` | ISO 8601 | 開始日時 |
| `to` | ISO 8601 | 終了日時 |
| `page` | Int | ページ番号（0始まり）|
| `size` | Int | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1001,
      "user_id": 42,
      "event_type": "PASSWORD_CHANGED",
      "ip_address": "203.0.113.1",
      "user_agent": "Mozilla/5.0 ...",
      "metadata": {},
      "created_at": "2026-02-19T10:30:00Z"
    }
  ],
  "meta": {
    "page": 0,
    "size": 20,
    "total_elements": 1234,
    "total_pages": 62
  }
}
```

---

## 5. ビジネスロジック

### 書き込み方針
- 各 Service から `AuditLogService.record(eventType, userId, ipAddress, userAgent, metadata)` を呼び出す
- 書き込みは **非同期（ApplicationEvent）** で行い、メイン処理のレスポンスタイムに影響させない
- 書き込み失敗はログに出力するが、メイン処理を失敗させない（fire-and-forget）

### 参照方針
- SYSTEM_ADMIN のみ全ログを参照可能（Phase 1）
- 将来的に ADMIN が自組織範囲のログを参照できるようにする（Phase 3 以降）

---

## 6. セキュリティ考慮事項

- **イミュータビリティ**: `audit_logs` テーブルへの UPDATE / DELETE は禁止。アプリケーション層で強制する
- **個人情報の最小化**: `metadata` には識別に必要な最小限の情報のみ記録する
- **保持ポリシー**: 1年間 DB で保持。1年超過分はアーカイブ（S3 等）へ移動後 DB から削除（Phase 10 以降のバッチで実装）
- **アクセス制御**: `GET /admin/audit-logs` は SYSTEM_ADMIN ロールを JWT + DB の両方で確認する

---

## 7. Flywayマイグレーション

```
V1.011__create_audit_logs_table.sql
```

**マイグレーション上の注意点**
- `users` テーブルが先に作成されていること（外部キー依存）
- `user_id` の外部キーは `ON DELETE SET NULL` で定義する

---

## 8. 未解決事項

- [ ] `GET /users/me/audit-logs`（自分のログ参照）の実装 Phase を確定する（Phase 3 候補）
- [ ] 1年超過分のアーカイブ先（S3 バケット名・パス設計）を Phase 10 開始前に確定する

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-02-19 | 初版作成。F01_auth.md から分離し独立 feature doc として整備 |
