# 03. セキュリティヘッダーと CSP

> **ステータス**: 🟢 設計確定
> **実装フェーズ**: Security Hardening Phase 1
> **最終更新**: 2026-05-26
> **関連ドキュメント**: [README](README.md), [01](01_authorization_baseline.md), F19.1 公開ページ

---

## 1. 概要

ブラウザ層の防御を担う **CSP（Content-Security-Policy）と各種セキュリティヘッダー** の方針を定義する。フロントエンド（Nuxt）の責務と、バックエンド（Spring Security）の責務を分担する。

### 責務分担
| 層 | 担当ヘッダー | 理由 |
|---|---|---|
| **フロント（Nuxt / nuxt-security）** | CSP, HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Permissions-Policy | HTML を配信する層。CSP は HTML レスポンスに付与してこそ意味がある |
| **バックエンド（Spring Security `.headers()`）** | X-Content-Type-Options, X-Frame-Options(DENY), Referrer-Policy | API は JSON 応答。CSP は不要だが MIME スニッフィング/クリックジャッキング防止の最小ヘッダーを付与 |

---

## 2. CSP（nuxt-security / nonce ベース）

`nuxt-security` モジュールを導入し、nonce ベースの CSP を適用する。SSR が本番有効（`ssr: false` は CI の Lighthouse 計測時のみ）のため、nuxt-security の nonce 自動付与が機能する。

### 2.1 ディレクティブ表
実コードの外部依存を調査して確定した許可先。

| ディレクティブ | 値 | 理由 |
|---|---|---|
| `default-src` | `'self'` | 既定は自オリジンのみ |
| `script-src` | `'self'` + nonce（段階的に `'unsafe-inline'` 排除） | Nuxt hydration スクリプトに nonce 付与。§4 ロードマップ |
| `style-src` | `'self' 'unsafe-inline' https://fonts.googleapis.com` | PrimeVue/Tailwind の動的インラインスタイル + Google Fonts CSS。`'unsafe-inline'` は当面維持（§4） |
| `font-src` | `'self' https://fonts.gstatic.com data:` | Noto Sans JP フォントファイル |
| `img-src` | `'self' data: blob:` + R2 エンドポイント + CDN Workers ドメイン | アバター・アップロード画像・OGP。R2/CDN は環境変数から動的構成 |
| `connect-src` | `'self'` + `NUXT_PUBLIC_API_BASE` + `https://fonts.googleapis.com https://fonts.gstatic.com` | API 通信 + フォント preconnect |
| `frame-src` | `https://www.google.com` | `PublicMapEmbed.vue` の Google Maps 埋め込み |
| `worker-src` | `'self' blob:` | `@vite-pwa/nuxt` の service worker |
| `manifest-src` | `'self'` | PWA マニフェスト |
| `frame-ancestors` | `'none'` | クリックジャッキング防止（自サイトの iframe 埋め込み禁止） |
| `base-uri` | `'self'` | `<base>` タグ injection 防止 |
| `form-action` | `'self'` | フォーム送信先を自オリジンに限定 |

> R2 エンドポイント（`*.r2.cloudflarestorage.com`）と CDN Workers ドメイン（`MANNSCHAFT_CDN_WORKERS_DOMAIN`）は環境ごとに異なるため、CSP 文字列を環境変数から構成する。`@nuxt/image` の最適化経路も img-src に含まれることを確認する。

### 2.2 レポートのみモードでの段階導入
本番適用前に `Content-Security-Policy-Report-Only` で観測し、正規利用で違反が出ないことを確認してから強制モードへ切り替えることを推奨する（nuxt-security の `reportOnly` オプション）。

---

## 3. その他のセキュリティヘッダー

| ヘッダー | 値 | 目的 |
|---|---|---|
| `Strict-Transport-Security` | `max-age=15768000; includeSubDomains`（HTTPS 環境のみ） | HTTPS 強制（§5） |
| `X-Frame-Options` | `DENY` | クリックジャッキング（CSP frame-ancestors と二重） |
| `X-Content-Type-Options` | `nosniff` | MIME スニッフィング防止 |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | リファラ漏洩抑制 |
| `Permissions-Policy` | 未使用機能を無効化（camera/microphone/geolocation 等は必要に応じ許可） | ブラウザ機能の最小権限 |

### バックエンド（Spring Security）
`SecurityConfig` の `.headers(...)` に以下を追加（API JSON 応答向けの最小限）:
```
.headers(h -> h
    .frameOptions(f -> f.deny())
    .contentTypeOptions(Customizer.withDefaults())
    .referrerPolicy(r -> r.policy(STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
```

---

## 4. script-src の nonce 化ロードマップ

PrimeVue/Tailwind は動的インラインスタイルを多用するため、**style-src の `'unsafe-inline'` は当面維持**する。script-src は nuxt-security の nonce で `'unsafe-inline'` を排除することを目標とするが、サードパーティ（PrimeVue 等）のインラインスクリプト有無を実機検証してから確定する。

1. **Phase 1（本 Phase）**: nonce ベース CSP を導入。script-src は nonce + 必要最小限。style-src は `'unsafe-inline'` 許容。Report-Only で観測
2. **Phase 2（将来）**: style-src の nonce/hash 化を検討（PrimeVue の対応状況次第）

---

## 5. TLS / HTTPS 強制（方針確定）

- 本番は全通信 HTTPS。HSTS で HTTP アクセスを抑止
- **強制レイヤー（確定）**: HTTP→HTTPS リダイレクトと HSTS の**一次責務は Cloudflare/LB エッジ層**に置く（証明書管理・終端をエッジに集約）。加えて**アプリ層（nuxt-security）でも HSTS ヘッダーを付与**して多層化する。アプリ層の HSTS は HTTPS 経由でのみ有効化する
- `Secure` Cookie（[02](02_cookie_and_session.md)）と整合

---

## 6. CORS

`CorsConfig` で管理。維持する方針。
- 許可オリジン: `mannschaft.allowed-origins`（`MANNSCHAFT_ALLOWED_ORIGINS`）。**ワイルドカード禁止**、本番フロントオリジンを明示
- メソッド: GET/POST/PUT/PATCH/DELETE/OPTIONS
- `allowCredentials=true`（HttpOnly Cookie 送信のため）
- プリフライトキャッシュ 1 時間

---

## 7. Permissions-Policy ベースライン（方針確定）

最小権限を既定とし、未使用機能は無効化する。利用機能のみ自オリジンに許可する。
- 既定で無効化: `camera=()`, `microphone=()`, `payment=()`, `usb=()`, `magnetometer=()`, `gyroscope=()` 等
- 利用機能を許可: `geolocation=(self)`（位置情報を使う画面がある場合）等、実機の利用箇所を棚卸しして許可リストを確定する（実装時に PWA/位置情報/カメラ利用ページを grep して反映）

---

## 8. 今後の拡張（スコープ外・意思決定済み）

- **script-src の `'unsafe-inline'` 完全排除**: §4 ロードマップ Phase 2 で実施（PrimeVue の nonce/hash 対応状況に依存するため Phase 1 ではスコープ外と決定）
- **CSP `reportUri` による違反レポート収集**: F12.5 エラー追跡基盤への統合は将来検討（Phase 1 では Report-Only モードでの観測に留める）

---

## 9. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-05-26 | 新規作成。nuxt-security による nonce CSP・各ヘッダー・責務分担を定義 |
| 2026-05-26 | フロント実装（`feature/security-fe-csp`）。`nuxt-security@2.6.0` 導入、`frontend/nuxt.config.ts` に `security: {...}` を追加。nonce 有効・CSRF/rateLimiter/xssValidator/corsHandler 等は無効化（API 防御はバックエンド責務）。Permissions-Policy は実コード棚卸し結果（geolocation/camera/screen-wake-lock/publickey-credentials-get/web-share/fullscreen を `self` 許可、その他無効化）を反映。devtools を本番無効化。実機 CSP 検証（PrimeVue ダイアログ・Google Maps 埋め込み・画像表示・PWA SW 登録）は残課題 |
