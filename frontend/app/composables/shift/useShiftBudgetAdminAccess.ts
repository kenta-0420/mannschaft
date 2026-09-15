/**
 * 予算管理（F08.7 `/admin/shift-budget/*`）の操作導線を出し分けるための権限判定（CMP-260913-1251）。
 *
 * <p>BE は Service 層で `BUDGET_ADMIN` を必須とし、持たない利用者・他テナントには 403 を返す
 * （{@code MonthlyShiftBudgetCloseService} / {@code ShiftBudgetAllocationService} /
 * {@code BudgetThresholdAlertService} / {@code ShiftBudgetFailedEventService}）。
 * このガードは一切外さない。ここで行うのは「BE が必ず弾く操作を画面に出さない」ことだけであり、
 * 防御ではなく表示の作法である。</p>
 *
 * <p>作法は直近の前例（チーム設定 > 時給設定を `useRoleAccess` の結果で
 * `TeamSidebar` から出し分ける）に合わせ、<b>権限が無ければ導線そのものを出さない</b>。
 * 権限の取得に失敗した場合も出さない（見せて 403 で弾かれるより安全側。
 * {@code TimelinePostForm} の配信範囲セレクタと同じ判断）。</p>
 *
 * <p>スコープ ID の注意: {@code useScopeStore} が保持する組織 ID は数値 ID だが、
 * 権限 API {@code /api/v1/organizations/{slug}/me/permissions} は slug でしか解決できない
 * （{@code OrganizationService#resolveOrgId}）。数値 ID をそのまま渡すと 404 になり
 * 「権限なし」と区別が付かないため、自分の所属組織一覧から slug を引き当ててから渡す。
 * 引き当てられない組織＝自分が所属していない組織（他テナント）なので false に倒れる。</p>
 */

/** BE の Service 層が要求する権限名（`accessControlService.checkPermission` の引数と同一）。 */
export const BUDGET_ADMIN_PERMISSION = 'BUDGET_ADMIN'

interface ScopeLike {
  type: string
  id: string | null
}

interface OrganizationLike {
  id: number
  slug: string
}

/**
 * 現在のスコープに対応する組織 slug を、自分の所属組織一覧から解決する。
 *
 * @returns 組織スコープかつ自分が所属している場合のみ slug。それ以外（個人/チームスコープ・
 *          他テナントの組織 ID・所属組織が未取得）は null。
 */
export function resolveOrganizationSlug(
  scope: ScopeLike,
  organizations: readonly OrganizationLike[],
): string | null {
  if (scope.type !== 'organization' || !scope.id) return null
  const found = organizations.find(o => String(o.id) === String(scope.id))
  return found?.slug ?? null
}

/**
 * 予算の管理操作（月次締め・割当 CRUD・警告承認・失敗イベント再実行）を出してよいか。
 *
 * <p>slug が解決できていない（他テナント／未取得）ときは、権限リストの内容に関わらず false。
 * その権限リストは別スコープのものか、そもそも取得できていないためである。</p>
 */
export function hasBudgetAdminPermission(
  organizationSlug: string | null,
  permissions: readonly string[],
): boolean {
  if (!organizationSlug) return false
  return permissions.includes(BUDGET_ADMIN_PERMISSION)
}

/**
 * 予算管理画面で使う権限判定 composable。
 *
 * <p>`ensureLoaded()` を `onMounted` と組織スコープの切り替え時に呼ぶこと。</p>
 */
export function useShiftBudgetAdminAccess() {
  const scopeStore = useScopeStore()
  const organizationStore = useOrganizationStore()

  const organizationSlug = computed(() =>
    resolveOrganizationSlug(scopeStore.current, organizationStore.myOrganizations),
  )
  // useRoleAccess は空文字なら取得を行わない（判定材料が無いだけで失敗ではない）。
  const slugRef = computed(() => organizationSlug.value ?? '')
  const { permissions, loadPermissions, loading } = useRoleAccess('organization', slugRef)

  const canManageBudget = computed(() =>
    hasBudgetAdminPermission(organizationSlug.value, permissions.value),
  )

  /**
   * slug の解決に必要な所属組織一覧を取得したうえで、権限を読み込む。
   *
   * <p>slug が取得の前後で変わった場合は {@code useRoleAccess} 内の watch も発火するが、
   * 呼び出し側から見て「await したら判定が確定している」ことを優先し、ここでも明示的に読む。</p>
   */
  async function ensureLoaded(): Promise<void> {
    if (organizationStore.myOrganizations.length === 0) {
      await organizationStore.fetchMyOrganizations()
    }
    await loadPermissions()
  }

  return { organizationSlug, canManageBudget, permissionsLoading: loading, ensureLoaded }
}
