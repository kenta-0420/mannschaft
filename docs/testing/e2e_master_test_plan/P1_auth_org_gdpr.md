# P1 認証・組織・権限・個人情報基盤 E2E テスト法案

> 対象: F01.1 / F01.2 / F00 / F00.5 / F01.9 / F12.3 / F12.4 / F02.1
> 凡例・テスト層は [README](./README.md) 参照。判定は実ファイル裏取り済み。

---

## 1. トレーサビリティ監査サマリ

### F01.1 認証・ユーザー管理（`docs/features/F01.1_auth.md`）
ほぼ全機能が 🟢 実在（3 層検証済）。

| 機能要素 | BE | FE | 判定 |
|---|---|---|---|
| 登録 / メール認証 / ログイン / 2FA 検証 | AuthRegistrationService / AuthLoginController / Auth2faController | register.vue / login.vue / 2fa-verify.vue | 🟢 |
| セッション一覧・デバイス別/全ログアウト・ログイン履歴 | AuthSessionService | SettingsLoginHistorySection.vue | 🟢 |
| WebAuthn 登録/一覧/削除/ログイン | AuthWebAuthnController | SecurityWebAuthnSection.vue | 🟢 |
| パスワードリセット | AuthPasswordResetService | パスワード忘却ページ | 🟢 |
| 退会申請 / 退会キャンセル | UserController | 設定画面 / 警告バナー | 🟢 / 🟡 |
| **パスワード変更** | UserController.changePassword() | **FE 画面要確認** | 🟡 |
| **メールアドレス変更** | UserController.changeEmail() | **FE 要確認** | 🟡 |

### F01.2 組織・チーム・メンバー・ロール（`docs/features/F01.2_org_team_member_role.md` 他）
チーム/組織の作成・メンバー・ロール・招待・権限グループは 🟢。

| 機能要素 | 判定 | 備考 |
|---|---|---|
| チーム/組織 作成・詳細・メンバー一覧・ロール変更 | 🟢 | |
| 招待トークン発行・プレビュー(未認証可)・参加 | 🟢 | |
| 権限グループ管理・MEMBER への割当 | 🟢 | UI E2E は要追加 |
| Supporter 自己登録(フォロー) | 🟢 | |
| **招待 QR コード生成** | 🟡 | `GET /invite/{token}/qr` BE 実装済、**FE 表示画面なし** |
| **ブロック管理** | 🟡 | API のみ |

### F00 ContentVisibilityResolver（`docs/features/F00_content_visibility_resolver.md`）
内部機構として 🟢 実装済（IF/抽象基底/StandardVisibility ラダー/未認証 fail-closed）。UI は持たないがコンテンツ可視性の心臓部。E2E は「可視性が正しく効くか」を各コンテンツ越しに検証。

### F01.9 年齢確認・保護者同意（`docs/features/F01.9_age_verification_parental_consent.md`）
**地図隊は「未実装」と誤判定したが、実際は全 Wave 完了済み**（PR #924/926/927）。

| 機能要素 | 判定 | 備考 |
|---|---|---|
| 登録時 birth_date 必須・18歳未満判定→PENDING_PARENTAL_CONSENT | 🟢 | |
| 保護者招待 送信/一覧/取消、承認済み保護者一覧 | 🟢 | pending.vue / manage.vue |
| 保護者同意 承認/否認(トークン・認証不要) | 🟢 | approve.vue |
| 18歳到達 自動解放バッチ(02:00) / 期限切れクリーンアップ(03:30) | 🟢 | ShedLock 二重実行防止 |
| **保護者リンク解除(保護者側)** | 🟡 | `DELETE /parental-consent/children/{id}` BE 実装済、**FE なし** |
| **年齢区分設定 Admin 画面** | 🔴 | `PUT /admin/age-group-settings` BE 実装済、**FE 未実装**（※設計上 Phase1 では許容） |

---

## 2. E2E 実機シナリオ（抜粋・トレーサビリティ付き）

### F01.1 認証
- **[F01.1-E01]** メール+PW 登録→メール認証→（18歳以上 ACTIVE / 未満 PENDING_PARENTAL_CONSENT）。`birth_date` 暗号化保存・トークン used_at 確認。（トレース: §4 register/verify-email, F01.9 §4）
- **[F01.1-E02]** remember_me 付きログイン→`refresh_tokens.expires_at = NOW()+30日`・Cookie `Secure/SameSite=Strict/HttpOnly`・トークンローテーションで `last_used_at` 更新。（§3 refresh_tokens, §4 login/sessions）
- **[F01.1-E03]** 全デバイスログアウト→全 `refresh_tokens.revoked_at` セット・Valkey `user_invalidated_at:{uid}`(TTL900)・最大15分で Access Token 失効。（§4 DELETE /auth/sessions）
- **[F01.1-E04]** WebAuthn 登録→デバイス一覧（public_key/credential_id/sign_count 非公開）→次回ログインで生体認証。（§3 webauthn_credentials, §4）

### F01.2 組織・チーム
- **[F01.2-E01]** チーム作成（作成者自動 ADMIN）→招待トークン発行→被招待者プレビュー→参加(MEMBER)→ロール変更(DEPUTY_ADMIN)。`user_roles` 更新確認。（§4 チーム作成/招待/ロール管理）
- **[F01.2-E02]** `visibility=PUBLIC` チーム→未認証で検索・詳細・メンバー一覧参照可（機密項目 joined_at/permission_groups は除外）。（§4 認可ルール・返却フィールド制限）

### F00 可視性
- **[F00-E01]** `SCOPE_AFFILIATED` ブログ投稿の可視性判定 3 パターン（所属=true / 未所属=false / 未認証=false）。バッチ判定 `filterAccessible()` で SQL 数 ≦2(NF-1)。（§4.5-4.6）

### F01.9 保護者同意
- **[F01.9-E01]** 未成年(15歳)登録→メール認証で `isMinor=true`→PENDING_PARENTAL_CONSENT→保護者招待(`expires_at=+7日`)→保護者承認→子 ACTIVE。（§6.1/6.2, DB §3.3）
- **[F01.9-E02]** 保護者否認→`status=REJECTED`→他に APPROVED 親が居なければ子 `deleted_at` 論理削除→30日後マスキング。（§6.2）
- **[F01.9-E03]** 18歳到達 自動解放バッチ→`birth_date` 復号・`isMinor=false`→`status=REVOKED`+通知。（§6.4, DDL V11.167）

---

## 3. このフェーズの「設計にあるが UI/導線が無い」確定

| 機能 | 状態 | 根拠 | 影響 |
|---|---|---|---|
| F01.9 年齢区分設定 Admin 画面 | 🔴 | AgeGroupSettingsController あり / FE なし | 管理者が DB 直接操作依存（※Phase1 許容と設計注記） |
| F01.9 保護者リンク解除(保護者側) | 🟡 | removeChildLink() あり / FE なし | 保護者が監護解除を画面でできない |
| F01.2 招待 QR コード表示 | 🟡 | getQrCode()(ZXing) あり / FE なし | オフライン招待(印刷配布)不可 |
| F01.1 パスワード変更 UI | 🟡 | changePassword() あり / FE 要確認 | 設定画面でPW変更できない懸念 |

---

## 4. 既存 E2E spec ギャップ

- ✅ カバー済: AUTH-001〜010(login/register/2FA/WebAuthn)、PC-001〜025(parental-consent 全網羅)
- 🟡 未/薄: F00 Resolver の E2E 化（ユニットは厚い）、F00.5 `memberships` 移行後リグレッション、F01.2 権限グループ割当 UI、F01.9 年齢区分設定 Admin

## 5. 論点（要マスター判断）
- **F00.5 移行期**: `user_roles` と `memberships` 併存。E2E が両テーブル参照の可能性 → 移行完了タイミングと整合性テストの要否。
- **F12.3 GDPR**: 退会の二段モデル（即時弱匿名化／30日強匿名化, CLAUDE.md §13.12）の E2E は P1 では未深掘り。退会→匿名化→PII 消去の cascade 検証を別途設計要。
