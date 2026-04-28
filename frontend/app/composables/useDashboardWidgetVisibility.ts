import type { Ref } from 'vue'
import type {
  WidgetVisibilityResponse,
  WidgetVisibilitySetting,
  WidgetVisibilityUpdate,
} from '~/types/dashboard'

export function useDashboardWidgetVisibility(
  scopeType: 'team' | 'organization',
  scopeId: Ref<number>,
) {
  const api = useApi()
  const settings = ref<WidgetVisibilitySetting[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)

  function endpoint(): string {
    return `/api/v1/dashboard/${scopeType}/${scopeId.value}/widget-visibility`
  }

  async function fetch(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const res = await api<WidgetVisibilityResponse>(endpoint())
      settings.value = res.data.widgets
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function save(updates: WidgetVisibilityUpdate[]): Promise<void> {
    saving.value = true
    error.value = null
    try {
      const res = await api<WidgetVisibilityResponse>(endpoint(), {
        method: 'PUT',
        body: { widgets: updates },
      })
      settings.value = res.data.widgets
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      saving.value = false
    }
  }

  return { settings, loading, saving, error, fetch, save }
}
