import type { ProxyInputConsent, ProxyInputRecord } from '~/types/proxy-input'
import type { ApiResponse } from '~/types/api'

interface ManagedOrganization {
  id: number
  slug: string
  name: string
  role: string
  isArchived?: boolean
}

/** 管理対象の組合と権限を確定してから取得する。初回と再試行は同じ経路を通す。 */
export function useProxyAdmin(mode: 'consents' | 'records') {
  const api = useApi()
  const proxyApi = useProxyInputApi()
  const authStore = useAuthStore()
  const { handleApiError } = useErrorHandler()
  const organizations = ref<ManagedOrganization[]>([])
  const organizationSlug = ref('')
  const organizationId = ref('')
  const loading = ref(true)
  const error = shallowRef<unknown>()
  const consents = ref<ProxyInputConsent[]>([])
  const records = ref<ProxyInputRecord[]>([])
  const pagination = usePagination()
  const canApprove = ref(false)
  let requestVersion = 0
  let disposed = false

  async function loadPage() {
    if (disposed) return
    const version = ++requestVersion
    loading.value = true
    error.value = undefined
    canApprove.value = false
    try {
      const response = await api<ApiResponse<ManagedOrganization[]>>('/api/v1/me/organizations')
      if (version !== requestVersion) return
      organizations.value = response.data.filter(
        (org) => !org.isArchived && (org.role === 'ADMIN' || org.role === 'DEPUTY_ADMIN'),
      )
      const organization =
        organizations.value.find((org) => org.slug === organizationSlug.value) ??
        organizations.value[0]
      if (!organization) {
        error.value = createError({ statusCode: 403 })
        return
      }
      organizationSlug.value = organization.slug
      // スコープ取得失敗を権限不足と混同せず、そのまま再試行可能なエラーにする。
      const permissionResponse = await api<
        ApiResponse<{ roleName: string; permissions: string[] }>
      >(`/api/v1/organizations/${organization.slug}/me/permissions`)
      if (version !== requestVersion) return
      const { roleName, permissions } = permissionResponse.data
      if (roleName !== 'ADMIN' && roleName !== 'DEPUTY_ADMIN') {
        error.value = createError({ statusCode: 403 })
        return
      }
      canApprove.value = permissions.includes('PROXY_CONSENT_APPROVE')
      const numericId = String(organization.id)
      organizationId.value = numericId
      const params = { page: pagination.page.value, size: pagination.rows.value }
      if (mode === 'consents') {
        const page = await proxyApi.getConsentsByOrg(numericId, params)
        if (version !== requestVersion) return
        consents.value = page.data
        pagination.totalRecords.value = page.meta.total
      } else {
        const page = await proxyApi.getRecords({ organizationId: Number(numericId), ...params })
        if (version !== requestVersion) return
        records.value = page.data
        pagination.totalRecords.value = page.meta.total
      }
    } catch (cause) {
      if (version !== requestVersion) return
      error.value = cause
      handleApiError(cause, '代理入力管理一覧取得')
    } finally {
      if (version === requestVersion) loading.value = false
    }
  }

  function changeOrganization(slug: string) {
    if (disposed) return
    organizationSlug.value = slug
    pagination.reset()
    consents.value = []
    records.value = []
    return loadPage()
  }

  function changePage(event: { page: number; rows: number }) {
    if (disposed) return
    pagination.onPage(event)
    return loadPage()
  }

  function mayApprove(consent: ProxyInputConsent): boolean {
    return (
      !disposed &&
      !loading.value &&
      error.value === undefined &&
      canApprove.value &&
      consent.organizationId === Number(organizationId.value) &&
      consent.proxyUserId !== authStore.user?.id &&
      consent.status === 'PENDING_APPROVAL' &&
      !consent.revokedAt &&
      !consent.approvedAt
    )
  }

  function mayRevoke(consent: ProxyInputConsent): boolean {
    return (
      !disposed &&
      !loading.value &&
      error.value === undefined &&
      consent.organizationId === Number(organizationId.value) &&
      !consent.revokedAt
    )
  }

  onMounted(loadPage)
  onBeforeUnmount(() => {
    disposed = true
    requestVersion++
  })

  return {
    organizations,
    organizationSlug,
    organizationId,
    loading,
    error,
    consents,
    records,
    pagination,
    loadPage,
    changeOrganization,
    changePage,
    mayApprove,
    mayRevoke,
  }
}
