import type {
  ModulePricingResponse,
  UpdateModulePricingRequest,
  ModulePricingHistoryResponse,
} from '~/types/module-pricing'

const BASE = '/api/v1/system-admin/module-pricing'

export function useModulePricingApi() {
  const api = useApi()

  async function getModulePricingList() {
    return api<{ data: ModulePricingResponse[] }>(BASE)
  }

  async function updateModulePricing(moduleId: number, body: UpdateModulePricingRequest) {
    return api<{ data: ModulePricingResponse }>(`${BASE}/${moduleId}`, {
      method: 'PUT',
      body,
    })
  }

  async function getModulePricingHistory(moduleId: number) {
    return api<{ data: ModulePricingHistoryResponse[] }>(`${BASE}/${moduleId}/history`)
  }

  return {
    getModulePricingList,
    updateModulePricing,
    getModulePricingHistory,
  }
}
