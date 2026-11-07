import type { PageResult } from '~/composables/useOrgTeamMembershipApi'

/**
 * ページ付き一覧の取得を「最後に発行した要求の応答だけ反映する」形で行う共通 composable。
 *
 * 絞り込み・view 切替・チーム切替・操作後の取り直しで要求が重なっても、古い応答が新しい表示を
 * 上書きしない。取得失敗は空一覧に見せず `loadFailed` で正直に返す（症状を隠さない）。
 * 最後のページの最後の 1 件を消した直後に空ページになった場合は、1 つ前のページへ戻して取り直す。
 */
export function useLatestPagedList<T>(fetchPage: (page: number) => Promise<PageResult<T>>) {
  const items = ref<T[]>([]) as Ref<T[]>
  const loading = ref(true)
  const loadFailed = ref(false)
  const page = ref(0)
  const total = ref(0)
  const totalPages = ref(1)

  let requestSeq = 0

  async function load(): Promise<void> {
    const seq = ++requestSeq
    loading.value = true
    loadFailed.value = false
    try {
      const result = await fetchPage(page.value)
      if (seq !== requestSeq) return
      items.value = result.items
      total.value = result.total
      totalPages.value = result.totalPages
      if (result.items.length === 0 && page.value > 0) {
        // page の watch が取り直す（loading は次の要求が解く）。
        page.value -= 1
        return
      }
    }
    catch {
      if (seq !== requestSeq) return
      items.value = []
      total.value = 0
      loadFailed.value = true
    }
    if (seq === requestSeq) loading.value = false
  }

  /** 先頭ページから取り直す（チーム切替・view 再表示用）。 */
  function reset(): void {
    if (page.value !== 0) {
      // page の watch が取得する。
      page.value = 0
      return
    }
    void load()
  }

  watch(page, () => void load())

  onBeforeUnmount(() => {
    // アンマウント後の応答で状態を更新しない。
    requestSeq++
  })

  return { items, loading, loadFailed, page, total, totalPages, load, reset }
}
