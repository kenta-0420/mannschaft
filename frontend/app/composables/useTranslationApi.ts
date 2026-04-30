import type {
  TranslationResponse,
  TranslationListResponse,
  TranslationStatus,
  CreateTranslationRequest,
  TranslationDashboard,
} from '~/types/translation'

export function useTranslationApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') query.set(key, String(value))
    }
    return query.toString()
  }

  async function listTranslations(
    orgId: number,
    params?: { status?: string; language?: string; sourceType?: string; page?: number; size?: number }
  ): Promise<TranslationListResponse> {
    const qs = buildQuery(params ?? {})
    return api<TranslationListResponse>(
      `/api/v1/organizations/${orgId}/translations${qs ? '?' + qs : ''}`
    )
  }

  async function getTranslation(orgId: number, id: number) {
    return api<TranslationResponse>(`/api/v1/organizations/${orgId}/translations/${id}`)
  }

  async function createTranslation(orgId: number, body: CreateTranslationRequest) {
    return api<TranslationResponse>(`/api/v1/organizations/${orgId}/translations`, {
      method: 'POST',
      body,
    })
  }

  async function updateStatus(orgId: number, id: number, status: TranslationStatus) {
    return api<TranslationResponse>(`/api/v1/organizations/${orgId}/translations/${id}/status`, {
      method: 'PUT',
      body: { status },
    })
  }

  async function publishTranslation(orgId: number, id: number) {
    return api<TranslationResponse>(`/api/v1/organizations/${orgId}/translations/${id}/publish`, {
      method: 'POST',
    })
  }

  async function getDashboard(orgId: number) {
    return api<TranslationDashboard>(`/api/v1/organizations/${orgId}/translations/dashboard`)
  }

  return {
    listTranslations,
    getTranslation,
    createTranslation,
    updateStatus,
    publishTranslation,
    getDashboard,
  }
}
