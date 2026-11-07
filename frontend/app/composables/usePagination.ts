// ページネーション状態を集約する composable
// PrimeVue DataTable の @page イベントに対応
export function usePagination(defaultRows = 20) {
  const page = ref(0)
  const rows = ref(defaultRows)
  const totalRecords = ref(0)

  function onPage(event: { page: number; rows: number }) {
    page.value = event.page
    rows.value = event.rows
  }

  function reset() {
    page.value = 0
  }

  /** 成功した空ページだけを最終有効ページへ補正し、再取得は1回に限る。 */
  async function loadWithPageCorrection<T extends {
    data?: unknown[]
    pagination?: { totalElements?: number; totalPages?: number }
  }>(fetchPage: (page: number, size: number) => Promise<T>): Promise<T> {
    let response = await fetchPage(page.value, rows.value)
    if ((response.data?.length ?? 0) === 0 && page.value > 0) {
      if ((response.pagination?.totalElements ?? 0) > 0) {
        const correctedPage = Math.max(0, (response.pagination?.totalPages ?? 1) - 1)
        response = await fetchPage(correctedPage, rows.value)
        page.value = correctedPage
      }
      else {
        page.value = 0
      }
    }
    if (response.pagination?.totalElements === 0) page.value = 0
    totalRecords.value = response.pagination?.totalElements ?? 0
    return response
  }

  return { page, rows, totalRecords, onPage, reset, loadWithPageCorrection }
}
