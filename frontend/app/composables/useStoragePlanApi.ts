import type {
  StoragePlanResponse,
  CreateStoragePlanRequest,
  UpdateStoragePlanRequest,
  TeamStorageUsageResponse,
} from '~/types/storage-plan'

const BASE = '/api/v1/system-admin/storage-plans'

export function useStoragePlanApi() {
  const api = useApi()

  async function getStoragePlans() {
    return api<{ data: StoragePlanResponse[] }>(BASE)
  }

  async function getStoragePlan(id: number) {
    return api<{ data: StoragePlanResponse }>(`${BASE}/${id}`)
  }

  async function createStoragePlan(body: CreateStoragePlanRequest) {
    return api<{ data: StoragePlanResponse }>(BASE, { method: 'POST', body })
  }

  async function updateStoragePlan(id: number, body: UpdateStoragePlanRequest) {
    return api<{ data: StoragePlanResponse }>(`${BASE}/${id}`, { method: 'PUT', body })
  }

  async function deleteStoragePlan(id: number) {
    return api(`${BASE}/${id}`, { method: 'DELETE' })
  }

  async function getStorageUsage() {
    return api<{ data: TeamStorageUsageResponse[] }>('/api/v1/system-admin/storage-usage')
  }

  return {
    getStoragePlans,
    getStoragePlan,
    createStoragePlan,
    updateStoragePlan,
    deleteStoragePlan,
    getStorageUsage,
  }
}
