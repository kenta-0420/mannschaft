import { GATE_FALLBACK_PATH, decideGate } from '~/constants/featureGates'

/**
 * 未公開機能の route 層隔離（Gate 基盤工事②）。
 *
 * 設計: Gate 基盤の4層（ナビ／route／API／バックグラウンド）のうち **route 層**を担う。
 * 対応表は `~/constants/featureGates` の `GATE_ROUTE_MAP`（gate_key の発行元は棚卸し台帳）。
 *
 * ## なぜ global middleware なのか
 * named middleware ＋ `definePageMeta` の自己申告方式は、宣言忘れが即 fail-open
 * （= 未公開機能の漏洩）になり本工事の目的と利害が逆になる。global なら漏れは
 * 「余計に弾く」側へ倒れる。実行順はファイル名のアルファベット順で
 * `feature-gate` < `slug-redirect` だが、`GATE_ROUTE_MAP` は teams/organizations の
 * slug パスを一切束縛していないため、`slug-redirect.global.ts` との干渉は起きない。
 *
 * ## 拒否時の挙動（「伝えて戻す」）
 * `navigateTo` で {@link GATE_FALLBACK_PATH} へ戻し、`$toast` でエラーを伝える。
 * **404 による存在秘匿はしない**（`admin-console.ts` に明文化済みのプロジェクト慣習）。
 *
 * ## 取得失敗は拒否に誤変換しない
 * 公開フラグの取得失敗は 503（fatal）で正直に伝播する。`admin-console.ts` の
 * 「権限不足（正常な false）と取得失敗を厳格に区別する」作法に倣う。
 * 「未公開扱いで弾く」も「公開扱いで通す」もしない（症状を隠さない）。
 *
 * ## SSR
 * SSR ではフラグを取得できない（公開フラグ API は localStorage のトークンに依存し、
 * ①の plugin は `.client` 限定）。ここでは判定せず `ssr-defer` として抜け、
 * **未公開コンテンツの SSR 出力自体は nuxt.config の routeRules（`ssr: false`）が防ぐ**
 * （`buildGateRouteRules()`）。素通り（fail-open）でも全機能未公開扱い（コアページまで
 * 弾く fail-closed の誤爆）でもない三値判定になっている。
 */
export default defineNuxtRouteMiddleware(async (to) => {
  const store = useFeatureFlagStore()

  const evaluate = () => decideGate({
    path: to.path,
    isServer: import.meta.server,
    publicLoaded: store.publicLoaded,
    enabled: (gateKey: string) => store.isEnabled(gateKey),
  })

  let decision = evaluate()

  // ガード対象外パス・SSR は即通過（対象外パスではフラグ取得関数を一度も呼ばない）。
  if (decision.action === 'pass' || decision.action === 'ssr-defer') return

  const nuxtApp = useNuxtApp()
  const t = (key: string): string => nuxtApp.$i18n.t(key)

  // 公開フラグ未取得（unknown）: ここで遅延取得する。素通りさせない（fail-open 禁止）。
  if (decision.action === 'ensure') {
    try {
      await store.loadPublicFlags()
    } catch (error) {
      // 取得失敗を「未公開」に倒さない。503 で正直に伝播する（握りつぶさない）。
      throw createError({
        statusCode: 503,
        statusMessage: t('featureGate.error.fetchFailedTitle'),
        data: { body: t('featureGate.error.fetchFailedBody') },
        cause: error,
        // fatal: true = Nuxt フルページエラーに落とす。
        fatal: true,
      })
    }
    decision = evaluate()
    if (decision.action === 'pass') return
    // 取得が解決したのにフラグが確定しない異常系は fail-closed（戻す）で扱う。
  }

  // 未公開: プロジェクト慣習に従い戻り先へ戻す（404 にしない）＋エラートースト。
  const toast = nuxtApp.$toast as
    | { add: (opts: Record<string, unknown>) => void }
    | undefined
  if (toast) {
    toast.add({
      severity: 'error',
      summary: t('featureGate.blocked.title'),
      detail: t('featureGate.blocked.body'),
      life: 5000,
    })
  }
  return navigateTo(GATE_FALLBACK_PATH, { replace: true })
})
