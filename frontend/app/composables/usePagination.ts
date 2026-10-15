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

  return { page, rows, totalRecords, onPage, reset }
}
