import type { BulletinCategory } from '~/types/bulletin'

/**
 * 掲示板カテゴリ関連 API（グローバル / スコープ別）。
 *
 * - グローバル: `/api/v1/bulletin/categories`
 * - スコープ別: `/api/v1/{scopeType}/{scopeId}/bulletin/categories`
 */
export function useBulletinCategories() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        query.set(key, String(value))
      }
    }
    return query.toString()
  }

  // === Categories ===
  async function getCategories(scopeType: string, scopeId: string | number) {
    const isVillage = scopeType === 'VILLAGE'
    const qs = buildQuery({
      scope_type: scopeType,
      scope_id: isVillage ? 0 : scopeId,
      ...(isVillage ? { scope_village_id: scopeId } : {}),
    })
    return api<{ data: BulletinCategory[] }>(`/api/v1/bulletin/categories?${qs}`)
  }

  async function createCategory(body: Record<string, unknown>) {
    return api<{ data: BulletinCategory }>('/api/v1/bulletin/categories', { method: 'POST', body })
  }

  async function updateCategory(categoryId: number, body: Record<string, unknown>) {
    return api<{ data: BulletinCategory }>(`/api/v1/bulletin/categories/${categoryId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteCategory(categoryId: number) {
    return api(`/api/v1/bulletin/categories/${categoryId}`, { method: 'DELETE' })
  }

  // === Scoped Categories ===
  async function getScopedCategories(scopeType: string, scopeId: string) {
    return api<{ data: BulletinCategory[] }>(`/api/v1/${scopeType}/${scopeId}/bulletin/categories`)
  }

  async function createScopedCategory(
    scopeType: string,
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: BulletinCategory }>(`/api/v1/${scopeType}/${scopeId}/bulletin/categories`, {
      method: 'POST',
      body,
    })
  }

  async function getScopedCategory(scopeType: string, scopeId: string, categoryId: number) {
    return api<{ data: BulletinCategory }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/categories/${categoryId}`,
    )
  }

  async function updateScopedCategory(
    scopeType: string,
    scopeId: string,
    categoryId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: BulletinCategory }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/categories/${categoryId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteScopedCategory(scopeType: string, scopeId: string, categoryId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/categories/${categoryId}`, {
      method: 'DELETE',
    })
  }

  return {
    getCategories,
    createCategory,
    updateCategory,
    deleteCategory,
    getScopedCategories,
    createScopedCategory,
    getScopedCategory,
    updateScopedCategory,
    deleteScopedCategory,
  }
}
