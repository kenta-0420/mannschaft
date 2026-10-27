type RoleName = 'SYSTEM_ADMIN' | 'ADMIN' | 'DEPUTY_ADMIN' | 'MEMBER' | 'SUPPORTER' | 'GUEST'

interface EffectivePermissions {
  roleName: RoleName
  permissions: string[]
  /**
   * 対象スコープの ADMIN 割当があるか（BE の AccessControlService.isAdmin と同じ判定）。
   * roleName は SYSTEM_ADMIN を優先して返すため、SYSTEM_ADMIN との兼任者は roleName だけでは判定できない。
   * 旧 BE は返さない（undefined）。
   */
  scopeAdmin?: boolean
  /**
   * ADMIN 専用権限（MANAGE_ORG_AFFILIATION 等）を含む権限グループの作成・更新・割当（付与・剥奪）ができるか。
   * BE の AccessControlService#canActAsScopeAdminOnly（スコープ ADMIN 本人で、かつ SYSTEM_ADMIN でない）。
   * SYSTEM_ADMIN 兼任者は scopeAdmin=true でも false。旧 BE は返さない（undefined）。
   */
  canGrantAdminOnlyPermissions?: boolean
}

export function useRoleAccess(
  scopeType: 'team' | 'organization',
  scopeId: Ref<string> | string,
  options: { watchScope?: boolean } = {},
) {
  const api = useApi()
  const auth = useAuthStore()
  const permissions = ref<string[]>([])
  const roleName = ref<RoleName | null>(null)
  const scopeAdminFromApi = ref<boolean | null>(null)
  const canGrantFromApi = ref<boolean | null>(null)
  const loading = ref(false)
  /**
   * ロール解決が完了したか（成功・失敗いずれでも true）。roleName=null は「読込中」「未所属」
   * 「取得失敗」で同じになるため、呼び出し元がこの2つの状態で区別する（CMP-261013-1226）。
   */
  const roleResolved = ref(false)
  /** 直近のロール解決が失敗したか（roleResolved=true かつ true のとき取得失敗）。 */
  const roleLoadFailed = ref(false)

  const resolvedId = computed(() => isRef(scopeId) ? scopeId.value : scopeId)

  type LoadResult = { ok: true } | { ok: false; error: unknown }

  /**
   * 「最後の要求が勝つ」ための要求番号（CMP-261001-0835 検分修繕2）。
   * 永続シェルでのスコープ遷移やフォロー解除後の再取得で要求が重なると、古い応答が後から
   * 返って roleName/権限を上書きしうる。反映前に「最新の要求か・要求時のスコープのままか」を
   * 判定し、古い応答は反映しない。
   */
  let requestSeq = 0
  let latestRequest: Promise<LoadResult> | null = null

  /**
   * 権限取得の成否。
   *
   * - `ok: true`  … 正常応答（roleName が確定。MEMBER 等の「権限不足」も含む正常結果）
   * - `ok: false` … 取得失敗（例外・タイムアウト・5xx）。roleName は null のまま。
   *
   * F10.1.1 §5.2 / 05_decisions §5: 「権限不足（正常に false）」と「取得失敗」を
   * 区別するために導入。無言 catch で取得失敗を「権限なし」に倒すと、BE 障害時に
   * 管理者を誤って弾く（症状を隠す対処療法）ため、成否を呼び出し元へ正直に返す。
   *
   * 既存呼び出し元は戻り値を無視すれば従来どおり動作する（後方互換）。
   */
  function loadPermissions(): Promise<LoadResult> {
    // scopeId 未確定時は取得を行わない。判定材料が無いだけで「失敗」ではないため ok: true。
    if (!resolvedId.value) return Promise.resolve({ ok: true })
    const seq = ++requestSeq
    const request = runLoad(seq, resolvedId.value)
    latestRequest = request
    return request
  }

  async function runLoad(seq: number, requestedId: string): Promise<LoadResult> {
    const isCurrent = () => seq === requestSeq && resolvedId.value === requestedId
    loading.value = true
    let result: LoadResult
    try {
      const base = scopeType === 'team' ? 'teams' : 'organizations'
      const response = await api<{ data: EffectivePermissions }>(
        `/api/v1/${base}/${requestedId}/me/permissions`,
      )
      if (isCurrent()) {
        permissions.value = response.data.permissions
        roleName.value = response.data.roleName
        scopeAdminFromApi.value = response.data.scopeAdmin ?? null
        roleResolved.value = true
        roleLoadFailed.value = false
        canGrantFromApi.value = response.data.canGrantAdminOnlyPermissions ?? null
      }
      result = { ok: true }
    }
    catch (error) {
      // 取得失敗。権限なしに倒さず、roleName=null かつ ok=false を返す（症状を隠さない）。
      if (isCurrent()) {
        permissions.value = []
        roleName.value = null
        scopeAdminFromApi.value = null
        roleResolved.value = true
        roleLoadFailed.value = true
        canGrantFromApi.value = null
      }
      result = { ok: false, error }
    }
    finally {
      if (seq === requestSeq) loading.value = false
    }
    // 後発の要求に追い越された場合は、呼び出し元にも最新要求の成否を返す
    // （古い要求の成否で同期失敗を判定させない）。
    if (seq !== requestSeq && latestRequest) return latestRequest
    return result
  }

  const can = (permission: string): boolean => {
    return permissions.value.includes(permission)
  }

  const isAdmin = computed(() =>
    roleName.value === 'ADMIN' || roleName.value === 'SYSTEM_ADMIN',
  )

  /**
   * 対象スコープの ADMIN 割当があるか。スコープ ADMIN 専用の画面・操作の出し分けに使う
   * （BE が対象スコープの ADMIN 割当で 403 を返す操作）。isAdmin は SYSTEM_ADMIN も含むため使わない。
   * scopeAdmin を返さない旧 BE では roleName === 'ADMIN' で代用する（SYSTEM_ADMIN は不可）。
   */
  const isScopeAdmin = computed(() =>
    scopeAdminFromApi.value ?? roleName.value === 'ADMIN',
  )

  /**
   * ADMIN 専用権限を含む権限グループを操作できるか（BE の checkScopeAdminOnly と同じ結果を BE が返す）。
   * 返さない旧 BE では roleName === 'ADMIN'（SYSTEM_ADMIN は roleName が SYSTEM_ADMIN になるため不可）で代用する。
   */
  const canGrantAdminOnlyPermissions = computed(() =>
    canGrantFromApi.value ?? roleName.value === 'ADMIN',
  )

  const isAdminOrDeputy = computed(() =>
    isAdmin.value || roleName.value === 'DEPUTY_ADMIN',
  )

  /** ADMIN/DEPUTY_ADMIN/MEMBER は書き込み可（SUPPORTER/GUEST は不可） */
  const isMember = computed(() =>
    isAdminOrDeputy.value || roleName.value === 'MEMBER',
  )

  // scope/本人切替の瞬間に旧権限を破棄し、旧応答も反映させない。
  function invalidatePermissions() {
    ++requestSeq
    latestRequest = null
    roleName.value = null
    permissions.value = []
    scopeAdminFromApi.value = null
    canGrantFromApi.value = null
    roleResolved.value = false
    roleLoadFailed.value = false
    loading.value = false
  }
  if (isRef(scopeId) && options.watchScope !== false) {
    watch(scopeId, (newId) => {
      invalidatePermissions()
      if (newId) void loadPermissions()
    }, { flush: 'sync' })
  }
  watch(() => auth.user?.id, (userId) => {
    invalidatePermissions()
    if (userId && resolvedId.value) void loadPermissions()
  }, { flush: 'sync' })

  return {
    permissions,
    roleName,
    roleResolved,
    roleLoadFailed,
    loading,
    loadPermissions,
    can,
    isAdmin,
    isScopeAdmin,
    canGrantAdminOnlyPermissions,
    isAdminOrDeputy,
    isMember,
  }
}
