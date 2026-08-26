# 02. Cookie とセッション

> **ステータス**: 🟢 設計確定
> **実装フェーズ**: Security Hardening Phase 1
> **最終更新**: 2026-05-26
> **関連ドキュメント**: [README](README.md), F01.1 認証, F12.4 セッション管理

---

## 1. 概要

JWT + HttpOnly Cookie によるステートレス認証における **Cookie 属性ポリシー** と、セッション無効化の運用方針を定義する。認証フローそのもの（トークン発行・MFA・OAuth）は F01.1 をマスタとし、本書は **Cookie 属性と運用** に集中する。

`SecurityConfig` は `SessionCreationPolicy.STATELESS` + `csrf().disable()`。これは JWT をトークンとして扱う設計上意図的であり、維持する（CSRF 耐性は `SameSite=Strict` + 認証 Cookie の組み合わせで担保）。

---

## 2. Cookie 属性ポリシー

| 属性 | 値 | 根拠 |
|---|---|---|
| `HttpOnly` | `true` | JavaScript からの読み取りを禁止（XSS によるトークン窃取を防止） |
| `Secure` | **`${MANNSCHAFT_COOKIE_SECURE}`**（本番 `true` / ローカル `false`） | HTTPS 通信時のみ送信。本番で平文送信を防ぐ |
| `SameSite` | `Strict` | クロスサイトからの Cookie 送信を禁止（CSRF 対策） |
| `Path` | `/` | アプリ全体 |
| `Max-Age` | access_token は **890 秒**（JWT 有効期限 900 秒より 10 秒短く設定） | Clock Skew 対策（§2.3）参照 |

### 2.3 Cookie Max-Age の Clock Skew 対策

**Clock Skew 対策**: Cookie の `Max-Age` は JWT 有効期限（`exp` claim）より **10 秒短く設定**する。
クライアントとサーバーの時刻差により、Cookie が先に消えることを保証し、
「Cookie なし → 認証フローへ」が確実に起動するようにする。

設定値: `access_token Cookie Max-Age = 890秒`（JWT 有効期限 900秒 - 10秒）

> 現状の実装（`buildAccessTokenCookie` に `maxAge(900)` が渡されている箇所）は 890 秒に修正すること。refresh_token Cookie の Max-Age は DB トークンの有効期限（`getRefreshTokenExpirationSeconds()`）から 10 秒差し引いた値を設定する。

### 2.1 `MANNSCHAFT_COOKIE_SECURE` の導入（本 Phase）
現状 `AuthLoginController` は `secure(false)` をハードコードしている（開発用）。これを環境変数化する。

- `application.yml`: `mannschaft.cookie.secure: ${MANNSCHAFT_COOKIE_SECURE:false}`
- `application-prod.yml`: `mannschaft.cookie.secure: ${MANNSCHAFT_COOKIE_SECURE:true}`（本番デフォルト true）
- `application-local.yml`: `false`（HTTP のローカル開発）

Controller では `@Value("${mannschaft.cookie.secure:false}")` で注入し、Cookie 発行・削除の両方で使用する。

### 2.2 Cookie 発行・削除の属性統一
**発行** (`buildAccessTokenCookie`) と **削除** (`clearAccessTokenCookie`) で属性を揃える。ブラウザは同名 Cookie でも属性（特に `Secure`/`SameSite`/`Path`）が異なると削除に失敗することがあるため、削除側にも `secure` / `sameSite("Strict")` / `path("/")` を付与する。

```
// 発行
ResponseCookie.from("access_token", token)
    .httpOnly(true).secure(cookieSecure).path("/").sameSite("Strict").maxAge(900)
// 削除（属性を揃え maxAge=0）
ResponseCookie.from("access_token", "")
    .httpOnly(true).secure(cookieSecure).path("/").sameSite("Strict").maxAge(0)
```

---

## 3. access_token と refresh_token の扱い（実装の実態）

| トークン | 保存 | 備考 |
|---|---|---|
| **access_token** | HttpOnly Cookie（本書の属性） | 15 分。ログイン成功・リフレッシュ成功で発行、ログアウトで削除 |
| **refresh_token** | サーバーが HttpOnly+Secure+SameSite Cookie として明示発行（`Set-Cookie`）。body にも返却（モバイル互換）。サーバーは DB に **SHA-256 ハッシュ** を保存 | 7 日（604800 秒、`mannschaft.jwt.refresh-token-expiration`）。リプレイ検出は `AuthTokenRotationService` |

> **整合に関する注記（2026-05-26 更新・実装済み）**: refresh_token も access_token と同様、サーバーが `ResponseCookie` として明示発行・削除する一元管理に移行した（`AuthLoginController#buildRefreshTokenCookie` / `#clearRefreshTokenCookie`）。F01.1 §203 のデュアルモード設計に合わせ、**login/refresh 成功時は Set-Cookie とレスポンスボディの両方**で返し（Web は Cookie・モバイルは body を使用）、**logout で maxAge=0 のクリア Cookie** を返す。Cookie の maxAge は DB トークンの有効期限（`getRefreshTokenExpirationSeconds()`）と一致させる。属性は access_token と統一（HttpOnly / Secure=`mannschaft.cookie.secure` / SameSite=Strict / Path=/）。

### 3.1 access_token の roles claim（実装済み）

access_token（JWT）の `roles` claim は **認可（authority）の起点**である。`JwtAuthenticationFilter` がこの claim を `ROLE_*` authority に変換し、SecurityFilterChain の `hasRole(...)` とメソッド層の `@PreAuthorize` がそれを評価する。

> ※2026-05-30 時点の「現状（病巣）」と「改善（根治後）」の対比表として記録。その後 Phase 1〜3 の実装（#1266・2026-06-02 点火）で根治済み。

| 項目 | 調査時（2026-05-29）の病巣 | 根治後（2026-06-02 実装済み） |
|---|---|---|
| 発行内容 | **全 5 経路（login/2FA/OAuth/WebAuthn/refresh）で `["MEMBER"]` 固定** | 基底 `MEMBER` ＋ SYSTEM_ADMIN ユーザーは `["MEMBER","SYSTEM_ADMIN"]`。発行時に `user_roles` から判定（`existsSystemAdminByUserId`） |
| SYSTEM_ADMIN | 誰の JWT にも載らない → SecurityConfig の `hasRole("SYSTEM_ADMIN")` 4 系統が全員 403（機能不全） | **roles 配列に `"SYSTEM_ADMIN"` を追加**（boolean claim ではない）。フィルタの既存 `ROLE_+role` 変換にそのまま乗り、`hasRole` がコード変更なしで機能 |
| per-scope ロール | — | team/org の ADMIN/DEPUTY_ADMIN は **JWT に載せず**リクエスト毎に DB 判定（マルチテナントでの肥大化回避） |
| 失効 | — | SYSTEM_ADMIN 剥奪時は §4 の全デバイス無効化タイムスタンプを発火し即時失効。最悪でもリフレッシュ（最長 15 分）で再判定 |

> 詳細・段階計画・`@PreAuthorize` カタログは [03 ロール・権限モデル](03_role_authority_model.md) を正典とする。

---

## 4. セッション無効化・ローテーション

- **リフレッシュトークンローテーション**: `AuthTokenRotationService` がリフレッシュ毎にトークンを再発行し、旧トークンに後継ポインタ（`replaced_by_token_hash`）を記録して失効させる。取得は DB 行ロック（`PESSIMISTIC_WRITE`）で直列化（詳細: [06 §7](06_business_logic_and_abuse_prevention.md#7-jwt-refresh-token-ローテーションの競合制御2026-07-02-実装済みに更新)）
- **並行更新の正規化と真リプレイの区別（2026-07-02 実装済み）**: 失効済みトークンの再提示は一律リプレイ扱いにしない。後継ポインタ有り × grace window（既定 60 秒、`mannschaft.jwt.refresh-rotation-grace-seconds`）以内なら「並行更新の負け側」として正規化し新トークンを発行（全デバイス無効化しない）。grace window 超過の後継有りトークン再提示のみを真リプレイとして `AuthSessionService.logoutAllDevices()` で全デバイス無効化する
- **JTI ブラックリスト**: ログアウト時、access_token の JTI を Valkey に残存 TTL 分だけ登録して無効化
- **全デバイス無効化**: ユーザー単位の無効化タイムスタンプを Valkey に保持
- **セッション一覧・個別無効化・新規デバイス検知**: F12.4 を参照

### 4.1 Valkey 障害時の動作方針（Fail-Open vs Fail-Closed）

現在の実装は **Fail-Open** ポリシーを採用している:
- JTI ブラックリスト確認時に Valkey が応答しない場合 → トークンを有効として扱う（アクセス許可）
- 全デバイス無効化タイムスタンプの確認失敗時 → 同様に無効化をスキップ

**理由**: 可用性を優先（ログアウト機能の Valkey 依存を UX トレードオフとして許容）

**本番要件**: Fail-Open を安全に運用するには Valkey の高可用性が前提条件。
本番環境では以下のいずれかを必須とする:
- Valkey Sentinel（自動フェイルオーバー、推奨）
- Valkey Cluster（水平シャーディング、大規模向け）
- AWS ElastiCache 等の Managed Redis（SLA 99.9% 以上）

シングルポイント Valkey での本番稼働は禁止する。詳細は [09 キー管理・ローテーション](09_key_management_and_rotation.md) の Valkey 冗長化要件も参照すること。

#### 4.1.1 実装上の担保 — Valkey 障害が DB トランザクションを汚さないこと（2026-07-02 根治）

Fail-Open は「Valkey が落ちても **DB 側の無効化（refresh_token の revoke）は必ず貫く**」ことが前提である。これを成立させるには、Valkey 操作の例外が呼び出し元の **DB トランザクションを rollback-only にマークしてはならない**。

- `AuthTokenService`（`setUserInvalidationTimestamp` / `addJtiToBlacklist` / `clearUserInvalidationTimestamp` / レートリミット）は **DB を一切触らず Valkey と JWT のみ**を扱うため、**クラスに `@Transactional` を付与しない**（非トランザクショナルに保つ）。
- 過去、誤ってクラスレベル `@Transactional(readOnly = true)` が付いており、これらの Valkey 専用メソッドが Spring プロキシ経由で呼び出し元（例: `AuthSessionService.logoutAllDevices`、`AuthTokenRotationService.refreshAccessToken` の真リプレイ検出経路）の DB トランザクションに参加していた。この状態で Valkey が例外を投げると、内側のトランザクション境界で現在のトランザクションが rollback-only にマークされ、呼び出し元が `try-catch` で例外を握って Fail-Open を意図しても、コミット時に `UnexpectedRollbackException` が発生し **DB revoke ごと巻き戻る**（＝ Fail-Open が実際には fail 側に倒れ、セッション無効化が永続化されない過小無効化）不具合があった。
- 根治として `AuthTokenService` から `@Transactional` を撤去し、Valkey 例外が呼び出し元の `try-catch` へ素通しで届くようにした。これにより Valkey 障害時も DB 側の無効化は確定コミットされる。
- **番人テスト**: `AuthLogoutValkeyFailOpenPersistenceIT`（実 MySQL Testcontainers ＋ Valkey 例外注入）が「Valkey 障害中でも全 refresh_token が `revoked_at NOT NULL` で永続化される」ことを守る。純 Mockito UT はトランザクション境界を踏まないため、この巻き戻りを検知できない（false-green）ことに注意。

---

## 5. ブルートフォース・リスト型攻撃対策

| 対策 | 値 | 実装 |
|---|---|---|
| パスワードハッシュ | **Argon2id**（OWASP 準拠 `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`）。既存ハッシュは生 BCrypt を段階移行 | `AuthConfig`（`DelegatingPasswordEncoder`） |
| ログインレートリミット | 1 分窓・最大 **5 回**（5 回失敗で 30 分ロック、HTTP 423） | `AuthService`（Valkey） |
| パスワードリセット申請レート | 1 分窓・最大 **3 回** | `AuthService`（Valkey） |
| メール認証コード送信レート | 1 分窓・最大 **3 回** | `AuthService`（Valkey） |
| アカウントロック | 5 回失敗で 30 分ロック（HTTP 423） | `AuthService` / F01.1 |

> **Argon2id 段階移行（2026-05-26 実装済み）**: `passwordEncoder` を `DelegatingPasswordEncoder`（既定 ID = `argon2`）に変更。新規エンコード（登録・パスワードリセット・変更）は `{argon2}` プレフィックス付きハッシュを生成する。既存の `{id}` プレフィックスなしの生 BCrypt ハッシュは `setDefaultPasswordEncoderForMatches(BCryptPasswordEncoder)` により BCrypt として検証されるため、**DB の既存ハッシュは無変更**。さらにログイン成功時に `PasswordEncoder#upgradeEncoding` が true なら検証済み平文を Argon2id で再エンコードして保存し、**既存ユーザーはログインのたびに透過的に Argon2id へ移行**する（強制リセット不要・ユーザー影響なし）。`password_hash` 列は当初から `VARCHAR(255)`（V1.001）で Argon2id ハッシュ（~96 文字）を十分格納できるため Flyway migration は不要。

---

## 6. 実装済み（2026-05-26 認証コア強化）

以下は当初スコープ外としていたが、認証コア強化フェーズで実装した。

- **refresh_token の Cookie 発行一元化**: F01.1 §203 のデュアルモード設計に合わせ、login/refresh 成功で `Set-Cookie` + body 両方を返し、logout で maxAge=0 のクリア Cookie を返すよう一元管理した（§3 参照）。ローテーション時は新トークンを Cookie にセット、旧トークンは DB で失効。既存の取得優先順位（Authorization ヘッダー > Cookie）とローテーション/リプレイ検出ロジックは不変。
- **Argon2id への移行**: `DelegatingPasswordEncoder` による段階移行を実装（§5 参照）。既存ハッシュ無変更・ログイン時透過的 upgrade。

---

## 7. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-12 | §3.1 の「現状（病巣）/改善」対比表の列ヘッダーを「調査時（病巣）/根治後（実装済み）」に更新し、Phase 1〜3 根治済み（#1266・2026-06-02 点火）であることを注記 |
| 2026-05-26 | 新規作成。`MANNSCHAFT_COOKIE_SECURE` 環境変数化と Cookie 属性統一を定義 |
| 2026-05-26 | 認証コア強化: Argon2id 段階移行（`DelegatingPasswordEncoder`・ログイン時透過 upgrade）と refresh_token Cookie 発行一元化（デュアルモード）を実装。§3/§5/§6 を実装済みに更新 |
| 2026-05-30 | §3.1 を新設。access_token の roles claim の現状（`["MEMBER"]` 固定）と SYSTEM_ADMIN を roles 配列に載せる改善を追記。詳細は [03](03_role_authority_model.md) を正典として参照 |
| 2026-06-02 | §2 Cookie 属性テーブルの `Max-Age` を 900→890 秒（Clock Skew 対策）に修正。§2.3 Clock Skew 対策セクションを新設（JWT `exp` より 10 秒短く設定する根拠・設定値を明記）。§4.1 Valkey 障害時 Fail-Open 方針を新設（本番 Sentinel/Cluster 必須・シングルポイント禁止）。§5 レートリミットテーブルにパスワードリセット申請（3回/分）・メール認証コード送信（3回/分）の数値を追記 |
| 2026-07-02 | §4.1.1 を新設。`AuthTokenService` のクラスレベル `@Transactional` により Valkey 専用メソッドが呼び出し元 DB トランザクションに参加し、Valkey 例外が rollback-only マーク → コミット時 `UnexpectedRollbackException` でセッション無効化の DB revoke ごと巻き戻る（Fail-Open が fail 側に倒れる）不具合を根治。`AuthTokenService` を非トランザクショナル化。番人 `AuthLogoutValkeyFailOpenPersistenceIT` を追加 |
| 2026-07-02 | §4 を更新: リフレッシュトークンローテーションの競合制御を DB 行ロック（`PESSIMISTIC_WRITE`）+ grace window 方式に変更（F01.1 自爆バグ根治）。失効済みトークン再提示を一律リプレイ扱いにせず、後継ポインタ（`replaced_by_token_hash`）× grace window（既定 60 秒）で並行更新の正規化と真リプレイを区別するよう記述を更新。詳細は [06 §7](06_business_logic_and_abuse_prevention.md#7-jwt-refresh-token-ローテーションの競合制御2026-07-02-実装済みに更新) を正典として参照 |
