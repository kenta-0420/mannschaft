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
| `Max-Age` | access_token は 900 秒（JWT 有効期限と一致） | トークン TTL と Cookie 寿命を揃える |

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

---

## 4. セッション無効化・ローテーション

- **リフレッシュトークンローテーション**: `AuthTokenRotationService` がリフレッシュ毎にトークンを再発行し、旧トークンを失効。失効済みトークンの再利用を検出した場合は全デバイスを無効化（リプレイ攻撃対策）
- **JTI ブラックリスト**: ログアウト時、access_token の JTI を Valkey に残存 TTL 分だけ登録して無効化
- **全デバイス無効化**: ユーザー単位の無効化タイムスタンプを Valkey に保持
- **セッション一覧・個別無効化・新規デバイス検知**: F12.4 を参照

---

## 5. ブルートフォース・リスト型攻撃対策

| 対策 | 値 | 実装 |
|---|---|---|
| パスワードハッシュ | **Argon2id**（OWASP 準拠 `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`）。既存ハッシュは生 BCrypt を段階移行 | `AuthConfig`（`DelegatingPasswordEncoder`） |
| ログインレートリミット | 1 分窓・最大試行数 | `AuthService`（Valkey） |
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
| 2026-05-26 | 新規作成。`MANNSCHAFT_COOKIE_SECURE` 環境変数化と Cookie 属性統一を定義 |
| 2026-05-26 | 認証コア強化: Argon2id 段階移行（`DelegatingPasswordEncoder`・ログイン時透過 upgrade）と refresh_token Cookie 発行一元化（デュアルモード）を実装。§3/§5/§6 を実装済みに更新 |
