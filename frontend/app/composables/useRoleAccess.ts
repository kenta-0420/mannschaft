type RoleName = 'SYSTEM_ADMIN' | 'ADMIN' | 'DEPUTY_ADMIN' | 'MEMBER' | 'SUPPORTER' | 'GUEST'

interface EffectivePermissions {
  roleName: RoleName
  permissions: string[]
}

export function useRoleAccess(scopeType: 'team' | 'organization', scopeId: Ref<string> | string) {
  const api = useApi()
  const permissions = ref<string[]>([])
  const roleName = ref<RoleName | null>(null)
  const loading = ref(false)

  const resolvedId = computed(() => isRef(scopeId) ? scopeId.value : scopeId)

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
  async function loadPermissions(): Promise<{ ok: boolean }> {
    // scopeId 未確定時は取得を行わない。判定材料が無いだけで「失敗」ではないため ok: true。
    if (!resolvedId.value) return { ok: true }
    loading.value = true
    try {
      const base = scopeType === 'team' ? 'teams' : 'organizations'
      const response = await api<{ data: EffectivePermissions }>(
        `/api/v1/${base}/${resolvedId.value}/me/permissions`,
      )
      permissions.value = response.data.permissions
      roleName.value = response.data.roleName
      return { ok: true }
    }
    catch {
      // 取得失敗。権限なしに倒さず、roleName=null かつ ok=false を返す（症状を隠さない）。
      permissions.value = []
      roleName.value = null
      return { ok: false }
    }
    finally {
      loading.value = false
    }
  }

  const can = (permission: string): boolean => {
    return permissions.value.includes(permission)
  }

  const isAdmin = computed(() =>
    roleName.value === 'ADMIN' || roleName.value === 'SYSTEM_ADMIN',
  )

  const isAdminOrDeputy = computed(() =>
    isAdmin.value || roleName.value === 'DEPUTY_ADMIN',
  )

  /** ADMIN/DEPUTY_ADMIN/MEMBER は書き込み可（SUPPORTER/GUEST は不可） */
  const isMember = computed(() =>
    isAdminOrDeputy.value || roleName.value === 'MEMBER',
  )

  // scopeIdがリアクティブな場合、変更時に自動リロード
  if (isRef(scopeId)) {
    watch(scopeId, (newId) => {
      if (newId) loadPermissions()
    })
  }

  return {
    permissions,
    roleName,
    loading,
    loadPermissions,
    can,
    isAdmin,
    isAdminOrDeputy,
    isMember,
  }
}
