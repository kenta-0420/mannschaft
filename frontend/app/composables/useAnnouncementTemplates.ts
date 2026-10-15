import type { ApiResponse } from '~/types/api'
import type {
  AnnouncementScopeType,
} from '~/types/announcement'
import type {
  AnnouncementTemplate,
  AnnouncementTemplateRequest,
} from '~/types/announcement_broadcast'

/**
 * F02.8 告知ウィザード 範囲テンプレート composable。
 *
 * GET/POST/PUT/DELETE /api/v1/{scopeType}/{scopeId}/announcement-templates を管理する。
 *
 * @param scopeType スコープ種別（TEAM / ORGANIZATION）
 * @param scopeId   スコープ ID
 */
export function useAnnouncementTemplates(scopeType: AnnouncementScopeType, scopeId: string) {
  const api = useApi()
  const templates = ref<AnnouncementTemplate[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const templateError = ref<string | null>(null)

  function basePath() {
    const scope = scopeType === 'TEAM' ? 'teams' : 'organizations'
    return `/api/v1/${scope}/${scopeId}/announcement-templates`
  }

  /** テンプレート一覧を取得する。 */
  async function fetchTemplates(): Promise<void> {
    loading.value = true
    templateError.value = null
    try {
      const res = await api<ApiResponse<AnnouncementTemplate[]>>(basePath())
      templates.value = res.data
    }
    catch {
      templateError.value = 'テンプレートの取得に失敗しました'
    }
    finally {
      loading.value = false
    }
  }

  /** テンプレートを作成する（ADMIN のみ）。 */
  async function createTemplate(request: AnnouncementTemplateRequest): Promise<AnnouncementTemplate> {
    saving.value = true
    templateError.value = null
    try {
      const res = await api<ApiResponse<AnnouncementTemplate>>(basePath(), {
        method: 'POST',
        body: request,
      })
      templates.value = [...templates.value, res.data]
      return res.data
    }
    catch {
      templateError.value = 'テンプレートの保存に失敗しました'
      throw templateError.value
    }
    finally {
      saving.value = false
    }
  }

  /** テンプレートを更新する（ADMIN のみ）。 */
  async function updateTemplate(
    templateId: number,
    request: AnnouncementTemplateRequest,
  ): Promise<AnnouncementTemplate> {
    saving.value = true
    templateError.value = null
    try {
      const res = await api<ApiResponse<AnnouncementTemplate>>(`${basePath()}/${templateId}`, {
        method: 'PUT',
        body: request,
      })
      const idx = templates.value.findIndex(t => t.id === templateId)
      if (idx !== -1) templates.value[idx] = res.data
      return res.data
    }
    catch {
      templateError.value = 'テンプレートの更新に失敗しました'
      throw templateError.value
    }
    finally {
      saving.value = false
    }
  }

  /** テンプレートを削除する（ADMIN のみ）。 */
  async function deleteTemplate(templateId: number): Promise<void> {
    saving.value = true
    templateError.value = null
    try {
      await api(`${basePath()}/${templateId}`, { method: 'DELETE' })
      templates.value = templates.value.filter(t => t.id !== templateId)
    }
    catch {
      templateError.value = 'テンプレートの削除に失敗しました'
      throw templateError.value
    }
    finally {
      saving.value = false
    }
  }

  /** デフォルトテンプレートを返す（存在しなければ null）。 */
  const defaultTemplate = computed(() =>
    templates.value.find(t => t.isDefault) ?? null,
  )

  return {
    templates,
    loading,
    saving,
    templateError,
    defaultTemplate,
    fetchTemplates,
    createTemplate,
    updateTemplate,
    deleteTemplate,
  }
}
