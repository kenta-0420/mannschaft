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

import { computed, ref } from 'vue'

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

/** 取得済みの権限と、それが<b>どの組織のものか</b>。両者を切り離して持たないこと。 */
export interface GrantedBudgetPermissions {
  slug: string
  permissions: string[]
}

/**
 * 組織ごとの権限取得結果を、組織と紐付けて保持する状態。
 *
 * <p>組織スコープを切り替えると、切替前の組織の権限が残ったままになる（旧組織で BUDGET_ADMIN・
 * 新組織で一般 MEMBER の利用者に、新しい取得が終わるまで管理ボタンが見えてしまう）。
 * さらに切替前後のリクエストが並行すると、遅れて返った<b>古い</b>レスポンスが後から書き込まれ、
 * 古い権限が居座り続ける。どちらも「権限の無い利用者に導線を見せない」という目的を崩す。</p>
 *
 * <p>そこで (1) 取得を始めた時点で保持中の結果を捨て（出してから消す窓を作らない）、
 * (2) 自分より新しい取得が始まっていたらレスポンスを破棄し、
 * (3) 結果は必ず slug と対にして保持する。</p>
 *
 * @param fetchPermissions 組織 slug の権限一覧を返す。取得に失敗した場合は null を返すこと
 *                         （空配列＝「権限を持たない」と、取得失敗を混同しないため）
 */
export function createBudgetPermissionState(
  fetchPermissions: (slug: string) => Promise<string[] | null>,
) {
  const granted = ref<GrantedBudgetPermissions | null>(null)
  /** 最後に開始した取得の通し番号。これと一致しないレスポンスは古いので捨てる。 */
  let latestRequest = 0

  async function load(slug: string | null): Promise<void> {
    const request = ++latestRequest
    // 取得中は「権限なし」に倒す。旧組織の権限で導線を出す窓をここで閉じる。
    granted.value = null
    if (!slug) return

    const permissions = await fetchPermissions(slug)
    // 自分より後に始まった取得がある＝このレスポンスは古い。新しい結果を上書きしない。
    if (request !== latestRequest) return
    // 取得失敗。false のままにするが、成功（空の権限）と混同して記録はしない。
    if (permissions === null) return

    granted.value = { slug, permissions }
  }

  return { granted, load }
}

/**
 * 表示中の組織に対して、予算の管理操作を出してよいか。
 *
 * <p>保持している権限が別の組織のものであれば使わない（組織切替の追随漏れを型で塞ぐ）。</p>
 */
export function canManageBudgetWith(
  currentSlug: string | null,
  granted: GrantedBudgetPermissions | null,
): boolean {
  if (!currentSlug || granted === null) return false
  if (granted.slug !== currentSlug) return false
  return hasBudgetAdminPermission(currentSlug, granted.permissions)
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

  /**
   * 権限の取得は正典 {@link useRoleAccess} に委ねる。
   *
   * <p>slug を <b>Ref ではなく文字列で</b>渡すのは意図的。Ref を渡すと useRoleAccess が
   * 自前の watch で再取得を始め、その書き込みがここの順序制御の外側で起きるため。
   * 取得ごとにインスタンスを作れば、レスポンスは他の取得と混ざらない。</p>
   */
  const { granted, load } = createBudgetPermissionState(async (slug) => {
    const access = useRoleAccess('organization', slug)
    const result = await access.loadPermissions()
    return result.ok ? [...access.permissions.value] : null
  })

  const canManageBudget = computed(() => canManageBudgetWith(organizationSlug.value, granted.value))

  /**
   * slug の解決に必要な所属組織一覧を取得したうえで、現在の組織の権限を読み込む。
   */
  async function ensureLoaded(): Promise<void> {
    if (organizationStore.myOrganizations.length === 0) {
      await organizationStore.fetchMyOrganizations()
    }
    await load(organizationSlug.value)
  }

  return { organizationSlug, canManageBudget, ensureLoaded }
}
