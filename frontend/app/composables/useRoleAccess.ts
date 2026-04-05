type RoleName = 'SYSTEM_ADMIN' | 'ADMIN' | 'DEPUTY_ADMIN' | 'MEMBER' | 'SUPPORTER' | 'GUEST'

interface EffectivePermissions {
  roleName: RoleName
  permissions: string[]
}

export function useRoleAccess(scopeType: 'team' | 'organization', scopeId: Ref<number> | number) {
  const api = useApi()
  const permissions = ref<string[]>([])
  const roleName = ref<RoleName | null>(null)
  const loading = ref(false)

  const resolvedId = computed(() => isRef(scopeId) ? scopeId.value : scopeId)

  async function loadPermissions() {
    if (!resolvedId.value) return
    loading.value = true
    try {
      const base = scopeType === 'team' ? 'teams' : 'organizations'
      const response = await api<{ data: EffectivePermissions }>(
        `/api/v1/${base}/${resolvedId.value}/me/permissions`,
      )
      permissions.value = response.data.permissions
      roleName.value = response.data.roleName
    }
    catch {
      permissions.value = []
      roleName.value = null
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
  }
}
