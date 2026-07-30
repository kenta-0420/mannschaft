import { ofetch } from 'ofetch'
import { resolveApiBaseUrl } from '~/composables/useApiBaseUrl'
import type { PaywallDetails } from '~/stores/usePaywallStore'

/**
 * トークンリフレッシュの結果を表す 3 状態。
 *
 * boolean（成功/失敗）だと「本物の認証失敗（refresh_token 無効）」と
 * 「一時的な失敗（timeout / ネットワーク / 5xx）」が区別できず、回線が遅いだけの
 * ユーザーを誤ってログアウトさせてしまう。安全のため 3 状態に分離する。
 *
 * - 'refreshed'   : 更新成功（新トークンを setTokens 済み）
 * - 'auth_failed' : refresh エンドポイントが 401/403 ＝ refresh_token が無効な本物の認証失敗
 * - 'transient'   : timeout / abort / ネットワークエラー / 5xx ＝ 一時的（回線が遅い/詰まっただけ）
 */
export type TokenRefreshResult = 'refreshed' | 'auth_failed' | 'transient'

// refresh API のハング保険。15 秒で abort する。
// これが無いと refresh がハングした際に await が永久 pending となり、
// 先回り更新を await している async プラグイン（auth.client）が app mount をブロックし、
// layouts/default.vue の LoadingBounce フォールバックが固着して白画面化する。
const REFRESH_TIMEOUT_MS = 15_000

let refreshPromise: Promise<TokenRefreshResult> | null = null

/**
 * access_token の先回り／事後リフレッシュを行うモジュールスコープ関数。
 *
 * 定期的な先回りリフレッシュ（armProactiveRefresh スケジューラ）と interceptor の 401 リカバリの
 * 両方から呼ばれ、モジュール level の refreshPromise で二重リフレッシュを防止する
 * （同時に複数の呼び出しが来ても 1 本の Promise を共有する）。
 *
 * config / authStore は引数で受け取る。リクエスト時コンテキスト（イベントハンドラ等）から
 * useRuntimeConfig() / useAuthStore() を呼ぶと Nuxt インスタンスが未解決になる落とし穴が
 * あるため、setup 時に capture したものを必ず渡すこと。
 *
 * 返り値は 3 状態（TokenRefreshResult）。呼び出し側は 'transient' でログアウトしないこと。
 */
export function performTokenRefresh(
  config: ReturnType<typeof useRuntimeConfig>,
  authStore: ReturnType<typeof useAuthStore>,
): Promise<TokenRefreshResult> {
  // 二重リフレッシュ防止
  if (refreshPromise) {
    return refreshPromise
  }

  refreshPromise = (async (): Promise<TokenRefreshResult> => {
    // ofetch の timeout オプションに依存せず、確実に abort できるよう自前の
    // AbortController + setTimeout で 15 秒のハング保険を張る。
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), REFRESH_TIMEOUT_MS)
    try {
      // バックエンドは refresh_token Cookie を読むため、body への refreshToken 送信は不要。
      // credentials: 'include' で Cookie が自動送信される。
      const data = await ofetch<{ data: { accessToken: string; refreshToken: string } }>(
        '/api/v1/auth/refresh',
        {
          baseURL: resolveApiBaseUrl(config),
          method: 'POST',
          credentials: 'include',
          signal: controller.signal,
        },
      )
      authStore.setTokens(data.data.accessToken, data.data.refreshToken)
      return 'refreshed'
    }
    catch (error) {
      // 401/403/400 ＝ refresh_token が無効な本物の認証失敗。
      // 401: 現行 BE の正規レスポンス。AUTH_007（無効/リボーク済み）は
      //      GlobalExceptionHandler.ERROR_CODE_STATUS_MAP で 401 にマップされている。
      // 400: 旧 BE（AUTH_007 が Severity.WARN 既定の 400 で返っていた頃）およびモバイル等の
      //      旧クライアント互換のため、引き続き認証失敗として扱う。後方互換目的で残す。
      // それ以外（timeout/abort/ネットワークエラー/レスポンス無し/5xx）は一時的とみなし、
      // ログアウトさせない（回線が遅いだけのユーザーを誤ってログアウトさせないため）。
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status === 400 || status === 401 || status === 403) {
        return 'auth_failed'
      }
      return 'transient'
    }
    finally {
      clearTimeout(timer)
      refreshPromise = null
    }
  })()

  return refreshPromise
}

/**
 * access_token の先回り（proactive）リフレッシュタイマーの武装遅延を計算する純粋関数。
 *
 * expiresAtMs（失効時刻）から bufferMs（安全マージン）を差し引いた時刻までの残り時間を返す。
 * 既に過去/バッファ以内の場合は負の遅延にせず 0（即時実行）を返す（AC-4）。
 */
export function computeProactiveRefreshDelayMs(
  expiresAtMs: number,
  nowMs: number,
  bufferMs: number,
): number {
  return Math.max(0, expiresAtMs - nowMs - bufferMs)
}

// 失効の何 ms 前に先回りリフレッシュを発火するかの安全マージン。
const PROACTIVE_REFRESH_BUFFER_MS = 60_000
// 先回りリフレッシュが失敗した場合（transient / auth_failed 等・新しい expiry が得られない）の
// 再武装遅延。リアクティブ 401 ノイズに戻さず、短い間隔でリトライして回復を試みる（AC-7）。
const PROACTIVE_REFRESH_RETRY_DELAY_MS = 30_000

// タイマーハンドルは module-scope で保持する（refreshPromise と同様の single-flight パターン。AC-5）。
let proactiveRefreshTimer: ReturnType<typeof setTimeout> | null = null

// 'auth_failed' を掴んだログアウト処理の single-flight ガード。
// auth_failed は「先回りリフレッシュ経路（armProactiveRefresh の fire）」と
// 「401 interceptor 経路（onResponseError）」の両方から同時に観測され得る
// （performTokenRefresh は 1 本の Promise を共有するため、両者が同じ 'auth_failed' を受け取る）。
// ガードが無いと logout が二重に走り、navigateTo('/login?reason=...') が二重発火する。
let authFailureLogoutPromise: Promise<void> | null = null

/**
 * refresh_token が本当に無効（auth_failed）だったときのログアウトを 1 回に束ねる。
 *
 * 複数経路が同時に auth_failed を掴んでも logout は 1 回しか実行されず、
 * 呼び出し元はいずれも同じ Promise を await できる。
 * ログアウト完了後はガードを解除し、再ログイン後の失効でも再びログアウトできるようにする。
 */
export function handleAuthFailureLogout(
  authStore: ReturnType<typeof useAuthStore>,
): Promise<void> {
  if (authFailureLogoutPromise) {
    return authFailureLogoutPromise
  }
  authFailureLogoutPromise = (async () => {
    try {
      // reason=session_expired を付与し、ログイン画面でセッション失効の案内を表示する。
      await authStore.logout({ reason: 'session_expired' })
    }
    finally {
      authFailureLogoutPromise = null
    }
  })()
  return authFailureLogoutPromise
}

/**
 * 武装中の先回りリフレッシュタイマーを解除する（AC-3）。ログアウト時に呼ぶこと。
 */
export function disarmProactiveRefresh(): void {
  if (proactiveRefreshTimer !== null) {
    clearTimeout(proactiveRefreshTimer)
    proactiveRefreshTimer = null
  }
}

/**
 * access_token の先回りリフレッシュタイマーを（再）武装するモジュールスコープ関数。
 *
 * 【呼び出しタイミング】
 * setTokens（ログイン成功時・リフレッシュ成功時の両方。useAuthStore 側でフック）、
 * auth.client プラグインの起動時（認証済みなら）から呼ばれる。
 *
 * 【挙動】
 * - 常に 1 本のみ武装する。呼び出し時に既存タイマーをクリアしてから張り直す（AC-5）。
 * - localStorage の tokenExpiresAt から、失効の PROACTIVE_REFRESH_BUFFER_MS（60秒）前に
 *   発火するよう遅延を計算する。既に過去/バッファ以内なら即時（遅延0）で発火する（AC-4）。
 * - 発火後 performTokenRefresh が 'refreshed' を返せば、setTokens 内で書き込まれた新しい
 *   tokenExpiresAt に基づいて次のタイマーを再武装する（AC-2。セッションが続く限り失効させない）。
 * - 'transient'（timeout / ネットワーク / 5xx）の場合は諦めず、短い遅延で再武装してリトライする
 *   （AC-7。リアクティブ 401 ノイズに戻さず、回線が遅いだけのユーザーをログアウトさせないため）。
 * - 'auth_failed'（refresh_token が本当に無効）の場合は再武装せず、ログアウトして /login へ誘導する
 *   （AC-8）。何度リトライしても回復しないため、再武装すると「確実に失敗するリクエストを 30 秒おきに
 *   永久に投げ続ける」ゾンビセッション（localStorage の currentUser が残り isAuthenticated が true の
 *   まま）になる。認証必須 API を 1 本も撃たないページに留まった場合、401 interceptor 側の
 *   ログアウトによる自然治癒も期待できない。
 * - SSR では張らない（AC-6。import.meta.client ガード）。
 *
 * config / authStore は performTokenRefresh と同様に引数で受け取る。setTimeout コールバック内で
 * useRuntimeConfig() / useAuthStore() を呼ぶと Nuxt インスタンスが未解決になる落とし穴があるため、
 * 呼び出し元（setTokens・auth.client プラグイン等の同期コンテキスト）で capture したものを渡すこと。
 *
 * 【遅延0（即時）の特別扱い】
 * delayMs が 0 の場合は setTimeout を挟まず、呼び出しと同じ同期フェーズで refresh の fetch を
 * 開始する。auth.client プラグインの起動時武装（リロード直後に Cookie が失効済みのケース）では、
 * 後続のアルファベット順プラグイン（nav-settings.client 等）が先に認証付き API を撃って 401 を
 * 出す前に、refresh のリクエストを確実に先行させる必要がある。setTimeout(fn, 0) はマクロタスクの
 * ため、後続プラグインの同期実行より後回しになってしまいレースに負ける（実機E2E
 * auth-proactive-refresh.spec.ts の PRR-001 が検証するリグレッションの再発になる）。
 */
export function armProactiveRefresh(
  config: ReturnType<typeof useRuntimeConfig>,
  authStore: ReturnType<typeof useAuthStore>,
): void {
  if (!import.meta.client) return

  disarmProactiveRefresh()
  // 武装＝生存中のセッションが（再）確立された状態。過去のログアウト処理のガードが
  // 何らかの理由で残っていても、ここで解除して再ログイン後の失効に備える。
  authFailureLogoutPromise = null

  const raw = localStorage.getItem('tokenExpiresAt')
  const expiresAtMs = raw ? Number(raw) : 0
  const delayMs = computeProactiveRefreshDelayMs(expiresAtMs, Date.now(), PROACTIVE_REFRESH_BUFFER_MS)

  const fire = (): void => {
    void (async () => {
      const result = await performTokenRefresh(config, authStore)
      if (result === 'refreshed') {
        // 新しい tokenExpiresAt（setTokens 内で書き込み済み）に基づいて次のタイマーを再武装する。
        armProactiveRefresh(config, authStore)
        return
      }
      if (result === 'transient') {
        // timeout / ネットワーク / 5xx の一時的失敗。回線が遅いだけのユーザーを誤ってログアウト
        // させないため、諦めず短い遅延で再武装して回復を試みる（AC-7）。
        proactiveRefreshTimer = setTimeout(() => {
          armProactiveRefresh(config, authStore)
        }, PROACTIVE_REFRESH_RETRY_DELAY_MS)
        return
      }
      // 'auth_failed': refresh_token が本当に無効。リトライしても永久に回復しないため
      // 再武装せずログアウトして /login へ誘導する（AC-8）。
      //
      // ここは必ず performTokenRefresh の await より後（= 最速でもネットワーク応答後）に実行される。
      // auth.client プラグインの起動時武装（遅延0で同期発火する経路）でも、logout 内の
      // navigateTo がプラグインの同期実行フェーズに割り込むことはなく、app mount をブロックしない
      //（白画面根治の前提を維持。armProactiveRefresh は同期関数で Promise を await しない）。
      await handleAuthFailureLogout(authStore)
    })()
  }

  if (delayMs === 0) {
    fire()
  }
  else {
    proactiveRefreshTimer = setTimeout(fire, delayMs)
  }
}

/**
 * `Retry-After` ヘッダを「あと何秒待てばよいか」に正規化する（RFC 9110 §10.2.3）。
 *
 * BE の `AbstractRateLimitFilter` は delay-seconds 形式（例: `"20"`）で返すが、
 * 将来 CDN / WAF が前段に入って HTTP-date 形式で返す可能性もあるため両方を受ける。
 * 解釈できない値は `null` を返し、呼び出し元は秒数なしの文言にフォールバックする
 * （壊れたヘッダで NaN 秒と表示するより、秒数を出さないほうが正直）。
 *
 * @param headerValue `Retry-After` ヘッダの生値（未設定なら null）
 * @returns 待ち秒数（0 以上）。解釈できない場合は null
 */
export function parseRetryAfterSeconds(headerValue: string | null): number | null {
  if (headerValue === null) return null
  const trimmed = headerValue.trim()
  if (trimmed === '') return null

  // delay-seconds 形式（BE の標準応答）
  if (/^\d+$/.test(trimmed)) {
    const seconds = Number(trimmed)
    return Number.isFinite(seconds) ? seconds : null
  }

  // HTTP-date 形式
  const dateMs = Date.parse(trimmed)
  if (Number.isNaN(dateMs)) return null
  return Math.max(0, Math.ceil((dateMs - Date.now()) / 1000))
}

// 短時間に複数の 5xx が発生した場合のトースト集約
let _errorBatchTimer: ReturnType<typeof setTimeout> | null = null
let _errorBatchCount = 0
let _errorBatchFirst: { status: number; statusText: string; url: string } | null = null

export function useApi() {
  const config = useRuntimeConfig()
  const authStore = useAuthStore()
  const proxyDeskStore = useProxyDeskStore()
  const guardianshipSwitchStore = useGuardianshipSwitchStore()
  const adminImpersonationStore = useAdminImpersonationStore()
  const nuxtApp = useNuxtApp()
  const errorReport = useErrorReport()

  // useI18n() は setup コンテキスト外（イベントハンドラや Pinia アクション）では
  // 呼べないため、useNuxtApp().$i18n 経由でアクセスする。
  const t = (key: string) => nuxtApp.$i18n.t(key)
  // 名前付き補間つきの翻訳。$i18n は useI18n() と同じ Composer なので
  // t(key, named) 形（テンプレートで既に使われている形）がそのまま使える。
  const tn = (key: string, named: Record<string, unknown>) => nuxtApp.$i18n.t(key, named)

  const api = ofetch.create({
    baseURL: resolveApiBaseUrl(config),
    // HttpOnly Cookie を自動送信するために credentials: 'include' を設定する。
    // これにより access_token Cookie がすべての API リクエストに付与される。
    credentials: 'include',
    // 401 受信後に onResponseError でトークンをリフレッシュし、onRequest で新トークンを付与して 1 回自動リトライする。
    retry: 1,
    retryStatusCodes: [401],

    onRequest({ options }) {
      if (authStore.accessToken) {
        const headers = new Headers(options.headers)
        headers.set('Authorization', `Bearer ${authStore.accessToken}`)

        // 代理入力モードが有効な場合: 4ヘッダを自動付与
        if (proxyDeskStore.isPinned) {
          headers.set('X-Proxy-For-User-Id', String(proxyDeskStore.pinnedSubjectUserId))
          headers.set('X-Proxy-Consent-Id', String(proxyDeskStore.pinnedConsentId))
          headers.set('X-Proxy-Input-Source', proxyDeskStore.inputSource)
          if (proxyDeskStore.originalStorageLocation) {
            headers.set('X-Proxy-Original-Storage', proxyDeskStore.originalStorageLocation)
          }
        }
        // 後見切替モードが有効な場合: X-Proxy-For-User-Id のみ付与（guardianship 経路）
        // X-Proxy-Consent-Id / X-Proxy-Input-Source は送らない（代理入力とは別経路）
        else if (guardianshipSwitchStore.isActingAs) {
          headers.set('X-Proxy-For-User-Id', String(guardianshipSwitchStore.activeChild!.childUserId))
        }
        // 管理者変身モードが有効な場合: X-Admin-Impersonate-User-Id を付与（F10.1）
        // BE の AdminImpersonationFilter が SecurityContext の principal を対象ユーザー ID に置き換える
        else if (adminImpersonationStore.isImpersonating) {
          headers.set('X-Admin-Impersonate-User-Id', String(adminImpersonationStore.targetUserId))
        }

        options.headers = headers
      }
    },

    async onResponseError({ options, request, response }) {
      // 401: Refresh Token ローテーション
      // バックエンドは未認証リクエストに必ず 401 を返す（SecurityConfig.exceptionHandling 参照）。
      if (response.status === 401) {
        // リトライ済み（2 回目の 401）はリフレッシュ不要。エラーをそのまま伝播させる
        const opts = options as unknown as Record<string, unknown>
        if (opts._tokenRefreshed) return
        opts._tokenRefreshed = true

        if (authStore.user) {
          const result = await performTokenRefresh(config, authStore)
          if (result === 'auth_failed') {
            // refresh_token が無効な本物の認証失敗。ログアウトして /login へ誘導する。
            // 先回りリフレッシュ経路（armProactiveRefresh の fire）と同じ single-flight ヘルパーを
            // 通し、両経路が同一の auth_failed を掴んでも logout が二重に走らないようにする。
            await handleAuthFailureLogout(authStore)
            // throw してリトライを中断する（リフレッシュ失敗 = ログアウト済み）
            throw new Error('token_refresh_failed')
          }
          if (result === 'transient') {
            // timeout / ネットワーク / 5xx の一時的失敗。回線が遅いだけのユーザーを
            // 誤ってログアウトさせないため、ログアウトせず元のエラーをそのまま伝播させる。
            // throw すると ofetch はリトライせず、呼び出し元が 401 を受け取って再試行できる。
            throw new Error('token_refresh_transient')
          }
          // 'refreshed': throw しない → ofetch が onRequest で新トークンを付与して 1 回リトライ
          // リアクティブ経路（この 401 ハンドラ）でリフレッシュが成立した場合も、先回りタイマーを
          // 新しい失効時刻で（再）武装しておく。通常は先回りタイマーが常時武装されているため
          // ここへは来ないが、万一先回りが未武装のまま 401 を踏んだ場合でも、以後はリアクティブ 401 を
          // 出さず先回り経路へ復帰できるようにする防御。useApi() setup で capture 済みの
          // config / authStore を渡すため、この async コンテキストでも composable は呼ばない。
          armProactiveRefresh(config, authStore)
          return
        }
        else {
          await navigateTo('/login')
          // throw してリトライを中断する
          throw new Error('not_authenticated')
        }
      }

      // 403: 認証済みだが権限不足の場合（Forbidden）。
      // ログアウトせず、呼び出し元でエラーを処理する（例: 「アクセス権がありません」表示）。
      // 未認証状態（user が null）での 403 は想定外だが、フォールバックとしてログインへ誘導する。
      if (response.status === 403 && !authStore.user) {
        await navigateTo('/login')
        return
      }

      // 402: F20.1 課金・エンタイトルメント基盤の共通ペイウォール検知（設計書 04 §2）。
      // ENTITLEMENT_003（購入手段あり・402）はここでグローバルにペイウォールモーダルを開く。
      // 呼び出し元は個別に握りつぶさず通常どおりエラーを catch できる（モーダル表示は副作用のみ）。
      // BE（#2442）は details（featureKey/addonAvailable/addonPriceJpy/plansContaining/
      // scopeKind/scopeId）を追補済みだが、details を持たない応答（旧 BE・後方互換）でも
      // message のみで動作する（AC-23）。
      if (response.status === 402) {
        const body = response._data as {
          error?: {
            code?: string
            message?: string
            details?: PaywallDetails
          }
        } | undefined
        if (body?.error?.code === 'ENTITLEMENT_003') {
          usePaywallStore().open({ message: body.error.message, details: body.error.details })
        }
      }

      // 429: レートリミット超過の共通ハンドリング（docs/security/06 §4.3 の標準応答）。
      //
      // BE の各 *RateLimitFilter は AbstractRateLimitFilter を通じて
      // 429 + Retry-After + X-RateLimit-* + {"error":"Too many requests"} を返す。
      // ここに共通ハンドリングが無かったため、呼び出し元が try/catch を持たない経路
      //（例: お知らせウィジェットの「すべて既読にする」）で 429 を踏むと
      // unhandledrejection になり、error-handler.client.ts が errorReport に載せるだけで
      // 画面には何も出ない = 「押しても何も起きない」沈黙する失敗になっていた。
      //
      // 402 のペイウォールと同じく「副作用として利用者に提示するだけ」に留め、
      // エラー自体は握りつぶさず呼び出し元へ伝播させる（握りつぶし catch 禁止・#2460）。
      // 提示の型は同じ関数内の 5xx 分岐（$toast + summary/detail/life）に揃えている。
      // 集約はしない — レート制限は「連打した本人が今この操作で弾かれた」ことを
      // 即座に知る必要があり、500ms の集約待ちを挟むと因果が伝わりにくくなるため。
      if (response.status === 429) {
        const toast = nuxtApp.$toast as
          | { add: (opts: Record<string, unknown>) => void }
          | undefined
        if (toast) {
          const retryAfterSeconds = parseRetryAfterSeconds(response.headers.get('Retry-After'))
          toast.add({
            severity: 'warn',
            summary: t('error.rate_limited'),
            detail:
              retryAfterSeconds === null
                ? t('error.rate_limited_detail')
                : tn('error.rate_limited_retry_after', { seconds: retryAfterSeconds }),
            life: 5000,
          })
        }
      }

      // 5xx: トースト集約 + エラー報告
      if (response.status >= 500) {
        const requestId = response.headers.get('X-Request-ID') ?? undefined
        const apiUrl = typeof request === 'string' ? request : (request?.toString() ?? '')

        // エラー報告は毎回送信
        errorReport.capture(new Error(`HTTP ${response.status} ${response.statusText}`), {
          apiUrl,
          statusCode: response.status,
          requestId,
        })

        // トーストは 500ms 以内の複数エラーをまとめて1件に集約
        _errorBatchCount++
        if (_errorBatchFirst === null) {
          _errorBatchFirst = {
            status: response.status,
            statusText: response.statusText,
            url: apiUrl,
          }
        }
        if (_errorBatchTimer === null) {
          _errorBatchTimer = setTimeout(() => {
            const toast = useNuxtApp().$toast as
              | { add: (opts: Record<string, unknown>) => void }
              | undefined
            if (toast) {
              toast.add({
                severity: 'error',
                summary: t('error.server'),
                detail:
                  _errorBatchCount > 1
                    ? `${_errorBatchCount}件のサーバーエラーが発生しました`
                    : t('error.server_retry'),
                life: 5000,
              })
            }
            _errorBatchCount = 0
            _errorBatchFirst = null
            _errorBatchTimer = null
          }, 500)
        }
      }
    },
  })

  return api
}
