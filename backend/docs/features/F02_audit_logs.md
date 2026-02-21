# F02: 監査ログ (Audit Logs)

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 1
> **最終更新**: 2026-02-21 (F05イベント追記)

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
| `target_user_id` | BIGINT UNSIGNED | YES | NULL | 操作の対象ユーザー（FK → users SET NULL on delete）。管理者操作（凍結・ロール変更等）またはバッチ処理が特定ユーザーに作用した場合に設定（例: USER_ARCHIVED）。自身への操作・全体バッチは NULL |
| `team_id` | BIGINT UNSIGNED | YES | NULL | 操作が行われたチームコンテキスト（FK → teams SET NULL on delete）。チームスコープ外の操作は NULL |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | 操作が行われた組織コンテキスト（FK → organizations SET NULL on delete）。組織スコープ外の操作は NULL |
| `event_type` | VARCHAR(100) | NO | — | イベント種別（下記カタログ参照）|
| `ip_address` | VARCHAR(45) | YES | NULL | 操作時のIPアドレス（IPv6対応）。バッチ処理は NULL |
| `user_agent` | VARCHAR(500) | YES | NULL | 操作時の User-Agent。バッチ処理は NULL |
| `metadata` | JSON | YES | NULL | イベント固有の補足情報（例: 旧メールアドレス・変更前後の値・バッチジョブ名）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_al_user_id (user_id)
INDEX idx_al_target_user_id (target_user_id)
INDEX idx_al_team_id (team_id)
INDEX idx_al_organization_id (organization_id)
INDEX idx_al_event_type (event_type)
INDEX idx_al_created_at (created_at)                      -- 保持期限バッチ・時系列クエリ用
INDEX idx_al_user_event (user_id, event_type)             -- ユーザー別イベント絞り込み用
INDEX idx_al_team_created (team_id, created_at)           -- Phase 3: ADMIN の組織ログ参照用
INDEX idx_al_org_created (organization_id, created_at)    -- Phase 3: ADMIN の組織ログ参照用
```

**制約・備考**
- `updated_at` は持たない（監査ログはイミュータブル。UPDATE / DELETE 禁止）
- `user_id` / `target_user_id` / `team_id` / `organization_id` はすべて `SET NULL`（退会・削除後も記録を残す）
- `metadata` には個人情報を最小限にとどめる（必要な識別子のみ）
- バッチ処理の識別は `metadata` の `batch_job_name` キーで管理する（専用カラム不要）

---

### イベントタイプカタログ

#### 認証・セッション (F01)
| event_type | トリガー | metadata 例 |
|-----------|---------|------------|
| `LOGIN_SUCCESS` | パスワードログイン成功 | `{}` |
| `LOGIN_FAILED` | パスワード認証失敗 | `{"reason": "INVALID_PASSWORD"}` |
| `WEBAUTHN_LOGIN` | WebAuthn ログイン成功 | `{"credential_id": "..."}` |
| `WEBAUTHN_LOGIN_FAILED` | WebAuthn 署名検証失敗 | `{"reason": "INVALID_SIGNATURE"}` |
| `WEBAUTHN_CREDENTIAL_REGISTERED` | WebAuthn デバイス登録完了 | `{"device_name": "iPhone 15"}` |
| `WEBAUTHN_CREDENTIAL_REMOVED` | WebAuthn デバイス登録解除 | `{"device_name": "iPhone 15"}` |
| `LOGOUT` | ログアウト | `{}` |
| `LOGOUT_ALL_SESSIONS` | 全デバイスログアウト | `{}` |
| `TOKEN_REUSE_DETECTED` | Refresh Token の不正再利用検出 | `{"revoked_token_hash": "..."}` |

#### アカウント登録・認証 (F01)
| event_type | トリガー | metadata 例 |
|-----------|---------|------------|
| `USER_REGISTERED` | メール+パスワードによる新規ユーザー登録 | `{}` |
| `OAUTH_USER_REGISTERED` | OAuth 経由の新規ユーザー登録 | `{"provider": "GOOGLE"}` |
| `EMAIL_VERIFIED` | メールアドレス認証完了 | `{}` |
| `PASSWORD_RESET_REQUESTED` | パスワードリセット申請 | `{}` |
| `PASSWORD_RESET_COMPLETED` | パスワードリセット完了 | `{}` |
| `ACCOUNT_LOCKED` | ログイン失敗5回到達によるアカウントロック発動。`LOGIN_FAILED` と同タイミングで記録 | `{"reason": "BRUTE_FORCE", "unlock_at": "2026-02-21T10:30:00Z"}` |
| `ACCOUNT_UNLOCKED` | 管理者によるアカウントロック手動解除（`reason=ADMIN_ACTION` 固定）。Redis TTL 自然失効による自動解除はイベントを記録しない | `{"reason": "ADMIN_ACTION"}` |

#### アカウント管理 (F01)
| event_type | トリガー | metadata 例 |
|-----------|---------|------------|
| `PASSWORD_CHANGED` | パスワード変更 | `{}` |
| `EMAIL_CHANGE_REQUESTED` | メールアドレス変更申請 | `{"new_email": "new@example.com"}` |
| `EMAIL_CHANGED` | メールアドレス変更完了 | `{"old_email": "old@example.com", "new_email": "new@example.com"}` |
| `WITHDRAWAL_REQUESTED` | 退会申請 | `{}` |
| `WITHDRAWAL_CANCELLED` | 退会申請キャンセル | `{}` |
| `WITHDRAWAL_COMPLETED` | 退会完了（猶予期間終了・個人情報物理削除） | `{"email_hash": "sha256_hash"}` |
| `PENDING_USER_CLEANED_UP` | PENDING_VERIFICATION ユーザーの自動削除（バッチ）| `{"email_hash": "sha256_of_email", "batch_job_name": "PendingUserCleanupJob"}` |

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
| `MFA_DISABLED` | TOTP 直接無効化（MEMBER 等がオプション 2FA を自分で無効化する場合）。MFA ロックアウト回復経由の無効化は `MFA_RECOVERY_COMPLETED` で記録 | `{}` |
| `MFA_BACKUP_CODES_REGENERATED` | バックアップコード再生成 | `{}` |
| `MFA_RECOVERY_REQUESTED` | 2FA ロックアウト回復メール申請 | `{}` |
| `MFA_RECOVERY_COMPLETED` | 2FA ロックアウト回復完了 | `{}` |

#### SYSTEM_ADMIN による特権操作

> `user_id` = 操作した SYSTEM_ADMIN、`target_user_id` = 対象ユーザー。
> アカウントロックの手動解除は `ACCOUNT_UNLOCKED`（`reason: ADMIN_ACTION`）で統一する。専用イベントは設けない。

| event_type | トリガー | metadata 例 |
|-----------|---------|------------|
| `USER_FROZEN` | アカウント凍結（`users.status` を `FROZEN` に変更） | `{"reason": "TERMS_VIOLATION"}` |
| `USER_UNFROZEN` | 凍結解除（`users.status` を `ACTIVE` に戻す） | `{}` |

#### 非アクティブアーカイブ（バッチ）
| event_type | トリガー | metadata 例 |
|-----------|---------|------------|
| `USER_ARCHIVED` | 非アクティブユーザーの自動アーカイブ | `{"batch_job_name": "InactiveUserArchiveJob"}` |
| `USER_UNARCHIVED` | アーカイブ解除（ログイン復帰） | `{}` |
| `TEAM_ARCHIVED` | 非アクティブチームの自動アーカイブ | `{"batch_job_name": "InactiveTeamArchiveJob"}` |
| `TEAM_UNARCHIVED` | チームアーカイブ解除 | `{}` |
| `ORGANIZATION_ARCHIVED` | 非アクティブ組織の自動アーカイブ | `{"batch_job_name": "InactiveOrganizationArchiveJob"}` |
| `ORGANIZATION_UNARCHIVED` | 組織アーカイブ解除 | `{}` |

#### 今後追加予定（Phase 拡大に伴い追記）
| event_type | 概要 | Phase |
|-----------|------|-------|
| `TEAM_MEMBER_INVITED` | チームへのメンバー招待 | Phase 2 以降 |
| `TEAM_MEMBER_JOINED` | 招待経由でのメンバー参加 | Phase 2 以降 |
| `TEAM_MEMBER_ROLE_CHANGED` | チーム内ロール変更 | Phase 2 以降 |
| `TEAM_MEMBER_REMOVED` | チームからのメンバー除名 | Phase 2 以降 |
| `TEAM_CREATED` | チーム作成 | Phase 2 以降 |
| `TEAM_DELETED` | チーム削除 | Phase 2 以降 |
| `ORGANIZATION_CREATED` | 組織作成 | Phase 2 以降 |
| `ORGANIZATION_DELETED` | 組織削除 | Phase 2 以降 |
| `ORGANIZATION_MEMBER_JOINED` | 招待経由での組織メンバー参加 | Phase 2 以降 |
| `ORGANIZATION_MEMBER_ROLE_CHANGED` | 組織内ロール変更 | Phase 2 以降 |
| `ORGANIZATION_MEMBER_REMOVED` | 組織からのメンバー除名 | Phase 2 以降 |
| `TEAM_MEMBER_BLOCKED` | チームのサポーター自己登録ブロック（メンバーであれば同時除名）| Phase 2 以降 |
| `TEAM_MEMBER_UNBLOCKED` | チームのブロック解除 | Phase 2 以降 |
| `ORGANIZATION_MEMBER_BLOCKED` | 組織のサポーター自己登録ブロック（メンバーであれば同時除名）| Phase 2 以降 |
| `ORGANIZATION_MEMBER_UNBLOCKED` | 組織のブロック解除 | Phase 2 以降 |
| `ORGANIZATION_ORG_TYPE_CHANGED` | 組織種別（NONPROFIT / FORPROFIT）の変更 | Phase 2 以降 |
| `TEAM_MEMBER_PERMISSION_GROUP_ASSIGNED` | DEPUTY_ADMIN / MEMBER への権限グループ割り当て | Phase 2 以降 |
| `PAYMENT_ITEM_CREATED` | 支払い項目（年会費・月謝・アイテム代金等）の作成 | Phase 3 以降 |
| `PAYMENT_COMPLETED` | 支払い完了（Stripe 自動決済 または ADMIN 手動記録）| Phase 3 以降 |
| `PAYMENT_MANUALLY_RECORDED` | ADMIN による手動支払い記録（現金・振込等）| Phase 3 以降 |
| `PAYMENT_REFUNDED` | 払い戻し処理完了（Stripe webhook または ADMIN 操作）| Phase 3 以降 |
| `SCHEDULE_CREATED` | スケジュール作成（単発 / 繰り返し）| Phase 3 以降 |
| `SCHEDULE_UPDATED` | スケジュール更新（update_scope 付き）| Phase 3 以降 |
| `SCHEDULE_CANCELLED` | スケジュールキャンセル（status = CANCELLED）| Phase 3 以降 |
| `SCHEDULE_DELETED` | スケジュール論理削除 | Phase 3 以降 |
| `SCHEDULE_RECURRENCE_EXPANDED` | 繰り返しスケジュールの自動展開バッチ実行 | Phase 3 以降 |

---

### ER図（テキスト形式）
```
users (1) ──── (N) audit_logs   ※ user_id は SET NULL（退会後もログ保持）
users (1) ──── (N) audit_logs   ※ target_user_id は SET NULL（退会後も保持）
teams (1) ──── (N) audit_logs   ※ team_id は SET NULL（削除後も保持）
organizations (1) ──── (N) audit_logs   ※ organization_id は SET NULL（削除後も保持）
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
| `target_user_id` | Long | 特定ユーザーを対象とした操作に絞り込み |
| `team_id` | Long | 特定チームスコープに絞り込み |
| `organization_id` | Long | 特定組織スコープに絞り込み |
| `event_type` | String | 特定イベントに絞り込み |
| `from` | ISO 8601 | 開始日時 |
| `to` | ISO 8601 | 終了日時 |
| `page` | Int | ページ番号（0始まり）|
| `size` | Int | 1ページ件数（最大100）|

**レスポンス（200 OK）**

※ `created_at` の降順（最新順）で返す。

```json
{
  "data": [
    {
      "id": 1001,
      "user_id": 42,
      "target_user_id": null,
      "team_id": null,
      "organization_id": null,
      "event_type": "PASSWORD_CHANGED",
      "ip_address": "203.0.113.1",
      "user_agent": "Mozilla/5.0 ...",
      "metadata": {},
      "created_at": "2026-02-21T10:30:00Z"
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

#### `GET /api/v1/users/me/audit-logs`（Phase 3 以降）

**クエリパラメータ**
| パラメータ | 型 | 説明 |
|-----------|---|------|
| `event_type` | String | 特定イベントに絞り込み |
| `from` | ISO 8601 | 開始日時 |
| `to` | ISO 8601 | 終了日時 |
| `page` | Int | ページ番号（0始まり）|
| `size` | Int | 1ページ件数（最大50）|

**レスポンス（200 OK）**

※ `created_at` の降順（最新順）で返す。

```json
{
  "data": [
    {
      "id": 1001,
      "event_type": "LOGIN_SUCCESS",
      "ip_address": "203.0.113.1",
      "user_agent": "Mozilla/5.0 ...",
      "metadata": {},
      "created_at": "2026-02-21T10:30:00Z"
    }
  ],
  "meta": {
    "page": 0,
    "size": 20,
    "total_elements": 58,
    "total_pages": 3
  }
}
```

> **注意**: `user_id` / `target_user_id` / `team_id` / `organization_id` は自分のログのみなので省略する。`metadata` は個人情報を含む場合があるため、返却フィールドをフロントエンド実装時に精査する。

**エラーレスポンス（両エンドポイント共通）**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 権限不足（SYSTEM_ADMIN 以外が admin エンドポイントへアクセス） |
| 422 | `from` > `to` など不正なパラメータ |
| 429 | レートリミット超過 |

### レートリミット

| エンドポイント | 制限 | 備考 |
|--------------|------|------|
| `GET /admin/audit-logs` | 60 req/min per user | Bucket4j 適用 |
| `GET /users/me/audit-logs` | 30 req/min per user | Bucket4j 適用 |

---

## 5. ビジネスロジック

### 書き込み方針
- 各 Service から `AuditLogService.record(eventType, userId, targetUserId, teamId, organizationId, ipAddress, userAgent, metadata)` を呼び出す
- `targetUserId` / `teamId` / `organizationId` はイベント種別に応じて null 可
- 書き込みは **非同期（ApplicationEvent）** で行い、メイン処理のレスポンスタイムに影響させない
- 書き込み失敗はログに出力するが、メイン処理を失敗させない（fire-and-forget）
- `LOGIN_FAILED` はユーザーが存在し、かつ認証に失敗した場合のみ記録する。存在しないメールアドレスへのログイン試行は記録しない（`user_id` が特定不可・ユーザー列挙情報の漏洩防止）

### 参照方針
- SYSTEM_ADMIN のみ全ログを参照可能（Phase 1）
- 将来的に ADMIN が自組織・チームスコープのログを参照できるようにする（Phase 3 以降）
  - `team_id` または `organization_id` が自 ADMIN のスコープに含まれるレコードのみ返す
- 一般ユーザーは自分の `user_id` に該当するログのみ参照可能（Phase 3 以降）

### データエクスポート
CSV / JSON エクスポート機能は F13（データエクスポート）として別途設計する。
`GET /admin/audit-logs` のクエリパラメータと連携してフィルタ済みデータをエクスポートする想定。

---

## 6. セキュリティ考慮事項

- **イミュータビリティ**: `audit_logs` テーブルへの UPDATE / DELETE は禁止。アプリケーション層で強制する
- **個人情報の最小化**: `metadata` には識別に必要な最小限の情報のみ記録する。メールアドレスが必要な場合は SHA-256 ハッシュ化して記録する（`WITHDRAWAL_COMPLETED` / `PENDING_USER_CLEANED_UP` が対象）
- **保持ポリシー**: 1年間 DB で保持。1年超過分はアーカイブ（S3 等）へ移動後 DB から削除（Phase 10 以降のバッチで実装）
- **アクセス制御**: `GET /admin/audit-logs` は SYSTEM_ADMIN ロールを JWT + DB の両方で確認する
- **レートリミット**: 大量ダウンロードによる情報漏洩リスク対策として Bucket4j でレートリミットを適用する
- **ログの自己参照禁止**: 監査ログの参照操作自体は監査ログに記録しない（無限ループ防止・ノイズ除去）

---

## 7. Flywayマイグレーション

```
V1.011__create_audit_logs_table.sql
```

**マイグレーション上の注意点**
- `users` テーブルが先に作成されていること（`user_id` / `target_user_id` の FK 依存）
- `team_id` / `organization_id` の FK は `teams` / `organizations` テーブルが存在しない Phase 1 時点では張れない。**Phase 1 では INT カラムとして定義し、FK 制約は各テーブル作成後の追加マイグレーションで付与する**
  ```
  V2.xxx__add_audit_logs_fk_team.sql          -- teams テーブル作成マイグレーションの直後
  V2.xxx__add_audit_logs_fk_organization.sql  -- organizations テーブル作成マイグレーションの直後
  ```
- `user_id` / `target_user_id` の外部キーは `ON DELETE SET NULL` で定義する（Phase 1 から適用）

---

## 8. 未解決事項

- [ ] `GET /users/me/audit-logs`（自分のログ参照）の実装 Phase を確定する（Phase 3 候補）
- [ ] `GET /admin/audit-logs` の `metadata` フィールドを全件返すか、一部キーのみ返すかを実装前に確定する（個人情報漏洩リスクの精査）
- [ ] 1年超過分のアーカイブ先（S3 バケット名・パス設計）を Phase 10 開始前に確定する
- [ ] Phase 3 で ADMIN が自組織ログを参照する際、`team_id` / `organization_id` が NULL（プラットフォームレベル操作）のレコードをどう扱うかを確定する
- [ ] `session_id` の記録要否を確定する（セッション単位での行動追跡が必要か）
- ~~`EMAIL_VERIFIED` の audit_logs 記録ステップを F01 メール認証フローに追記する~~ → 対応済み（2026-02-21）

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-02-21 | F05 対応のイベントを「今後追加予定」一覧に追記（`SCHEDULE_CREATED` / `SCHEDULE_UPDATED` / `SCHEDULE_CANCELLED` / `SCHEDULE_DELETED` / `SCHEDULE_RECURRENCE_EXPANDED`）|
| 2026-02-21 | F03・F04 対応のイベントを「今後追加予定」一覧に追記（`ORGANIZATION_ORG_TYPE_CHANGED` / `TEAM_MEMBER_PERMISSION_GROUP_ASSIGNED` / payment 系4件）。`TEAM/ORGANIZATION_MEMBER_BLOCKED` の説明にメンバー同時除名の旨を追記 |
| 2026-02-21 | `target_user_id` 説明にバッチ操作ケースを追記。`ACCOUNT_UNLOCKED` を管理者手動解除のみに限定（Redis TTL 自然失効はイベント対象外と明記）。`MFA_DISABLED` のトリガー条件を明確化。`me/audit-logs` にもソート順を追記。セキュリティ考慮事項の email_hash 対象を両イベントに更新。Flyway に FK 追加タイミング設計（Phase 1 では INT のみ・FK は後続 migration）を追記。`EMAIL_VERIFIED` 未解決事項を対応済みに更新 |
| 2026-02-21 | `target_user_id` 説明の "BAN" を "凍結" に修正。`PENDING_USER_CLEANED_UP` の metadata を email 平文から `email_hash` に変更（`WITHDRAWAL_COMPLETED` との一貫性）。`LOGIN_FAILED` の記録条件（ユーザー存在時のみ）を書き込み方針に明記。GET レスポンスのソート順（`created_at` 降順）を明記。未解決事項の F01 対応済み項目を整理し `EMAIL_VERIFIED` を残課題として明記 |
| 2026-02-21 | `WEBAUTHN_CREDENTIAL_REGISTERED` を追加。`SYSTEM_ADMIN_USER_BANNED/UNBANNED` を DB ステータスに合わせ `USER_FROZEN/UNFROZEN` に改名。`SYSTEM_ADMIN_ACCOUNT_UNLOCKED` を削除（`ACCOUNT_UNLOCKED` + `reason: ADMIN_ACTION` に統合）。`ACCOUNT_LOCKED` の発動タイミングを明記。未解決事項に F01 クロスドキュメント修正項目を追記 |
| 2026-02-21 | DB設計に `target_user_id` / `team_id` / `organization_id` を追加。イベントタイプカタログに F01 漏れ（登録・パスワードリセット・ロック・退会完了・SYSTEM_ADMIN操作・アーカイブ）を補完。API仕様に `me/audit-logs` レスポンス定義・レートリミット・エラーレスポンスを追加。ビジネスロジック・セキュリティ考慮事項を更新 |
| 2026-02-19 | 初版作成。F01_auth.md から分離し独立 feature doc として整備 |
