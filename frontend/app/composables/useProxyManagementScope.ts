import type { ApiResponse } from '~/types/api'

interface ProxyManagementOrganization {
  id: number
  slug: string
  name: string
  role: string
}

/** 同意管理の2画面で、現在の組合と取得失敗を区別して解決する。 */
export function useProxyManagementScope() {
  const api = useApi()
  const auth = useAuthStore()
  const scope = useScopeStore()
  const organizations = ref<ProxyManagementOrganization[]>([])
  const organization = ref<ProxyManagementOrganization | null>(null)
  const loading = ref(false)
  const failed = ref(false)
  const permissions = ref<string[]>([])
  let request = 0
  const allowed = computed(() => !auth.isSystemAdmin && organization.value !== null && !failed.value && !loading.value)
  const choices = computed(() => auth.isSystemAdmin ? [] : organizations.value.filter(o => o.role === 'ADMIN' || o.role === 'DEPUTY_ADMIN'))

  async function load() {
    const current = ++request
    organization.value = null
    permissions.value = []
    failed.value = false
    loading.value = true
    try {
      const response = await api<ApiResponse<ProxyManagementOrganization[]>>('/api/v1/me/organizations')
      if (current !== request) return
      organizations.value = response.data
      if (auth.isSystemAdmin || scope.current.type !== 'organization') return
      const found = response.data.find(o => String(o.id) === scope.current.id)
      if (!found) return
      // useRoleAccessと同じ応答を使い、scope切替前の遅い応答は採用しない。
      const result = await api<ApiResponse<{ roleName: string; permissions: string[] }>>(
        `/api/v1/organizations/${found.slug}/me/permissions`,
      )
      if (current !== request) return
      if (result.data.roleName !== 'ADMIN' && result.data.roleName !== 'DEPUTY_ADMIN') return
      organization.value = found
      permissions.value = result.data.permissions
    }
    catch {
      if (current === request) failed.value = true
    }
    finally {
      if (current === request) loading.value = false
    }
  }

  function select(id: number) {
    const found = choices.value.find(o => o.id === id)
    if (found) scope.setOrganizationScope(found.id, found.name)
  }

  onMounted(() => { void load() })
  watch(() => [scope.current.type, scope.current.id, auth.isSystemAdmin, auth.user?.id], () => { void load() })
  return { organizations: choices, organization, loading, failed, allowed, permissions, load, select }
}
