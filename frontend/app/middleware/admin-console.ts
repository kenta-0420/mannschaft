/**
 * 管理コンソール（`/teams|organizations/[slug]/admin` 配下）のアクセス制御ミドルウェア。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/01_console_routes.md §5 /
 *         04_security_authorization.md / 05_decisions.md §5・§8
 *
 * ## 方針（§5.1 / 05 §5）
 * - スコープのロールを `useRoleAccess(scopeType, slug)` で解決し `isAdminOrDeputy` を判定する。
 * - **「権限不足（正常に false）」と「取得失敗（例外・タイムアウト・5xx）」を区別する**:
 *   - 取得失敗（`ok: false`）→ エラー画面（503 相当・再試行）。権限なしに倒さない（症状を隠さない）。
 *   - 権限不足（`ok: true` かつ非管理者）→ スコープトップへリダイレクト＋エラートースト。
 * - **404 による存在秘匿はしない**（05 §8・プロジェクト慣習）。列挙防止の本丸は BE の scope 絞り込み＋F00 認可。
 *
 * これは UX（誤遷移の早期遮断）のための表示制御であり、認可の最終判断ではない。
 * BE は各 API で必ず認可チェックを通す（04 §2）。
 */
export default defineNuxtRouteMiddleware(async (to) => {
  // ロール取得は client の認証トークン（localStorage）に依存するため SSR ではスキップする。
  // auth ミドルウェアと同じ作法（SSR では判定材料が無いので素通り、client で確定させる）。
  if (import.meta.server) return

  // slug が空の場合はミドルウェアを素通りさせ、ページ側でルート不一致を処理する。
  const slug = String(to.params.slug ?? '')
  if (!slug) return

  const scopeType = to.path.startsWith('/organizations/') ? 'organization' : 'team'
  const scopeTop = scopeType === 'organization'
    ? `/organizations/${slug}`
    : `/teams/${slug}`

  const nuxtApp = useNuxtApp()
  const t = (key: string): string => nuxtApp.$i18n.t(key)

  const access = useRoleAccess(scopeType, slug)
  const result = await access.loadPermissions()

  // 取得失敗（BE 障害等）。権限なしに倒さず 503 でフルページエラーへ落とす（握りつぶさない）。
  // 取得失敗は 503 で正直に伝播。リッチな再試行画面 UX（専用 error.vue）は本フェーズ対象外。
  if (!result.ok) {
    throw createError({
      statusCode: 503,
      statusMessage: t('adminConsole.error.fetchFailedTitle'),
      data: { body: t('adminConsole.error.fetchFailedBody') },
      // fatal: true = Nuxt フルページエラーに落とす（権限なしへの誤倒しを防ぐ正しい挙動）。
      fatal: true,
    })
  }

  // 権限不足: プロジェクト慣習に従いスコープトップへ戻す（404 にしない）＋エラートースト。
  if (!access.isAdminOrDeputy.value) {
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
