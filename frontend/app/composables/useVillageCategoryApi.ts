import type { VillageCategoryRequest, VillageCategoryResponse } from '~/types/villageCategory'

/**
 * 村カテゴリ API composable
 *
 * Backend Controller: VillageCategoryController / SystemAdminVillageCategoryController
 * API:
 *   GET    /api/v1/village-categories                      ← 一般ユーザー向け一覧（ツリー構造）
 *   GET    /api/v1/system-admin/village-categories         ← SYSTEM_ADMIN 向け一覧
 *   POST   /api/v1/system-admin/village-categories         ← 作成
 *   PUT    /api/v1/system-admin/village-categories/{id}    ← 更新
 *   DELETE /api/v1/system-admin/village-categories/{id}    ← 論理削除（204 No Content）
 */
export function useVillageCategoryApi() {
  const api = useApi()

  /** 一般ユーザー向け: 村カテゴリ一覧取得（ツリー構造） */
  async function fetchCategories(): Promise<VillageCategoryResponse[]> {
    return api<VillageCategoryResponse[]>('/api/v1/village-categories')
  }

  /** SYSTEM_ADMIN 向け: 村カテゴリ一覧取得（ツリー構造） */
  async function fetchAdminCategories(): Promise<VillageCategoryResponse[]> {
    return api<VillageCategoryResponse[]>('/api/v1/system-admin/village-categories')
  }

  /** SYSTEM_ADMIN 向け: 村カテゴリ作成 */
  async function createCategory(req: VillageCategoryRequest): Promise<VillageCategoryResponse> {
    return api<VillageCategoryResponse>('/api/v1/system-admin/village-categories', {
      method: 'POST',
      body: req,
    })
  }

  /** SYSTEM_ADMIN 向け: 村カテゴリ更新 */
  async function updateCategory(id: string, req: VillageCategoryRequest): Promise<VillageCategoryResponse> {
    return api<VillageCategoryResponse>(`/api/v1/system-admin/village-categories/${id}`, {
      method: 'PUT',
      body: req,
    })
  }

  /** SYSTEM_ADMIN 向け: 村カテゴリ論理削除（204 No Content） */
  async function deleteCategory(id: string): Promise<void> {
    await api(`/api/v1/system-admin/village-categories/${id}`, { method: 'DELETE' })
  }

  return {
    fetchCategories,
    fetchAdminCategories,
    createCategory,
    updateCategory,
    deleteCategory,
  }
}
