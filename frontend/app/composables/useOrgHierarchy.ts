import type { Ref } from 'vue'
import type {
  AncestorOrganization,
  AncestorsResponse,
  ChildOrganization,
  ChildrenResponse,
} from '~/types/organization'

/**
 * F01.2 組織階層 — 祖先（パンくず用）と直近の子組織を扱う Composable。
 *
 * - GET /api/v1/organizations/{id}/ancestors  — root → 親順
 * - GET /api/v1/organizations/{id}/children    — 直近の子組織（カーソルページネーション）
 */
export function useOrgHierarchy(orgId: Ref<number>) {
  const api = useApi()
  const { handleApiError } = useErrorHandler()

  const ancestors = ref<AncestorOrganization[]>([])
  const ancestorsMeta = ref<{ depth: number; truncated: boolean }>({
    depth: 0,
    truncated: false,
  })
  const children = ref<ChildOrganization[]>([])
  const loading = ref(false)
  const childrenCursor = ref<string | null>(null)
  const childrenHasNext = ref(false)

  async function fetchAncestors() {
    try {
      const result = await api<AncestorsResponse>(
        `/api/v1/organizations/${orgId.value}/ancestors`,
      )
      ancestors.value = result.data ?? []
      ancestorsMeta.value = result.meta ?? { depth: 0, truncated: false }
    } catch (error) {
      // 403（PRIVATE 非所属など）・404 は祖先非表示として扱う
      ancestors.value = []
      ancestorsMeta.value = { depth: 0, truncated: false }
      handleApiError(error, '上位組織取得')
    }
  }

  async function fetchChildren(reset = true) {
    if (reset) {
      children.value = []
      childrenCursor.value = null
      childrenHasNext.value = false
    }

    loading.value = true
    try {
      const query = new URLSearchParams()
      if (childrenCursor.value) {
        query.set('cursor', childrenCursor.value)
      }
      const url = `/api/v1/organizations/${orgId.value}/children${
        query.toString() ? `?${query}` : ''
      }`
      const result = await api<ChildrenResponse>(url)

      children.value = reset
        ? (result.data ?? [])
        : [...children.value, ...(result.data ?? [])]
      childrenCursor.value = result.meta?.nextCursor ?? null
      childrenHasNext.value = result.meta?.hasNext ?? false
    } catch (error) {
      handleApiError(error, '下位組織取得')
    } finally {
      loading.value = false
    }
  }

  return {
    ancestors,
    ancestorsMeta,
    children,
    loading,
    childrenCursor,
    childrenHasNext,
    fetchAncestors,
    fetchChildren,
  }
}
