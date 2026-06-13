# セキュリティ横断設計（docs/security/）

> **ステータス**: 🟢 設計確定
> **実装フェーズ**: Security Hardening Phase 1
> **最終更新**: 2026-06-12
> **関連ドキュメント**: F01.1 認証, F01.2-04 セキュリティ運用, F03.5-04 セキュリティ運用, F10.3 監査ログ, F12.3 GDPR, F12.4 セッション管理, docs/architecture/db_scalability.md

---

## 1. 概要

本ディレクトリは **インフラ / 設定レイヤーの横断的セキュリティ方針** を集約する。

機能ごとのセキュリティ考慮事項（認可チェック・プライバシー・監査イベント定義など）は従来どおり各機能ドキュメント（`features/Fxx.y/04_security_operations.md` 等）に記載し、本ディレクトリでは **複数機能をまたぐ設定・基盤レベルの方針**（認可の既定値・Cookie/セッション・セキュリティヘッダー/CSP・依存関係管理・インジェクション横断ルール）を扱う。

### スコープの線引き

| ここで扱う（横断） | features/ で扱う（機能別） |
|---|---|
| SecurityFilterChain の既定認可方針 | 各 API の所有権/ロールチェック |
| Cookie/セッションの属性ポリシー | 認証フローそのもの（F01.1） |
| CSP・セキュリティヘッダー | 各画面の XSS 個別対策 |
| 依存関係の脆弱性管理 | — |
| インジェクション横断ルール | 各機能の入力バリデーション仕様 |

### なぜ作ったか

セキュリティ調査（2026-05-26）で、認証・認可・XSS 対策は高水準に実装されている一方、**インフラ/設定レイヤーの方針が文書化されておらず、本番前に塞ぐべき穴**（`anyRequest().permitAll()` フォールバック、Cookie `secure` ハードコード、CSP/セキュリティヘッダー未設定、Dependabot 未導入）が残っていることが判明した。これらを設計として明文化し、エンドポイント追加時や本番移行時の判断基準を残すために本ディレクトリを新設した。

---

## 2. 脅威モデル（OWASP Top 10:2021 対応表）

| OWASP カテゴリ | 主な対策 | 文書 |
|---|---|---|
| A01 アクセス制御の不備 | deny-by-default 認可 / ロール・権限モデル（SYSTEM_ADMIN を JWT・per-scope は SpEL ガード）/ `AbstractTenantAwareRepository` によるテナント絞り込み / IDOR 防止（`*` 1 階層厳格パターン） 🟢 **Phase 1〜3 実装済み（#1266・2026-06-02 点火）** | [01](01_authorization_baseline.md), [03 ロール・権限](03_role_authority_model.md), F01.2-04, F03.5-04 |
| A02 暗号化の失敗 | TLS/HSTS / AES-256-GCM（`birth_date` 等）/ JWT HMAC-SHA256 / 機密は環境変数 | [03](03_security_headers_and_csp.md) §TLS, [§6 本書](#6-シークレット管理ポリシー現状記録), F01.1 |
| A03 インジェクション | JPA パラメータバインディング / DOMPurify / CSP | [05](05_injection_and_input_validation.md) |
| A04 安全が考慮されない設計 | レートリミット（公開 API/ログイン）/ 退会時匿名化 / モジュラーモノリスのドメイン境界。レートリミット Valkey 化 全 18 フィルタ完了（🟢 #1470/#1471/#1472・2026-06-12）/ ビジネスロジック設計書は [06](06_business_logic_and_abuse_prevention.md) に整備済み | F01.2-04, F19.1, CLAUDE.md, [06](06_business_logic_and_abuse_prevention.md) |
| A05 セキュリティの設定ミス | CSP / セキュリティヘッダー / CORS / devtools 本番無効化 / Actuator は SYSTEM_ADMIN 限定 | [03](03_security_headers_and_csp.md) |
| A06 脆弱で古いコンポーネント | Dependabot / OWASP Dependency-Check / npm audit | [04](04_dependency_and_supply_chain.md) |
| A07 識別と認証の失敗 | BCrypt(12) / アカウントロック / トークンローテーション / HttpOnly+Secure+SameSite Cookie | [02](02_cookie_and_session.md), F01.1, F12.4 |
| A08 ソフトウェアとデータの整合性の不備 | webhook 認証: Stripe = 署名検証実装済 / SES = SQS 内部認証方式へ移行（F09.6 Phase 8a・HTTP 受け口廃止・署名検証不要化）/ LINE = `X-Line-Signature` HMAC-SHA256 🟢 **実装済み（フラグ段階導入・Phase 8b）** / incoming = パストークン DB 照合。内部トークン / SRI は今後検討 | [01](01_authorization_baseline.md) §webhook, [04](04_dependency_and_supply_chain.md) |
| A09 ログとモニタリングの不備 | 監査ログ基盤 / セキュリティスキャン状態表示 | F10.3 |
| A10 SSRF | 外部 URL はサーバーから fetch しない方針（表示用のみ） | [05](05_injection_and_input_validation.md), F01.2-04 |

---

## 3. 文書一覧とステータス

| 文書 | 扱う範囲 | ステータス |
|---|---|---|
| [01_authorization_baseline.md](01_authorization_baseline.md) | 認可の既定値・公開エンドポイント許可リスト・webhook・WebSocket 二層認証 | 🟢 設計確定 |
| [02_cookie_and_session.md](02_cookie_and_session.md) | Cookie 属性・セッション無効化・access_token roles claim・Valkey Fail-Open 方針 | 🟢 設計確定 |
| [03_role_authority_model.md](03_role_authority_model.md) | **ロール・権限モデル（認可基盤完全根治）**。JWT への SYSTEM_ADMIN 搭載・per-scope SpEL ガード・`@PreAuthorize` カタログ・段階計画 Phase 0〜6 | 🟢 Phase 0〜5 実装済み（ShiftPdf 負論理等の根治 #1263・点火 #1266・統合テスト 11 シナリオ含む。2026-06-02）。Phase 6（性能最適化）のみ未着手 |
| [03_security_headers_and_csp.md](03_security_headers_and_csp.md) | CSP・セキュリティヘッダー・CORS・TLS | 🟢 設計確定 |
| [04_dependency_and_supply_chain.md](04_dependency_and_supply_chain.md) | Dependabot・脆弱性管理。npm high 脆弱性 11 件未解消（2026-06-02） | 🟢 設計確定 |
| [05_injection_and_input_validation.md](05_injection_and_input_validation.md) | SQL/XSS/入力検証・マスアサインメント・ログインジェクション | 🟢 設計確定 |
| [06_business_logic_and_abuse_prevention.md](06_business_logic_and_abuse_prevention.md) | ビジネスロジック攻撃・レートリミット統一戦略・JWT Refresh Token 競合制御 | 🟡 設計確定（レートリミット Valkey 化は全 18 フィルタ＋共通基盤 実装済み #1470/1471/1472/1474・2026-06-12）|
| [07_file_and_storage_security.md](07_file_and_storage_security.md) | Presigned URL ライフサイクル・MIME 検証・TTL 上限強制・大容量ファイル暗号化方針 | 🟡 設計確定（実装未着手）|
| [08_incident_response.md](08_incident_response.md) | インシデント対応フロー・GDPR Article 33 通知・SecurityIncidentLog エンティティ設計 | 🟡 設計確定（手順整備未完了）|
| [09_key_management_and_rotation.md](09_key_management_and_rotation.md) | 秘密鍵全リスト・ローテーション手順（JWT/AES/HMAC）・Valkey 冗長化要件 | 🟡 設計確定（手順整備未完了）|

### 機能別セキュリティ文書（参照）

| 文書 | 内容 |
|---|---|
| `features/F01.1_auth.md` | 認証基盤（JWT・2FA・WebAuthn・OAuth・パスワードポリシー） |
| `features/F01.2_org_team_member_role/04_security_operations.md` | RBAC・URL スキーム検証・SSRF 回避・プロフィール可視性 |
| `features/F03.5_shift/04_security_operations.md` | シフトの IDOR 防止・PDF 情報露出制御 |
| `features/F10.3_audit_logs.md` | 監査ログ・イベントカタログ |
| `features/F12.3_gdpr_personal_data.md` | GDPR・個人情報削除 |
| `features/F12.4_session_management.md` | セッション一覧・無効化・新規デバイス検知 |

---

## 4. セキュリティ原則（横断）

1. **deny-by-default** — 認可は「明示的に許可したものだけ公開、それ以外は認証必須」を既定とする（[01](01_authorization_baseline.md)）
2. **多層防御** — SecurityFilterChain（粗い境界）+ `@PreAuthorize`/Service 層（細かい所有権）の二重ガード。**宣言＝強制を単一真実源とする**ため `@EnableMethodSecurity` を有効化し `@PreAuthorize` を実効化する（[03](03_role_authority_model.md)）
3. **秘密はコードに置かない** — 全機密は環境変数。`.gitignore` で `.env`/`*.key`/`*.pem` を除外
4. **症状を隠さない** — 障害は根治治療（CLAUDE.md「障害対応の原則」と整合）。認可エラーを握りつぶさない
5. **テナント分離** — `organization_id` 絞り込みを `AbstractTenantAwareRepository` で統一（将来のシャーディング前提）

---

## 5. 本番移行チェックリスト（セキュリティ関連）

本番デプロイ時に必須の環境変数・確認項目。詳細は `docs/operations/PRODUCTION_DEPLOY_CHECKLIST.md` に統合する。

- [ ] `MANNSCHAFT_COOKIE_SECURE=true`（[02](02_cookie_and_session.md)）
- [ ] `MANNSCHAFT_JWT_SECRET` を 256bit 以上のランダム値に設定
- [ ] `MANNSCHAFT_ALLOWED_ORIGINS` を本番フロントオリジンに設定（ワイルドカード禁止）
- [ ] `MANNSCHAFT_ENCRYPTION_KEY` / `MANNSCHAFT_HMAC_KEY` / `JOB_QR_SIGNING_SECRET` を設定
- [ ] 認可既定値が `.authenticated()` で動作（webhook 3 系統 Stripe/LINE/incoming が無認証到達することを確認。SES は SQS 移行で HTTP 受け口なし）
- [ ] SES 通知 SQS: `SES_NOTIFICATION_QUEUE_NAME` を設定し、SES Identity/Configuration Set の Bounce/Complaint 通知先が SNS Topic に結線されていることを確認（F09.6 Phase 8a）
- [ ] CSP 違反がブラウザコンソールに出ないことを主要画面で確認
- [ ] HTTPS/HSTS が有効（アプリ層 or Cloudflare/LB 層、[03](03_security_headers_and_csp.md) §TLS）

---

## 6. シークレット管理ポリシー（現状記録）

全ての機密情報は **環境変数経由で注入** し、コード・リポジトリにハードコードしない。`application.yml` のデフォルト値は開発用ダミーのみ、`application-prod.yml` はデフォルト値を持たず未設定なら起動失敗（fail-fast）。

### `.gitignore` 除外（確認済み）
`.env` / `secrets.yml` / `credentials.yml` / `*.key` / `*.pem`

### 主なセキュリティ関連環境変数

| 変数 | 用途 | 本番必須 |
|---|---|---|
| `MANNSCHAFT_JWT_SECRET` | JWT 署名鍵（HMAC-SHA256, 256bit+） | ✅ |
| `MANNSCHAFT_COOKIE_SECURE` | Cookie の secure 属性（本番 true）※本 Phase で新設 | ✅ |
| `MANNSCHAFT_ENCRYPTION_KEY` | データ暗号化（AES-256-GCM） | ✅ |
| `MANNSCHAFT_HMAC_KEY` | 整合性 HMAC | ✅ |
| `MANNSCHAFT_ALLOWED_ORIGINS` | CORS 許可オリジン | ✅ |
| `MANNSCHAFT_R2_ACCESS_KEY` / `_SECRET_KEY` | R2 認証 | ✅ |
| `JOB_QR_SIGNING_SECRET` | チェックイン QR 署名（256bit+） | ✅ |
| `MANNSCHAFT_AD_UNSUBSCRIBE_SECRET` / `_AD_OPEN_PIXEL_SECRET` | 広告 unsubscribe/開封ピクセル署名 | ✅ |
| `INTERNAL_LOG_TOKEN` | `/api/internal/ssr-logs` 内部認証 | ✅ |
| `MANNSCHAFT_*_CLIENT_SECRET`（Google/LINE/Apple） | OAuth | OAuth 利用時 |

> 鍵ローテーション手順は機能別ドキュメントを参照（例: `docs/operations/weather_api_key_rotation.md`）。JWT 秘密鍵のローテーションは全 access_token 失効を伴うため計画的に行う。

---

## 7. 今後の拡張（スコープ外・意思決定済み）

本 Phase（Security Hardening Phase 1）では下記を意図的にスコープ外とした。いずれも「未決の設計課題」ではなく「次フェーズへ送ると決定した拡張」である。

- **script-src の `'unsafe-inline'` 完全排除（nonce 化完了）** — PrimeVue の対応状況に依存。[03](03_security_headers_and_csp.md) §4 ロードマップ Phase 2
- **refresh_token の Cookie 発行一元化** — F01.1 デュアルモード設計との整合確認後。[02](02_cookie_and_session.md) §6
- **Argon2id 移行 / Subresource Integrity (SRI) / CSP reportUri 収集** — 将来の認証・配信基盤改修時に評価

---

## 8. 実装待機（本番前必須・優先対応項目）

セキュリティ精査（2026-06-02）で判明した未実装・未解消の重大ギャップ。本番稼働前に全て対処すること。

| ID | 内容 | 深刻度 | 設計書 | 状態 |
|---|---|---|---|---|
| C-1 | 認可基盤根治（Phase 1〜5）— JWT roles 固定・`@EnableMethodSecurity` 未有効・97 個の `@PreAuthorize` が no-op | **Critical** | [03](03_role_authority_model.md) | 🟢 **Phase 0〜5 根治済み（前哨修正 #1263＋点火 #1266・2026-06-02。ShiftPdf 負論理根治・統合テスト 11 シナリオ含む）**。残: Phase 6（性能最適化） |
| 🔴 C-2 | npm high 脆弱性 × 11 件未解消（serialize-javascript RCE 等）。CI は `continue-on-error: true` で通過中 | **Critical** | [04](04_dependency_and_supply_chain.md) | ⏳ 未着手（解消 PR を別途準備中） |
| C-3 | CSP 違反レポート受信エンドポイント未実装（`POST /api/v1/security/csp-reports`）— CSP を設定しても違反を検知できない | **High** | [03 CSP](03_security_headers_and_csp.md) | 🟢 **実装済み（#1274・`CspReportController` + SecurityConfig permitAll）**。違反の可視化（F12.5 連携）は別途 |

> ※C-1 の裏取り根拠: `SecurityConfig.java` に `@EnableMethodSecurity(prePostEnabled = true)` が付与済みであることを grep で確認（2026-06-12）。また `git log --oneline --grep="#1266"` で `feat(authz): @EnableMethodSecurity 有効化 — 認可基盤根治 Phase 3 点火 (#1266)` コミットを確認。

---

## 9. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-12 | **ステータス追従**: 認可基盤根治 Phase 1〜3 完了（#1266・2026-06-02 点火）・レートリミット Valkey 化 全 18 フィルタ完了（#1470/1471/1472・2026-06-12）を反映。OWASP A01/A04 の未着手警告を根治済みに更新。文書一覧の 03 ステータス更新。§8 実装待機テーブルに「状態」列を追加し C-1 を根治済みに更新。実機コード裏取りにより ShiftPdf 負論理(#1263)・統合テスト 11 シナリオ(#1266)・CSP レポート受信 EP(#1274 `CspReportController`)も完了済みと確認し、C-1 の残課題が Phase 6（性能最適化）のみであること・C-3 実装済みを反映。 |
| 2026-06-02 | セキュリティ精査ギャップ反映。文書一覧に 06〜09 を追加。OWASP A01 に未着手警告・A04 にビジネスロジック設計書参照を追加。§8「実装待機」セクションを新設（C-1/C-2/C-3 の 3 件を明示）。 |
| 2026-05-26 | 新規作成（Security Hardening Phase 1）。横断セキュリティ設計を集約 |
| 2026-05-30 | [03 ロール・権限モデル](03_role_authority_model.md) を新設（認可基盤完全根治）。文書一覧・OWASP A01・原則 2（多層防御）に参照を追加 |
| 2026-06-02 | 文書一覧に 06〜09（ビジネスロジック攻撃防止・ファイルストレージセキュリティ・インシデント対応・キー管理）を追加。既存 01〜03 の説明文を更新（WebSocket 二層認証・Valkey Fail-Open・Phase 6 性能最適化反映） |
