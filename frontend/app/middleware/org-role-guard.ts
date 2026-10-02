/**
 * 組織詳細配下の管理者専用ページ（永続シェルのタブ対象外・budget/analytics/audit-logs 等）向け
 * 直リンク防御ミドルウェア（CMP-260917-1351 課題B）。
 *
 * 実証済みの欠陥: MEMBER が URL 直打ちでこれらのページを開くと、BE は 403 を返すのに
 * 画面はそのまま表示され空・エラー状態になる（二重防御が invites/permission-groups/
 * admin-console の3件にしか無かった）。
 *
 * 方針は `middleware/admin-console.ts` と同一（書き方を揃える。新流儀を持ち込まない）:
 *  - 取得失敗（BE障害等）は 503 でフルページエラーに落とす（権限なしに誤倒さず、症状を隠さない）
 *  - 権限不足は組織トップへ誘導 + エラートースト（`/login` へは飛ばさない）
 *  - 404 による存在秘匿はしない（列挙防止の本丸は BE 側の認可）
 *
 * 対象ページは全て ADMIN 専用（OrganizationSidebar.vue の該当項目 requiredRole:'ADMIN' と揃える。
 * 翻訳ページ（translations.vue）は CMP-260917-1351 課題C で MEMBER 可に変更されたため対象外）。
 */
export default defineNuxtRouteMiddleware(async (to) => {
  // ロール取得は client の認証トークンに依存するため SSR ではスキップ（admin-console.ts と同じ作法）。
  if (import.meta.server) return

  const slug = String(to.params.slug ?? '')
  if (!slug) return

  const scopeTop = `/organizations/${slug}`

  const nuxtApp = useNuxtApp()
  const t = (key: string): string => nuxtApp.$i18n.t(key)

  const access = useRoleAccess('organization', slug)
  const result = await access.loadPermissions()

  if (!result.ok) {
    throw createError({
      statusCode: 503,
      statusMessage: t('adminConsole.error.fetchFailedTitle'),
      data: { body: t('adminConsole.error.fetchFailedBody') },
      fatal: true,
    })
  }

  if (!access.isAdmin.value) {
    const toast = nuxtApp.$toast as
      | { add: (opts: Record<string, unknown>) => void }
      | undefined
    if (toast) {
      toast.add({
        severity: 'error',
        summary: t('adminConsole.middleware.accessDeniedTitle'),
        detail: t('adminConsole.middleware.accessDeniedBody'),
        life: 5000,
      })
    }
    return navigateTo(scopeTop)
  }
})
