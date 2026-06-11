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
| `connect-src` | `'self'` + `NUXT_PUBLIC_API_BASE`（空文字時は含めない） + `https://fonts.googleapis.com https://fonts.gstatic.com` + `ws: wss:` | API 通信 + フォント preconnect + WebSocket。本番（NUXT_PUBLIC_API_BASE=''）時は 'self' で足りるため apiBase は含めない |
| `frame-src` | `https://www.google.com` | `PublicMapEmbed.vue` の Google Maps 埋め込み |
| `worker-src` | `'self' blob:` | `@vite-pwa/nuxt` の service worker |
| `manifest-src` | `'self'` | PWA マニフェスト |
| `frame-ancestors` | `'none'` | クリックジャッキング防止（自サイトの iframe 埋め込み禁止） |
| `base-uri` | `'self'` | `<base>` タグ injection 防止 |
| `form-action` | `'self'` | フォーム送信先を自オリジンに限定 |
| `report-uri` | `/api/v1/security/csp-reports`（`NUXT_PUBLIC_CSP_REPORT_URI` で差替可） | CSP 違反レポート送信先。**相対パス固定**（本番 FE/BE 同一オリジン）。受信 EP 実装済み（PR #1274）。dev は nitro.devProxy で :8080 へ転送（§4.1）|

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
- **受信エンドポイント（実装済み・PR #1274）**: `POST /api/v1/security/csp-reports`
  （`com.mannschaft.app.cspreport`）。`SecurityConfig` で `permitAll`（ブラウザ自動送信のため認証不要）、
  Spring Security の CSRF は本アプリではステートレス無効のため弾かれない。`application/json` /
  `application/csp-report` 双方を受理し、`{"csp-report":{...}}` ラッパーあり・なし両形式をパースして
  `csp_reports` テーブルに記録、常に 204 を返す（パース失敗は WARN ログのみで握り潰さず記録）。

#### report-uri の値は「相対パス固定」とし、dev は devProxy で根治（2026-06-03）

`report-uri` は **常に相対パス** `/api/v1/security/csp-reports` とする。本番は FE/BE 同一オリジンの
ため相対のままブラウザが正しく送信する。一方 **dev は FE(:3000) と BE(:8080) がオリジン分離**して
いるため、相対のままだとブラウザが :3000 起点で解決し `POST http://localhost:3000/api/v1/security/csp-reports`
が **404**（Nitro に該当ルートが無い）になる。

- **採用方式: nitro.devProxy（dev 限定転送）**。`nuxt.config.ts` の `nitro.devProxy` で
  `/api/v1/security/csp-reports` を `${NUXT_PUBLIC_API_BASE}/api/v1/security/csp-reports`（既定 :8080）へ
  サーバーサイドでフォワードする。`devProxy` は `nuxi dev` 時のみ適用され**本番には一切影響しない**ため、
  report-uri は単一の相対値のまま dev/本番で同一挙動を保てる。
- **絶対URL化（`NUXT_PUBLIC_CSP_REPORT_URI` を `http://localhost:8080/...`）は採らない**。
  report-uri をクロスオリジン絶対URLにすると、本番の同一オリジン挙動と乖離し、FE の通常 API が
  CORS 制約下にある中で report-uri だけ別経路になる二重管理になるため。**①絶対URL化と②devProxy は
  同目的の代替であり、本番挙動に揃う②のみを採用して冗長な二重対応を避ける**。
- devProxy は **CSP 受信 EP のみに限定**し、FE の通常 API 呼び出し（`useApi` の :8080 絶対URL）には
  干渉しない。E2E 全 API プロキシ（`NUXT_API_PROXY=true`）時は `routeRules` 側が `/api/v1/**` 全体を
  担うため、二重化回避のため devProxy 側は無効化する。
- 検証（2026-06-03）: dev サーバ（worktree :3200）に対し `POST /api/v1/security/csp-reports` が
  **204** で返ること（= :8080 へ転送・受理）を実機確認。devProxy はサーバーサイド転送のため
  ブラウザ CORS の対象外で成立する。

#### 観測された実違反（2026-06-03 / Playwright 実機捕捉）

ログイン→ダッシュボード（`/dashboard`）読み込み時に `securitypolicyviolation` を 1 件捕捉した。

| 項目 | 値 |
|---|---|
| `violatedDirective` / `effectiveDirective` | `script-src` |
| `blockedURI` | `eval` |
| `sourceFile` | `vuedraggable.js`（`new Function("return this")()`）|
| `lineNumber` | 5302（dev 最適化キャッシュ。published dist では `vuedraggable.common.js:3098` / `vuedraggable.umd.js:3107`）|
| `disposition` | `enforce` |

- **原因**: `vuedraggable@4.1.0` の published dist に含まれる webpack/UMD の globalThis 取得ポリフィル
  `g = g || new Function("return this")();`。`new Function` は CSP の `script-src`（`'unsafe-eval'` 不在）で
  ブロックされ違反レポートが発火する。**dev 専用の Vite/HMR 由来ではなく、ライブラリ dist 由来の実違反**
  であり、`module` フィールド経由で本番バンドルにも入りうる。
- **機能影響: なし（根治不要）**。当該コードは `try { g = new Function(...) } catch (e) { if (typeof window === 'object') g = window }`
  の try/catch で囲まれており、`new Function` がブロックされても catch 節が `g = window` にフォールバック
  するため globalThis は正しく解決され、ドラッグ並べ替え機能は正常動作する（実機でダッシュボード描画・
  操作に支障なし）。違反レポートが 1 件記録されるのみ。
- **方針**: CSP を弱める対応（`script-src` に `'unsafe-eval'` を追加する等）は**行わない**。
  `'unsafe-eval'` 付与は eval/Function を全面解禁し XSS 面を大きく広げる実質的な弱体化であり、
  本違反は機能無害なため不要。`report-uri` の収集対象としてこの 1 件が継続記録されることを許容する
  （ノイズ低減が必要になった場合は将来 vuedraggable のフォーク/差し替え、または Report-Only での
  ディレクティブ別運用を検討）。

- 強制モード（`contentSecurityPolicyReportOnly: false`）は維持する。Report-Only への切替は
  §2.2 のとおり観測が必要になった場合に行う。

#### apiBase 二層化（NUXT_PUBLIC_API_BASE vs NUXT_INTERNAL_API_BASE）

本番は Cloudflare 経由の FE/BE 同一オリジン構成のため、ブラウザ用 API ベース URL は `NUXT_PUBLIC_API_BASE=''`（空文字・相対パス）で運用する。このとき `'self'` で API 通信がカバーされるため connect-src への apiBase 追加は不要（空文字が混入しないよう `nuxt.config.ts` でガード済み）。

一方、Nitro サーバーサイド（SSR / server plugins）は相対パスではバックエンドに到達できない。そのため **サーバー専用の絶対 URL を `internalApiBase`（`NUXT_INTERNAL_API_BASE`）として別途管理**する。

| 環境変数 | スコープ | 用途 | 本番推奨値 |
|---|---|---|---|
| `NUXT_PUBLIC_API_BASE` | ブラウザ + サーバー（public） | ブラウザからの API 通信 | `''`（同一オリジン・相対パス） |
| `NUXT_INTERNAL_API_BASE` | サーバーサイドのみ（非 public） | Nitro server plugins（`ssr-error-logger.ts` 等）からの BE 呼び出し | `http://backend:8080`（コンテナ内部名等） |

`server/plugins/ssr-error-logger.ts` は `config.internalApiBase` を参照する（`config.public.apiBase` は参照しない）。

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
- **CSP 違反レポート収集（report-uri 受信エンドポイント）**: §4.1 のとおり `report-uri` ディレクティブ設定済み・受信エンドポイント（`POST /api/v1/security/csp-reports`）実装済み（PR #1274）・dev 転送（nitro.devProxy）も対応済み（2026-06-03）。残: F12.5 エラー追跡基盤への統合（収集済みレポートの可視化・集計）は将来別途起票

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
| 2026-06-11 | apiBase 二層化（§4.1 末尾追記）。`NUXT_PUBLIC_API_BASE=''`（本番同一オリジン相対パス）と `NUXT_INTERNAL_API_BASE`（Nitro サーバー用絶対 URL）を分離。connect-src の空文字ガード対応（§2.1 connect-src 更新）。`ssr-error-logger.ts` が `config.internalApiBase` を参照するよう変更 |
