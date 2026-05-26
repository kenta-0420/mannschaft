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
| `report-uri` | `/api/v1/security/csp-reports`（`NUXT_PUBLIC_CSP_REPORT_URI` で差替可） | CSP 違反レポート送信先。受信エンドポイントは未実装（§4.1）|

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

### 4.0 style-src の `'unsafe-inline'` 排除可否 — 実地検証の結論（2026-05-26）

**結論: 現状 nonce 化は不可。`'unsafe-inline'` を維持する（Phase 2 へ繰り越し）。**

`feature/security-fe-csp-refine` で style-src を nonce ベース（`'nonce-{{nonce}}'`、`'unsafe-inline'` 排除）へ切り替える可否をコードレベルで実地検証した。結果は以下のとおり。

- **PrimeVue 4.5.4 はテーマ/コンポーネント CSS をクライアント実行時に動的注入する。**
  `@primevue/core/usestyle` の `useStyle()` が `document.createElement('style')` →
  `document.head.appendChild()` でハイドレーション後に `<style>` を注入する
  （`node_modules/@primevue/core/usestyle/index.mjs`）。
- **その `<style>` の nonce は PrimeVue 静的設定 `csp.nonce` 由来であり、nuxt-security が
  リクエストごとに発番するランダム nonce とは構造的に一致し得ない。** `useStyle` は
  `options.nonce` を受け付けるが、その値は PrimeVue の app レベル設定（ビルド時固定）であって、
  SSR レスポンスごとに変わる per-request nonce をクライアント実行時へ橋渡しする仕組みが
  当スタック（nuxt-security + @primevue/nuxt-module 4.x / PrimeVue 4.5.4）に存在しない。

> 検証時の依存実バージョン: PrimeVue `4.5.4`、nuxt-security `1.4.3`（`package-lock.json` 実体。
> `package.json` の宣言は `^2.6.0` だが現行 main のロックは 1.4.3 にピンされている。
> 本結論は nuxt-security のバージョンに依存しない（根本原因は PrimeVue のクライアント実行時注入）。
- したがって style-src を nonce 化すると、PrimeVue が実行時注入する `<style>` が CSP 違反で
  ブロックされ **UI が崩壊する**。Tailwind の `@nuxtjs/tailwindcss` および Vue scoped style も
  SSR インライン化されるため同様の懸念がある。
- `'strict-dynamic'` は script にのみ作用し、style には効かないため救済にならない。

**判断**: 障害対応の原則（症状を作らない＝根治治療）に従い、`'unsafe-inline'` を**維持**する。
無理に排除して PrimeVue スタイルを壊すことはしない。排除は **Phase 2** で PrimeVue 側の
per-request nonce 対応（SSR nonce をクライアント `csp.nonce` へ伝播する公式機構）が
整った時点で再評価する。

> 補足: `script-src` は既に nonce + `'strict-dynamic'` で `'unsafe-inline'` を排除済み
> （`nuxt.config.ts` の `script-src`）。スクリプト側は Nuxt の SSR が nonce を自動付与するため成立する。
> 問題は **style 側のみ**、かつ **PrimeVue のクライアント実行時注入**に起因する。

### 4.1 CSP 違反レポート（report-uri / report-to）

CSP 違反を収集できるよう、`report-uri` ディレクティブを追加した（`nuxt.config.ts` の CSP `report-uri`）。

- **採用: `report-uri`**（既定値 `/api/v1/security/csp-reports`、`NUXT_PUBLIC_CSP_REPORT_URI` で差し替え可）。
  非推奨化されつつあるが、ブラウザ互換が最も広く、companion ヘッダー不要で単体機能するため採用。
- **見送り: `report-to`**（後継仕様）。`report-to` は CSP ディレクティブ単体では機能せず、
  別途 `Reporting-Endpoints`（または旧 `Report-To`）レスポンスヘッダーで endpoint グループを
  定義する必要がある。**nuxt-security はこのヘッダーを自動出力しない**ため、本 Phase では
  見送る。将来導入する場合は Nitro プラグイン/route rules で当該ヘッダーを付与する。
- **スコープ**: 本 Phase はディレクティブ追加と方針確定に留める。**バックエンドの受信
  エンドポイント（`POST /api/v1/security/csp-reports`、`Content-Type: application/csp-report`/
  `application/reports+json`）は未実装**。未実装の間はブラウザの違反レポート送信が 404 になるだけで、
  **CSP 強制（強制モード）自体は正常に機能する**。受信エンドポイントの実装は F12.5 エラー追跡基盤
  への統合として別途起票する（過剰実装回避のため本 Phase ではスコープ外）。
- 強制モード（`contentSecurityPolicyReportOnly: false`）は維持する。Report-Only への切替は
  §2.2 のとおり観測が必要になった場合に行う。

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
- **CSP 違反レポート収集（report-uri 受信エンドポイント）**: §4.1 のとおり `report-uri` ディレクティブは設定済み。バックエンドの受信エンドポイント（`POST /api/v1/security/csp-reports`）実装と F12.5 エラー追跡基盤への統合は将来別途起票（本 Phase はディレクティブ追加と方針確定まで）

### 8.1 SRI（Subresource Integrity）— 現状 N/A（実地検証の結論 2026-05-26）

**結論: 外部リソースへの SRI 適用は現状 N/A（適用対象なし）。コード変更不要。**

- **外部から読み込む JS は存在しない。** 全 JS は Nuxt/Vite がバンドルした自オリジン資産であり、
  外部 CDN からの `<script src>` 読み込みは無い。
- **外部 CSS は Google Fonts（`https://fonts.googleapis.com/css2?...`）のみ。** Google Fonts の
  CSS はブラウザ/プラットフォームごとに `@font-face` の内容が動的に出し分けられるため、固定の
  SRI ハッシュを付けると配信内容が変わった瞬間に**読み込みが壊れる**。Google 自身も Fonts CSS への
  SRI 付与を非推奨としている。よって Google Fonts には SRI を適用しない。
- **自オリジンのバンドル資産には nuxt-security が既に SRI を自動付与している。**
  nuxt-security の `sri` オプションは既定 `true` で、SSR の `render:html` フックにて
  バンドル済みローカル `<script src>` / `<link rel=stylesheet|preload|modulepreload>` に
  `integrity` 属性を自動付与する（`subresourceIntegrity` プラグイン。ハッシュは
  バンドル資産から算出される）。外部 URL はハッシュ辞書に無いため対象外で、Google Fonts は
  そのまま素通しされる（壊れない）。
- **将来、セルフホストしない外部スクリプト（解析タグ・ウィジェット等）を追加する場合は、
  その時点で個別に SRI ハッシュ付与を検討する。** 現状は該当なし。

---

## 9. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-05-26 | 新規作成。nuxt-security による nonce CSP・各ヘッダー・責務分担を定義 |
| 2026-05-26 | フロント実装（`feature/security-fe-csp`）。`nuxt-security@2.6.0` 導入、`frontend/nuxt.config.ts` に `security: {...}` を追加。nonce 有効・CSRF/rateLimiter/xssValidator/corsHandler 等は無効化（API 防御はバックエンド責務）。Permissions-Policy は実コード棚卸し結果（geolocation/camera/screen-wake-lock/publickey-credentials-get/web-share/fullscreen を `self` 許可、その他無効化）を反映。devtools を本番無効化。実機 CSP 検証（PrimeVue ダイアログ・Google Maps 埋め込み・画像表示・PWA SW 登録）は残課題 |
| 2026-05-26 | CSP 精緻化（`feature/security-fe-csp-refine`）。①style-src の nonce 化可否を実地検証し「現状不可（PrimeVue 4.5.4 のクライアント実行時 `useStyle` 注入 vs nuxt-security の per-request nonce 不一致）」と結論、`'unsafe-inline'` 維持を確定（§4.0）。②CSP `report-uri` を追加（`/api/v1/security/csp-reports`、受信 EP は未実装＝別途起票、§4.1）。`report-to` は companion ヘッダー不在のため見送り。③SRI は外部 JS なし・Google Fonts は SRI 非推奨で現状 N/A、自オリジンバンドルは nuxt-security `sri:true` が自動付与済みと整理（§8.1） |
