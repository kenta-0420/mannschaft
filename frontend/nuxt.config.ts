import Aura from '@primeuix/themes/aura'

// ──────────────────────────────────────────────────────────────────────────
// セキュリティヘッダー / CSP（nuxt-security）
// 設計書: docs/security/03_security_headers_and_csp.md §2.1 / §3 / §7
// ──────────────────────────────────────────────────────────────────────────

// ブラウザ用 API ベース URL。connect-src に追加する必要がある（同一オリジンでない場合）。
// E2E プロキシ（NUXT_API_PROXY=true）時は API が Nuxt 同一オリジン経由になるため 'self' で足りる。
// 本番（Cloudflare 経由 FE/BE 同一オリジン構成）では NUXT_PUBLIC_API_BASE='' (空文字) で運用する。
const apiBase = process.env.NUXT_PUBLIC_API_BASE ?? 'http://localhost:8080'

// CSP 違反レポートの送信先（report-uri）。
// バックエンド受信エンドポイント `POST /api/v1/security/csp-reports`（permitAll・PR #1274）へ向ける。
// 値は **常に相対パス** とする（本番は FE/BE 同一オリジンのため相対で届く）。
//   - 本番: 同一オリジンで素通り。
//   - dev: FE(:3000) と BE(:8080) がオリジン分離しているため、相対のままだと :3000 起点で
//     解決され 404 になる。これを下記 nitro.devProxy（dev 限定）で :8080 へフォワードして根治する。
//     → report-uri は単一の相対値のまま、dev/本番で同一挙動を保てる（絶対URL化は採らない=冗長回避）。
// 環境変数 NUXT_PUBLIC_CSP_REPORT_URI で差し替え可能（既定は同一オリジンの収集エンドポイントパス）。
// 設計書: docs/security/03_security_headers_and_csp.md §4.1 / §8
const cspReportUri = process.env.NUXT_PUBLIC_CSP_REPORT_URI ?? '/api/v1/security/csp-reports'

// connect-src の許可リストを構成する。
// - 'self'（同一オリジン API / SSR エラー転送 / PWA）
// - apiBase（バックエンド API オリジン。プロキシ時も無害なので含める）
// - Google Fonts（preconnect / CSS 取得）
// - ws:/wss:（STOMP WebSocket / dev HMR。apiBase 由来の ws もカバー）
// upgrade-insecure-requests が http:// → https:// に昇格させるため両方を許可する
// apiBase が空文字（本番同一オリジン構成: NUXT_PUBLIC_API_BASE=''）のとき connectSrc に混入しないようガードする
const apiBaseSrc = apiBase
  ? apiBase.startsWith('http://')
    ? [apiBase, apiBase.replace('http://', 'https://')]
    : [apiBase]
  : []

// プロフィールメディアの presigned PUT はブラウザからストレージ（本番:R2 / ローカル:MinIO）へ
// 直接 fetch するため connect-src に許可が必要。無いと CSP で Failed to fetch になりアップロード不能。
// ローカル既定: MinIO http://localhost:9000 / 本番: NUXT_PUBLIC_MEDIA_UPLOAD_ORIGIN に R2 origin を注入。
// 例: NUXT_PUBLIC_MEDIA_UPLOAD_ORIGIN=https://pub-xxxxx.r2.dev（R2 の公開エンドポイント origin）
const mediaUploadOrigin = process.env.NUXT_PUBLIC_MEDIA_UPLOAD_ORIGIN ?? 'http://localhost:9000'
const mediaUploadSrc = mediaUploadOrigin
  ? mediaUploadOrigin.startsWith('http://')
    ? [mediaUploadOrigin, mediaUploadOrigin.replace('http://', 'https://')]
    : [mediaUploadOrigin]
  : []

const connectSrc = [
  "'self'",
  ...apiBaseSrc,
  ...mediaUploadSrc,
  'https://fonts.googleapis.com',
  'https://fonts.gstatic.com',
  // STOMP WebSocket（@stomp/stompjs）と dev サーバ HMR。
  // 本番 API が wss、dev が ws のため双方を許可する。
  'ws:',
  'wss:',
  // F08.9 P5 Stripe.js: SetupIntent confirm / Elements が Stripe API へ XHR/fetch する。
  // 設計書: docs/features/F08.9_membership_billing_paywall/04_ui_i18n.md §2.2 / docs/security/03_security_headers_and_csp.md
  'https://api.stripe.com',
]

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2024-11-01',
  // 本番では Nuxt DevTools を無効化（情報露出・バンドル肥大の抑止）。
  devtools: { enabled: process.env.NODE_ENV !== 'production' },

  app: {
    head: {
      charset: 'utf-8',
      viewport: 'width=device-width, initial-scale=1',
      link: [
        { rel: 'icon', type: 'image/x-icon', href: '/favicon.ico' },
        { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
        { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' },
        {
          rel: 'stylesheet',
          href: 'https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@400;500;700&display=swap',
        },
      ],
    },
    pageTransition: { name: 'page-fade', mode: 'out-in' },
  },
  future: {
    compatibilityVersion: 4,
  },

  components: [{ path: '~/components', pathPrefix: false }],

  imports: {
    dirs: ['composables', 'composables/jobs', 'composables/wallet-group-show', 'composables/match'],
  },

  devServer: {
    // 【根治】'0.0.0.0' は IPv4 のみの bind のため [::]:3000（IPv6 側）が空き、
    // そこを dev サーバー由来の WebSocket が掴んでしまう。Windows の名前解決は
    // localhost → ::1 を優先するため、この状態で http://localhost:3000 を開くと
    // アプリではなく WS サーバーに当たり、恒久的に 426 Upgrade Required が返っていた
    // （2026-07-28 に実機で確認）。
    // '::' はデュアルスタック bind となり、Node/Nuxt が IPv4/IPv6 の両方で 3000 を
    // 直接持つため、localhost / 127.0.0.1 / [::1] のいずれでも 200 になる
    // （2026-08-04 実測で確認。HMR ポート分離だけでは [::]:3000 に別の WS サーバーが
    // 残り根治しなかったため、この host 変更が正しい根治策）。
    host: '::',
  },

  modules: [
    '@nuxtjs/i18n',
    '@primevue/nuxt-module',
    '@nuxtjs/tailwindcss',
    '@pinia/nuxt',
    '@vueuse/nuxt',
    '@nuxt/image',
    '@nuxt/eslint',
    '@vite-pwa/nuxt',
    'nuxt-security',
  ],

  // ──────────────────────────────────────────────────────────────────────
  // nuxt-security: CSP / セキュリティヘッダー
  // 設計書: docs/security/03_security_headers_and_csp.md
  // ──────────────────────────────────────────────────────────────────────
  security: {
    // nonce ベース CSP。SSR レスポンスの <script>/<style> に nonce を自動付与し、
    // CSP の script-src に nonce-{{nonce}} を自動展開する。
    nonce: true,
    // CSRF はバックエンド（JWT + HttpOnly Cookie のステートレス認証）が管理し、
    // Spring Security 側で disable 済み。nuxt-security の CSRF を有効化すると
    // 二重防御で正規リクエストが壊れるため明示的に無効化する。
    csrf: false,
    // 以下はバックエンド API 側の責務 or 本アプリ構成と相性が悪いため無効化。
    // （フロントは HTML 配信に専念し、API 防御はバックエンドに委ねる方針）
    rateLimiter: false,
    requestSizeLimiter: false,
    xssValidator: false,
    corsHandler: false,
    allowedMethodsRestricter: false,
    // removeLoggers（本番で console.* を除去）は既定 true だが、F10.6 の SSR エラー
    // 転送など意図的なログ出力に影響しうるため、本タスクのスコープ外として無効化する。
    removeLoggers: false,
    headers: {
      // COEP は既定で本番 'credentialless' になり、CORP ヘッダーを持たない
      // クロスオリジン埋め込み（Google Maps iframe・外部 https: 画像）を阻害しうる。
      // 設計書は COEP を要求しておらず、埋め込み・画像表示を壊さないため明示的に無効化する。
      crossOriginEmbedderPolicy: false,
      // 自オリジン資源への影響を避けるため CORP は cross-origin 許容に緩める
      // （アバター/画像 CDN・OGP を考慮）。X-Frame-Options/frame-ancestors で
      // クリックジャッキングは別途防御する。
      crossOriginResourcePolicy: 'cross-origin',
      contentSecurityPolicy: {
        'default-src': ["'self'"],
        // script-src: nonce は nuxt-security が自動付与。
        // 'strict-dynamic' により nonce 付きスクリプトがロードする子スクリプトを許可。
        // F08.9 P5: Stripe.js 本体（https://js.stripe.com/v3）の読み込みを許可。
        //   loadStripe は nonce 付き親スクリプトから動的に <script src> を挿入するため
        //   'strict-dynamic' 下でも src 許可リストが効くよう明示する（過剰緩和なし）。
        // 設計書: docs/features/F08.9_membership_billing_paywall/04_ui_i18n.md §2.2
        'script-src': ["'self'", "'nonce-{{nonce}}'", "'strict-dynamic'", 'https://js.stripe.com'],
        // style-src: PrimeVue(Aura)/Tailwind の動的インラインスタイルのため
        // 'unsafe-inline' を当面維持する。
        // 【実地検証の結論（2026-05-26 / feature/security-fe-csp-refine）】
        // nonce 化（'unsafe-inline' 排除）は現状不可。
        //   - PrimeVue 4.5.4 はテーマ/コンポーネントの CSS を `useStyle`
        //     （@primevue/core/usestyle）でクライアント実行時に <style> を
        //     document.head へ動的注入する（createElement('style') + appendChild）。
        //   - その <style> の nonce は PrimeVue 静的設定 `csp.nonce` 由来であり、
        //     nuxt-security がリクエストごとに発番するランダム nonce とは一致し得ない
        //     （SSR の per-request nonce をクライアント実行時設定へ橋渡しする
        //      仕組みが当スタックに存在しない）。
        //   - よって style-src を nonce 化すると PrimeVue 注入スタイルが CSP 違反で
        //     ブロックされ UI が崩壊する。Tailwind/Vue scoped style も同様の懸念。
        //   - 症状を作らない方針（根治治療原則）に基づき 'unsafe-inline' を維持する。
        //   - 排除は Phase 2 で PrimeVue 側の per-request nonce 対応を待って再評価する。
        // 設計書: docs/security/03_security_headers_and_csp.md §4.0 / §4 ロードマップ Phase 2
        'style-src': ["'self'", "'unsafe-inline'", 'https://fonts.googleapis.com'],
        'font-src': ["'self'", 'https://fonts.gstatic.com', 'data:'],
        // img-src: アバター/アップロード画像/OGP。R2/CDN は環境依存のため https: を許容。
        // @nuxt/image の最適化経路（/_ipx/）は 'self' でカバーされる。
        // presigned アップロード後の画像表示元（本番:R2 https / ローカル:MinIO http://localhost:9000）も
        // 許可する。connect-src だけだと直 PUT は通るが <img> 表示が img-src で CSP ブロックされる
        // （本番 R2 は https: で既にカバーされるが、ローカル MinIO は http なので mediaUploadSrc が必要）。
        'img-src': ["'self'", 'data:', 'blob:', 'https:', ...mediaUploadSrc],
        'connect-src': connectSrc,
        // frame-src: PublicMapEmbed.vue の Google Maps 埋め込み。
        // F08.9 P5: Stripe.js の PaymentElement iframe（js.stripe.com）と
        //   3DS 認証チャレンジ iframe（hooks.stripe.com）を許可。
        // 設計書: docs/features/F08.9_membership_billing_paywall/04_ui_i18n.md §2.2
        'frame-src': ['https://www.google.com', 'https://js.stripe.com', 'https://hooks.stripe.com'],
        // worker-src: @vite-pwa/nuxt の service worker。
        'worker-src': ["'self'", 'blob:'],
        'manifest-src': ["'self'"],
        'frame-ancestors': ["'none'"],
        'base-uri': ["'self'"],
        'form-action': ["'self'"],
        // 本番（HTTPS）では有効。dev（HTTP）では BE が HTTP のため false にする。
        'upgrade-insecure-requests': process.env.NODE_ENV === 'production',
        // report-uri: CSP 違反レポートの送信先。将来の収集基盤（F12.5 エラー追跡）へ向ける。
        // report-uri は非推奨化されつつあるが広範なブラウザ互換のため採用する。
        // report-to（後継）は CSP ディレクティブ単体では機能せず、別途
        // `Reporting-Endpoints`/`Report-To` レスポンスヘッダーの付与が必要。
        // nuxt-security はこのヘッダーを自動出力しないため、本 Phase では report-to は
        // 見送り、report-uri のみ設定する（バックエンド受信エンドポイントは別途実装）。
        // 設計書: docs/security/03_security_headers_and_csp.md §4.1
        'report-uri': [cspReportUri],
      },
      // HSTS は HTTPS 経由でのみ有効化される（nuxt-security の既定挙動）。
      // 一次責務はエッジ層（Cloudflare/LB）、アプリ層でも多層化する（設計書 §5）。
      strictTransportSecurity: {
        maxAge: 15768000, // 約 6 ヶ月
        includeSubdomains: true,
      },
      xFrameOptions: 'DENY',
      xContentTypeOptions: 'nosniff',
      referrerPolicy: 'strict-origin-when-cross-origin',
      // Permissions-Policy: 最小権限。利用機能のみ自オリジンに許可する（設計書 §7）。
      // 棚卸し結果（frontend/app 配下の grep）:
      //   geolocation … useGeolocation.ts（求人マッチング）
      //   camera … useQrScanner.ts / BarcodeCapture.vue（QR/バーコード読取）
      //   screen-wake-lock … useWakeLockWithFallback.ts（ウォレット提示画面）
      //   publickey-credentials-get … useBiometricGate.ts（生体認証）
      //   web-share … ActivitySharePanel.vue（ネイティブ共有）
      //   fullscreen … PrimeVue 等の全画面表示
      // 注: Permissions-Policy の自オリジン許可は CSP と異なり引用符なしの
      //     キーワード `self` を用いる（`self` → `geolocation=(self)`）。
      //     引用符付き `'self'` だと `(’self’)` となり仕様上不正なため使わない。
      // vue-tsc の PermissionsPolicyValue 型定義に含まれないキー（bluetooth 等）が
      // あるため Record<string, string[]> でキャストして型エラーを回避する
      permissionsPolicy: {
        geolocation: ['self'],
        camera: ['self'],
        microphone: [], // 未使用 → 無効化
        'screen-wake-lock': ['self'],
        'publickey-credentials-get': ['self'],
        'web-share': ['self'],
        fullscreen: ['self'],
        payment: [], // 未使用 → 無効化
        usb: [], // 未使用 → 無効化
        serial: [], // 未使用 → 無効化
        midi: [], // 未使用 → 無効化
        hid: [], // 未使用 → 無効化
        magnetometer: [], // 未使用 → 無効化
        gyroscope: [], // 未使用 → 無効化
        accelerometer: [], // 未使用 → 無効化
        'idle-detection': [], // 未使用 → 無効化
      } as Record<string, string[]>,
    },
  },

  pwa: {
    registerType: 'autoUpdate',
    manifest: {
      name: 'Mannschaft',
      short_name: 'Mannschaft',
      description: '汎用組織管理プラットフォーム',
      lang: 'ja',
      theme_color: '#3B82F6',
      background_color: '#ffffff',
      display: 'standalone',
      start_url: '/',
      icons: [
        { src: '/icons/icon-192x192.png', sizes: '192x192', type: 'image/png' },
        { src: '/icons/icon-512x512.png', sizes: '512x512', type: 'image/png' },
        {
          src: '/icons/icon-512x512.png',
          sizes: '512x512',
          type: 'image/png',
          purpose: 'maskable',
        },
      ],
      // Androidホーム画面アイコン長押しメニュー（ショートカット）
      shortcuts: [
        {
          name: 'ポイっとメモ',
          short_name: 'メモ',
          description: 'すぐにメモを入力する',
          url: '/quick-memos',
          icons: [{ src: '/icons/icon-192x192.png', sizes: '192x192', type: 'image/png' }],
        },
      ],
    },
    workbox: {
      navigateFallback: '/',
      globPatterns: ['**/*.{js,css,html,png,svg,ico,woff2}'],
      runtimeCaching: [
        // ─────────────────────────────────────────────────────────────────
        // API キャッシュポリシー（セキュリティ上重要）
        //
        // 認証付き API レスポンスを Service Worker キャッシュに残すと、
        // ログアウト後・ユーザー切替後に別ユーザーが旧ユーザーのデータを
        // 閲覧できる情報漏洩リスクが生じる。
        // そのため /api/v1/** のキャッシュは「認証不要な公開 API のみ」に
        // 限定し、それ以外は必ず NetworkOnly（キャッシュ禁止）とする。
        //
        // workbox は先勝ちマッチのため、以下の順序が重要:
        //   1. /api/v1/public/** → NetworkFirst（公開コンテンツ・鮮度優先）
        //   2. /api/v1/recruitment-categories → SWR（静的マスタ・鮮度不要）
        //   3. その他 /api/v1/** → NetworkOnly（セーフガード）
        // ─────────────────────────────────────────────────────────────────

        // 公開コンテンツ API（認証不要）は NetworkFirst — 「鮮度」を最優先する。
        //
        // 【なぜ SWR をやめたか】
        // 旧設定は StaleWhileRevalidate + maxAgeSeconds 86400（24時間）だった。
        // SWR は「キャッシュを即返し、裏で更新する」戦略のため、投稿者が記録を
        // 非公開に戻す / 削除しても、一度でも閲覧した端末では最大 24 時間
        // 古い本文が表示され続けた。「間違って公開したので急いで消した」場合に
        // 投稿者の意思がまったく届かない、という質の悪い不具合になっていた。
        //
        // 【NetworkFirst のコストはゼロ】
        // SWR は workbox-strategies/StaleWhileRevalidate の実装上、キャッシュ
        // ヒットの有無にかかわらず毎回 fetchAndCachePut() を発火する。つまり
        // ネットワーク往復の回数は SWR と NetworkFirst で同一であり、
        // PublicApiRateLimitFilter（未認証 60 req/min/IP）の消費量は変わらない。
        // 変わるのは「レスポンスを画面に渡すのがネットワーク応答後になる」点だけ。
        //
        // 【オフライン体験は維持する】
        // networkTimeoutSeconds: 3 で、圏外・低速回線では 3 秒でキャッシュに
        // フォールバックする。非公開化後に BE が 404 を返す場合、NetworkFirst は
        // その 404 をそのまま画面へ返す（キャッシュ参照はネットワーク「失敗」時のみ）。
        //
        // 【maxAgeSeconds を 24時間 → 1時間 に短縮した理由】
        // NetworkFirst ではこの値は「オフライン時のフォールバック可能期間」だけを
        // 意味する。公開ページのオフライン利用は電車のトンネル・エレベーター等の
        // 一時的な断線が実態なので 1 時間で十分であり、かつ「消したものが見える」
        // 最悪ケースを 24時間 → 1時間 に圧縮できる。
        //
        // 【cacheName は 'api-cache' のまま維持】
        // 既に端末に配布済みの古い api-cache を孤児化させないため。名前を変えると
        // 旧キャッシュは誰も参照・失効させない残骸になる。同名を維持することで
        // 旧エントリは NetworkFirst の成功レスポンスで上書きされ、
        // ExpirationPlugin の新しい 1 時間ルールで掃除される。
        {
          urlPattern: /\/api\/v1\/public\//,
          handler: 'NetworkFirst' as const,
          method: 'GET',
          options: {
            cacheName: 'api-cache',
            networkTimeoutSeconds: 3,
            expiration: { maxEntries: 200, maxAgeSeconds: 3600 },
            cacheableResponse: { statuses: [0, 200] },
          },
        },
        // 静的マスタ（募集カテゴリ一覧）は StaleWhileRevalidate 24時間を維持。
        // 運用バッチでしか変わらない参照データで鮮度要件が無く、絞り込み UI の
        // 即時描画が効く。公開コンテンツと maxAgeSeconds が異なるため、
        // ExpirationPlugin の設定衝突を避けて別 cacheName に分離する。
        {
          urlPattern: /\/api\/v1\/recruitment-categories(?=[?#]|$)/,
          handler: 'StaleWhileRevalidate' as const,
          method: 'GET',
          options: {
            cacheName: 'api-static-cache',
            expiration: { maxEntries: 20, maxAgeSeconds: 86400 },
            cacheableResponse: { statuses: [0, 200] },
          },
        },
        // 認証付き API は NetworkOnly（キャッシュ禁止）。
        // 上の公開 API 用エントリがマッチしなかった /api/v1/** 全体に適用。
        // 将来誰かが「全 API をキャッシュしたい」という設定を追加しても、
        // workbox の先勝ちマッチによりこのエントリが防波堤として機能する。
        {
          urlPattern: /\/api\/v1\//,
          handler: 'NetworkOnly' as const,
          method: 'GET',
        },
        {
          urlPattern: /\.(?:png|jpg|jpeg|svg|gif|webp)$/,
          handler: 'CacheFirst' as const,
          options: {
            cacheName: 'image-cache',
            expiration: { maxEntries: 300, maxAgeSeconds: 604800 },
            cacheableResponse: { statuses: [0, 200] },
          },
        },
        {
          urlPattern: /\.(?:woff|woff2|ttf|eot)$/,
          handler: 'CacheFirst' as const,
          options: {
            cacheName: 'font-cache',
            expiration: { maxEntries: 20, maxAgeSeconds: 2592000 },
          },
        },
      ],
    },
  },

  css: [
    '~/assets/css/main.css',
    // frappe-gantt v1.2.2 の package.json exports に CSS サブパスが未定義のため、
    // Vite が exports を厳密チェックして ERR_PACKAGE_PATH_NOT_EXPORTED になる問題を回避。
    // node_modules からアセットとしてコピーし、exports 制限を完全に回避する（意図的なベンダリング）。
    '~/assets/css/frappe-gantt.css',
  ],

  runtimeConfig: {
    // F10.6 Phase 10-γ-③-b: SSRエラー転送用内部トークン（サーバーサイドのみ）
    internalLogToken: process.env.NUXT_INTERNAL_LOG_TOKEN || 'dev-internal-token',
    // Nitro サーバー（SSR）側からバックエンドを叩く際の内部 API ベース URL（サーバーサイドのみ）。
    // ブラウザ用 NUXT_PUBLIC_API_BASE は本番で '' (空文字・相対パス) にできるが、
    // Nitro サーバーは相対パスでは BE に到達できないため、絶対 URL を別途保持する。
    // 設計書: docs/security/03_security_headers_and_csp.md §2.1（apiBase 二層構成）
    internalApiBase: process.env.NUXT_INTERNAL_API_BASE ?? 'http://localhost:8080',
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE ?? 'http://localhost:8080',
      // フロントエンドのベース URL（canonical / hreflang / JSON-LD 等の SEO 用）。
      // NUXT_PUBLIC_API_BASE=''（同一オリジン構成）では apiBase から FE の origin を
      // 逆算できないため、専用の環境変数で明示する。
      // 設計書: docs/security/03_security_headers_and_csp.md §4.1 / useSeoPublicPage.ts
      baseUrl: process.env.NUXT_PUBLIC_BASE_URL ?? '',
      // F18 SELF_ISSUED_BALANCE 機能フラグ（2026-05-17 マスター御裁可で凍結）。
      // 資金決済法（前払式支払手段＝自家型）対応のため法務整備が整うまで一時凍結。
      // 設計書: docs/features/F18_point_card_wallet.md §1.4 / §16 / §17
      // 既定 false（凍結中）。再開時は NUXT_PUBLIC_F18_BALANCE_ENABLED=true で復活可能。
      f18BalanceEnabled: process.env.NUXT_PUBLIC_F18_BALANCE_ENABLED === 'true',
      // F08.9 P5 継続課金（Stripe.js）公開可能キー。
      // クライアントで `loadStripe(publishableKey)` に渡す。公開鍵は秘匿情報ではない（pk_*）が、
      // 環境（test/live）で切り替えるため値はコミットせず環境変数から注入する。
      // 未設定（空文字）の場合は useStripeSetup が明示エラーを投げる（症状を隠さない）。
      // 設計書: docs/features/F08.9_membership_billing_paywall/04_ui_i18n.md §2.2
      stripePublishableKey: process.env.NUXT_PUBLIC_STRIPE_PUBLISHABLE_KEY ?? '',
    },
  },

  // E2E テスト時（NUXT_API_PROXY=true 環境変数）は API を Nuxt サーバー経由でプロキシする。
  // これにより CORS プリフライト問題を回避し、Playwright のルートインターセプトが確実に機能する。
  routeRules: process.env.NUXT_API_PROXY === 'true' ? {
    '/api/v1/**': { proxy: `${apiBase}/api/v1/**` },
  } : {},

  // ──────────────────────────────────────────────────────────────────────
  // dev 限定: CSP 違反レポート (report-uri) を BE(:8080) へフォワードする。
  // ──────────────────────────────────────────────────────────────────────
  // report-uri は相対パス `/api/v1/security/csp-reports`（本番の同一オリジン挙動に合わせる）。
  // dev では FE(:3000)/BE(:8080) がオリジン分離しているため相対のままだと :3000 で 404 になる。
  // nitro.devProxy は `nuxi dev` 時のみ適用されるため本番には一切影響しない（=絶対URL化のような
  // 冗長な二重対応を避けつつ、dev でもブラウザの違反レポートが BE に 204 で届く）。
  // CSP 受信 EP のみに限定し、FE の通常 API 呼び出し（useApi の :8080 絶対URL）には干渉しない。
  // NUXT_API_PROXY=true（E2E 全 API プロキシ）時は routeRules 側が全 /api/v1/** を担うため二重化を避けて無効化。
  nitro: {
    devProxy:
      process.env.NUXT_API_PROXY === 'true'
        ? {}
        : {
            '/api/v1/security/csp-reports': {
              // devProxy は dev 専用（本番には適用されない）。
              // apiBase が空文字（本番同一オリジン構成）の場合もフォールバックとして
              // localhost:8080 を使う（dev で NUXT_PUBLIC_API_BASE='' のケースは想定しないが、
              // 空文字が target に混入して無効な URL になる事故を防ぐ）。
              target: `${apiBase || 'http://localhost:8080'}/api/v1/security/csp-reports`,
              changeOrigin: true,
            },
          },
  },

  i18n: {
    locales: [
      {
        code: 'ja',
        language: 'ja',
        name: '日本語',
        files: [
          'ja/common.json',
          'ja/auth.json',
          'ja/validation.json',
          'ja/landing.json',
          'ja/help.json',
          'ja/action_memo.json',
          'ja/pwa.json',
          'ja/recruitment.ts',
          'ja/quick_memo.json',
          'ja/reflection.json',
          'ja/timeline_digest.json',
          'ja/translation.json',
          'ja/equipment.json',
          'ja/friends.json',
          'ja/contact.json',
          'ja/announcement.json',
          'ja/profile_media.json',
          'ja/appearance.json',
          'ja/event.json',
          'ja/committee.json',
          'ja/jobmatching.json',
          'ja/matching.json',
          'ja/shift.json',
          'ja/care.json',
          'ja/surveys.json',
          'ja/reservation.json',
          'ja/school.json',
          'ja/shiftBudget.json',
          'ja/dashboard.json',
          'ja/activity.json',
          'ja/settings.json',
          'ja/timetable.json',
          'ja/member-info.json',
          'ja/property.json',
          'ja/disclosure.json',
          'ja/error_report.json',
          'ja/org_sidebar.json',
          'ja/team_sidebar.json',
          'ja/repair_plan.json',
          'ja/succession.json',
          'ja/wallet.json',
          'ja/village.json',
          'ja/system_admin_batch.json',
          'ja/system_admin_gdpr.json',
          'ja/system_admin_security_scan.json',
          'ja/system_admin_fee_policy.json',
          'ja/advertising.json',
          'ja/public.json',
          'ja/faq.json',
          'ja/proxy.json',
          'ja/bulletin.json',
          'ja/market.json',
          'ja/inbox.json',
          'ja/schedule.json',
          'ja/payment.json',
          'ja/match.json',
          'ja/tournament.json',
          'ja/file_sharing.json',
          'ja/admin_report.json',
          'ja/system_admin_incident_banner.json',
          'ja/admin_console.json',
          'ja/feedback.json',
          'ja/circulation.json',
          'ja/parental-consent.json',
          'ja/billing.json',
          'ja/global_nav.json',
        ],
      },
      {
        code: 'en',
        language: 'en',
        name: 'English',
        files: [
          'en/common.json',
          'en/auth.json',
          'en/validation.json',
          'en/landing.json',
          'en/help.json',
          'en/action_memo.json',
          'en/pwa.json',
          'en/recruitment.ts',
          'en/quick_memo.json',
          'en/reflection.json',
          'en/timeline_digest.json',
          'en/translation.json',
          'en/equipment.json',
          'en/friends.json',
          'en/contact.json',
          'en/announcement.json',
          'en/profile_media.json',
          'en/appearance.json',
          'en/event.json',
          'en/committee.json',
          'en/jobmatching.json',
          'en/matching.json',
          'en/shift.json',
          'en/care.json',
          'en/surveys.json',
          'en/reservation.json',
          'en/school.json',
          'en/shiftBudget.json',
          'en/dashboard.json',
          'en/activity.json',
          'en/settings.json',
          'en/timetable.json',
          'en/member-info.json',
          'en/property.json',
          'en/disclosure.json',
          'en/error_report.json',
          'en/org_sidebar.json',
          'en/team_sidebar.json',
          'en/repair_plan.json',
          'en/succession.json',
          'en/wallet.json',
          'en/village.json',
          'en/system_admin_batch.json',
          'en/system_admin_gdpr.json',
          'en/system_admin_security_scan.json',
          'en/system_admin_fee_policy.json',
          'en/advertising.json',
          'en/public.json',
          'en/faq.json',
          'en/proxy.json',
          'en/bulletin.json',
          'en/market.json',
          'en/inbox.json',
          'en/schedule.json',
          'en/payment.json',
          'en/match.json',
          'en/tournament.json',
          'en/file_sharing.json',
          'en/admin_report.json',
          'en/system_admin_incident_banner.json',
          'en/admin_console.json',
          'en/feedback.json',
          'en/circulation.json',
          'en/parental-consent.json',
          'en/billing.json',
          'en/global_nav.json',
        ],
      },
      {
        code: 'zh',
        language: 'zh',
        name: '中文（简体）',
        files: [
          'zh/common.json',
          'zh/auth.json',
          'zh/validation.json',
          'zh/landing.json',
          'zh/help.json',
          'zh/action_memo.json',
          'zh/pwa.json',
          'zh/recruitment.ts',
          'zh/quick_memo.json',
          'zh/reflection.json',
          'zh/timeline_digest.json',
          'zh/translation.json',
          'zh/equipment.json',
          'zh/friends.json',
          'zh/contact.json',
          'zh/announcement.json',
          'zh/profile_media.json',
          'zh/appearance.json',
          'zh/event.json',
          'zh/committee.json',
          'zh/jobmatching.json',
          'zh/matching.json',
          'zh/shift.json',
          'zh/care.json',
          'zh/surveys.json',
          'zh/reservation.json',
          'zh/school.json',
          'zh/shiftBudget.json',
          'zh/dashboard.json',
          'zh/activity.json',
          'zh/settings.json',
          'zh/timetable.json',
          'zh/member-info.json',
          'zh/property.json',
          'zh/disclosure.json',
          'zh/error_report.json',
          'zh/org_sidebar.json',
          'zh/team_sidebar.json',
          'zh/repair_plan.json',
          'zh/succession.json',
          'zh/wallet.json',
          'zh/village.json',
          'zh/system_admin_batch.json',
          'zh/system_admin_gdpr.json',
          'zh/system_admin_security_scan.json',
          'zh/system_admin_fee_policy.json',
          'zh/advertising.json',
          'zh/public.json',
          'zh/faq.json',
          'zh/proxy.json',
          'zh/bulletin.json',
          'zh/market.json',
          'zh/inbox.json',
          'zh/schedule.json',
          'zh/payment.json',
          'zh/match.json',
          'zh/tournament.json',
          'zh/file_sharing.json',
          'zh/admin_report.json',
          'zh/system_admin_incident_banner.json',
          'zh/admin_console.json',
          'zh/feedback.json',
          'zh/circulation.json',
          'zh/parental-consent.json',
          'zh/billing.json',
          'zh/global_nav.json',
        ],
      },
      {
        code: 'ko',
        language: 'ko',
        name: '한국어',
        files: [
          'ko/common.json',
          'ko/auth.json',
          'ko/validation.json',
          'ko/landing.json',
          'ko/help.json',
          'ko/action_memo.json',
          'ko/pwa.json',
          'ko/recruitment.ts',
          'ko/quick_memo.json',
          'ko/reflection.json',
          'ko/timeline_digest.json',
          'ko/translation.json',
          'ko/equipment.json',
          'ko/friends.json',
          'ko/contact.json',
          'ko/announcement.json',
          'ko/profile_media.json',
          'ko/appearance.json',
          'ko/event.json',
          'ko/committee.json',
          'ko/jobmatching.json',
          'ko/matching.json',
          'ko/shift.json',
          'ko/care.json',
          'ko/surveys.json',
          'ko/reservation.json',
          'ko/school.json',
          'ko/shiftBudget.json',
          'ko/dashboard.json',
          'ko/activity.json',
          'ko/settings.json',
          'ko/timetable.json',
          'ko/member-info.json',
          'ko/property.json',
          'ko/disclosure.json',
          'ko/error_report.json',
          'ko/org_sidebar.json',
          'ko/team_sidebar.json',
          'ko/repair_plan.json',
          'ko/succession.json',
          'ko/wallet.json',
          'ko/village.json',
          'ko/system_admin_batch.json',
          'ko/system_admin_gdpr.json',
          'ko/system_admin_security_scan.json',
          'ko/system_admin_fee_policy.json',
          'ko/advertising.json',
          'ko/public.json',
          'ko/faq.json',
          'ko/proxy.json',
          'ko/bulletin.json',
          'ko/market.json',
          'ko/inbox.json',
          'ko/schedule.json',
          'ko/payment.json',
          'ko/match.json',
          'ko/tournament.json',
          'ko/file_sharing.json',
          'ko/admin_report.json',
          'ko/system_admin_incident_banner.json',
          'ko/admin_console.json',
          'ko/feedback.json',
          'ko/circulation.json',
          'ko/parental-consent.json',
          'ko/billing.json',
          'ko/global_nav.json',
        ],
      },
      {
        code: 'es',
        language: 'es',
        name: 'Español',
        files: [
          'es/common.json',
          'es/auth.json',
          'es/validation.json',
          'es/landing.json',
          'es/help.json',
          'es/action_memo.json',
          'es/pwa.json',
          'es/recruitment.ts',
          'es/quick_memo.json',
          'es/reflection.json',
          'es/timeline_digest.json',
          'es/translation.json',
          'es/equipment.json',
          'es/friends.json',
          'es/contact.json',
          'es/announcement.json',
          'es/profile_media.json',
          'es/appearance.json',
          'es/event.json',
          'es/committee.json',
          'es/jobmatching.json',
          'es/matching.json',
          'es/shift.json',
          'es/care.json',
          'es/surveys.json',
          'es/reservation.json',
          'es/school.json',
          'es/shiftBudget.json',
          'es/dashboard.json',
          'es/activity.json',
          'es/settings.json',
          'es/timetable.json',
          'es/member-info.json',
          'es/property.json',
          'es/disclosure.json',
          'es/error_report.json',
          'es/org_sidebar.json',
          'es/team_sidebar.json',
          'es/repair_plan.json',
          'es/succession.json',
          'es/wallet.json',
          'es/village.json',
          'es/system_admin_batch.json',
          'es/system_admin_gdpr.json',
          'es/system_admin_security_scan.json',
          'es/system_admin_fee_policy.json',
          'es/advertising.json',
          'es/public.json',
          'es/faq.json',
          'es/proxy.json',
          'es/bulletin.json',
          'es/market.json',
          'es/inbox.json',
          'es/schedule.json',
          'es/payment.json',
          'es/match.json',
          'es/tournament.json',
          'es/file_sharing.json',
          'es/admin_report.json',
          'es/system_admin_incident_banner.json',
          'es/admin_console.json',
          'es/feedback.json',
          'es/circulation.json',
          'es/parental-consent.json',
          'es/billing.json',
          'es/global_nav.json',
        ],
      },
      {
        code: 'de',
        language: 'de',
        name: 'Deutsch',
        files: [
          'de/common.json',
          'de/auth.json',
          'de/validation.json',
          'de/landing.json',
          'de/help.json',
          'de/action_memo.json',
          'de/pwa.json',
          'de/recruitment.ts',
          'de/quick_memo.json',
          'de/reflection.json',
          'de/timeline_digest.json',
          'de/translation.json',
          'de/equipment.json',
          'de/friends.json',
          'de/contact.json',
          'de/announcement.json',
          'de/profile_media.json',
          'de/appearance.json',
          'de/event.json',
          'de/committee.json',
          'de/jobmatching.json',
          'de/matching.json',
          'de/shift.json',
          'de/care.json',
          'de/surveys.json',
          'de/reservation.json',
          'de/school.json',
          'de/shiftBudget.json',
          'de/dashboard.json',
          'de/activity.json',
          'de/settings.json',
          'de/timetable.json',
          'de/member-info.json',
          'de/property.json',
          'de/disclosure.json',
          'de/error_report.json',
          'de/org_sidebar.json',
          'de/team_sidebar.json',
          'de/repair_plan.json',
          'de/succession.json',
          'de/wallet.json',
          'de/village.json',
          'de/system_admin_batch.json',
          'de/system_admin_gdpr.json',
          'de/system_admin_security_scan.json',
          'de/system_admin_fee_policy.json',
          'de/advertising.json',
          'de/public.json',
          'de/faq.json',
          'de/proxy.json',
          'de/bulletin.json',
          'de/market.json',
          'de/inbox.json',
          'de/schedule.json',
          'de/payment.json',
          'de/match.json',
          'de/tournament.json',
          'de/file_sharing.json',
          'de/admin_report.json',
          'de/system_admin_incident_banner.json',
          'de/admin_console.json',
          'de/feedback.json',
          'de/circulation.json',
          'de/parental-consent.json',
          'de/billing.json',
          'de/global_nav.json',
        ],
      },
    ],
    defaultLocale: 'ja',
    strategy: 'no_prefix',
    lazy: true,
    restructureDir: false,
    bundle: {
      optimizeTranslationDirective: false,
    },
    langDir: 'locales/',
    detectBrowserLanguage: {
      // useCookie:true → SSR が Cookie からロケールを確定し、ハイドレーション mismatch を根治する。
      // リロード後もロケールが cookie 経由で復元されるためナビバーが日本語に戻るバグ（#2）を根治。
      useCookie: true,
      cookieKey: 'i18n_locale',
      redirectOn: 'root',
    },
  },

  primevue: {
    autoImport: true,
    components: {
      prefix: '',
    },
    options: {
      ripple: true,
      inputVariant: 'filled',
      theme: {
        preset: Aura,
        options: {
          prefix: 'p',
          darkModeSelector: '.p-dark',
          cssLayer: false,
        },
      },
    },
  },

  build: {
    // frappe-gantt 1.2.2 の package.json exports フィールドに
    // ./dist/frappe-gantt.css が列挙されていないため、
    // nuxt:ssr-styles:client プラグインの静的解析時にエラーが発生する。
    // transpile に指定することで Vite がパッケージを直接バンドルし解消する。
    transpile: ['frappe-gantt'],
  },

  // Lighthouse CI（nuxt generate）では SSR バンドルロード自体が 4GB OOM を引き起こすため
  // SPA モード（ssr: false）で Nitro SSR バンドルをスキップし、index.html + クライアント資産
  // のみ生成して計測可能にする。専用フラグ NUXT_GENERATE_SPA=true（lighthouse-ci.yml が付与）
  // のときだけ適用する。
  //
  // 【重要】かつては `process.env.CI === 'true'` で分岐していたが、これは Smoke E2E
  // （playwright が CI=true 下で `npm run dev` を起動）も巻き込んでいた。nuxt 3.21.6+ では
  // vite-node IPC が socket ベースに変更され、`ssr: false` の dev サーバーは
  // `vite:serverCreated` の SSR サーバーフックが発火しないため NUXT_VITE_NODE_OPTIONS.socketPath
  // が設定されず、SSR レンダリングが「Vite Node IPC socket path not configured」で 500 を返す。
  // その結果 playwright webServer が応答待ちのまま 240s timeout していた
  // （nuxt#35033 系のリグレッション。3.21.7+ では先に rollupOptions.input でクラッシュ）。
  // Smoke E2E は SSR を有効にすれば正常起動するため、SPA 化は Lighthouse 限定に戻す。
  ...(process.env.NUXT_GENERATE_SPA === 'true' ? { ssr: false } : {}),

  vite: {
    server: {
      allowedHosts: true,
    },
    optimizeDeps: {
      // date-holidays は pure ESM パッケージのため、Vite が事前バンドルしないと
      // dev server の SSR コンテキストでモジュール評価が失敗する
      // chart.js / dompurify / vuedraggable は遅延ロードされる詳細ページで初めて参照される。
      // 未指定だと初回 SPA 遷移中に Vite が依存を発見してページ全体を reload し、
      // URL 確定前の一覧へ戻るため、dev server 起動時に事前最適化しておく。
      include: ['date-holidays', 'dexie', 'chart.js', 'dompurify', 'vuedraggable'],
    },
  },
})
