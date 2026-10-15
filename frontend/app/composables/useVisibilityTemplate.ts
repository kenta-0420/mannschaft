import type {
  VisibilityTemplateListResponse,
  VisibilityTemplateDetail,
  CreateVisibilityTemplateRequest,
} from '~/types/visibility-template'

/**
 * 公開範囲テンプレート管理 composable
 */
export function useVisibilityTemplate() {
  const api = useApi()

  const templates = ref<VisibilityTemplateListResponse | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** テンプレート一覧（システムプリセット＋ユーザー独自）を取得する */
  async function fetchTemplates(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const res = await api<{ data: VisibilityTemplateListResponse }>(
        '/api/v1/visibility-templates',
      )
      templates.value = res.data
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  /** テンプレートを新規作成する */
  async function createTemplate(
    request: CreateVisibilityTemplateRequest,
  ): Promise<VisibilityTemplateDetail> {
    loading.value = true
    error.value = null
    try {
      const res = await api<{ data: VisibilityTemplateDetail }>('/api/v1/visibility-templates', {
        method: 'POST',
        body: request,
      })
      return res.data
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  /** テンプレートを削除する */
  async function deleteTemplate(id: number): Promise<void> {
    loading.value = true
    error.value = null
    try {
      await api(`/api/v1/visibility-templates/${id}`, {
        method: 'DELETE',
      })
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  return {
    templates,
    loading,
    error,
    fetchTemplates,
    createTemplate,
    deleteTemplate,
  }
}
