import type { StorageScopeUsage } from '~/types/storage'

/**
 * F13 ストレージ容量使用量 API — ユーザー向け
 * GET /api/v1/me/storage/usage
 */
export function useStorageUsageApi() {
  const api = useApi()

  async function getMyStorageUsage(): Promise<StorageScopeUsage[]> {
    return api<StorageScopeUsage[]>('/api/v1/me/storage/usage')
  }

  return {
    getMyStorageUsage,
  }
}
