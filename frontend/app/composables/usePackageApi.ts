import type {
  PackageResponse,
  CreatePackageRequest,
  UpdatePackageRequest,
} from '~/types/package'

const BASE = '/api/v1/system-admin/packages'

export function usePackageApi() {
  const api = useApi()

  async function getPackages() {
    return api<{ data: PackageResponse[] }>(BASE)
  }

  async function getPackage(id: number) {
    return api<{ data: PackageResponse }>(`${BASE}/${id}`)
  }

  async function createPackage(body: CreatePackageRequest) {
    return api<{ data: PackageResponse }>(BASE, { method: 'POST', body })
  }

  async function updatePackage(id: number, body: UpdatePackageRequest) {
    return api<{ data: PackageResponse }>(`${BASE}/${id}`, { method: 'PUT', body })
  }

  async function deletePackage(id: number) {
    return api(`${BASE}/${id}`, { method: 'DELETE' })
  }

  async function togglePublish(id: number) {
    return api<{ data: PackageResponse }>(`${BASE}/${id}/publish`, { method: 'PATCH' })
  }

  return {
    getPackages,
    getPackage,
    createPackage,
    updatePackage,
    deletePackage,
    togglePublish,
  }
}
