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
| **refresh_token** | クライアントが保持し、リクエスト時に Cookie で送る（`@CookieValue`）。サーバーは DB に **SHA-256 ハッシュ** を保存 | 7 日。リプレイ検出は `AuthTokenRotationService` |

> **整合に関する注記**: 現状、access_token はサーバーが Cookie として明示発行するが、refresh_token はサーバー側で `ResponseCookie` として明示発行していない（読み取りのみ）。refresh_token も HttpOnly+Secure+SameSite Cookie として発行・削除を一元管理するのが望ましいが、F01.1 のデュアルモード（Web/モバイル）設計との整合が必要なため、本 Phase では **access_token Cookie の属性統一を確実に行う** ことを最小スコープとし、refresh_token Cookie の発行一元化は §6 未解決事項として追跡する。

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
| パスワードハッシュ | BCrypt strength 12（本番）/ 8（ローカル） | `AuthConfig` |
| ログインレートリミット | 1 分窓・最大試行数 | `AuthService`（Valkey） |
| アカウントロック | 5 回失敗で 30 分ロック（HTTP 423） | `AuthService` / F01.1 |

> Argon2id への移行は将来検討。現状 BCrypt(12) は OWASP 許容範囲。

---

## 6. 今後の拡張（スコープ外・意思決定済み）

- **refresh_token の Cookie 発行一元化**: サーバーが refresh_token も `ResponseCookie` として明示発行・削除する一元管理は望ましいが、F01.1 の Web/モバイル デュアルモード設計との整合確認が必要なため、本 Phase ではスコープ外と決定。本 Phase は access_token Cookie の属性統一（`MANNSCHAFT_COOKIE_SECURE` + 発行/削除の属性一致）を確実に行う
- **Argon2id への移行**: BCrypt(12) は OWASP 許容範囲のため現状維持と決定。移行はコスト/互換性評価の上、将来の認証基盤改修時に検討

---

## 7. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-05-26 | 新規作成。`MANNSCHAFT_COOKIE_SECURE` 環境変数化と Cookie 属性統一を定義 |
