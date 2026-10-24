import type { FriendRequestSource } from '~/types/friends'

/** 相手・ページ・開閉世代が一致する候補だけを適用する。 */
export function useFriendRequestSources(targetSlug: Ref<string>, opened: Ref<boolean>) {
  const { listSourceTeams } = useFriendTeamsApi()
  const candidates = ref<FriendRequestSource[]>([])
  const selected = ref<FriendRequestSource | null>(null)
  const loading = ref(false)
  const error = shallowRef<unknown>(null)
  const { page, rows, totalRecords } = usePagination()
  let generation = 0

  async function load(corrected = false, autoSelect = true): Promise<void> {
    const current = ++generation
    const slug = targetSlug.value
    const requestedPage = page.value
    const valid = () => current === generation && opened.value && targetSlug.value === slug && page.value === requestedPage
    if (!opened.value || !slug) return
    loading.value = true
    error.value = null
    try {
      const result = await listSourceTeams(slug, { page: requestedPage, size: rows.value })
      if (!valid()) return
      if (!result.data.length && result.pagination.totalElements > 0 && requestedPage > 0 && !corrected) {
        page.value = 0
        await load(true, autoSelect)
        return
      }
      candidates.value = result.data
      totalRecords.value = result.pagination.totalElements
      if (totalRecords.value === 0) { page.value = 0; selected.value = null }
      // 総数が1のときだけ自動選択。ページ内の1件を全候補と誤認しない。
      if (autoSelect && totalRecords.value === 1 && result.data.length === 1) selected.value = result.data[0]!
    }
    catch (cause) { if (valid()) error.value = cause }
    finally { if (current === generation) loading.value = false }
  }

  function reset() {
    generation += 1
    candidates.value = []
    selected.value = null
    page.value = 0
    totalRecords.value = 0
    error.value = null
    loading.value = false
  }
  watch([targetSlug, opened], () => { reset(); if (opened.value) void load() }, { immediate: true })
  onBeforeUnmount(() => { generation += 1 })
  return { candidates, selected, loading, error, page, rows, totalRecords, load, reset }
}
