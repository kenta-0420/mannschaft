import type { JoinRequestResponse, JoinRequestScopeType } from '~/composables/useJoinRequestApi'

const PAGE_SIZE = 20

/**
 * 参加申請の ADMIN 向け審査一覧・承認/却下を扱うページレベル composable。
 * `useSupporterManagement` と同型（金型: `frontend/app/composables/useSupporterManagement.ts`）。
 *
 * Codex 検分 CMP-260901-1538 是正:
 *  - 第1巡 P1-2: 常に page=0/size=20 固定で `totalElements` を捨てていたため、
 *    PENDING が21件を超えると残りを画面から審査できなかった。ページ状態を保持し、
 *    「さらに読み込む」で追加取得できるようにする。バッジは `totalElements` を使う。
 *  - 第1巡 P1-3: 取得失敗時に `requests=[]` としていたため、失敗と「0件」が画面上区別できず
 *    「承認待ちの参加申請はありません」という誤った空状態が出ていた。`requestsError` を
 *    独立して保持し、失敗時は空状態を表示させない。
 *  - 第2巡 P1-3: 追加取得（`loadMore`）と承認・却下後の先頭ページ再取得が競合すると、
 *    後着した古い応答が新しい一覧へ追記され、行の重複・審査済み行の復活・件数不整合が
 *    起きていた。取得世代番号を保持し、自分より新しい取得が既に始まっていれば応答を
 *    破棄する。追記時も ID で重複排除する。
 */
export function useJoinRequestManagement(scopeType: Ref<JoinRequestScopeType>, scopeId: Ref<number>) {
  const { listJoinRequestsForReview, approveJoinRequest, rejectJoinRequest } = useJoinRequestApi()
  const notification = useNotification()
  // useI18n() は setup コンテキスト外（本 composable のような素の関数呼び出し）では
  // 呼べないため、useApi.ts と同様に useNuxtApp().$i18n 経由でアクセスする。
  const t = (key: string) => useNuxtApp().$i18n.t(key)
  const { handleApiError } = useErrorHandler()

  const requests = ref<JoinRequestResponse[]>([])
  const requestsLoading = ref(false)
  const requestsError = ref(false)
  const processingIds = ref<string[]>([])
  const totalElements = ref(0)
  const page = ref(0)
  let fetchSeq = 0

  const hasMore = computed(() => requests.value.length < totalElements.value)

  /** append=true のときは次ページを追記取得し、それ以外は先頭ページからやり直す。 */
  async function fetchRequests(append = false) {
    const seq = ++fetchSeq
    const targetPage = append ? page.value + 1 : 0
    requestsLoading.value = true
    requestsError.value = false
    try {
      const res = await listJoinRequestsForReview(scopeType.value, scopeId.value, {
        status: 'PENDING',
        page: targetPage,
        size: PAGE_SIZE,
      })
      // 自分より新しい取得が既に始まっていれば、後着した古い応答は破棄する
      // （page1 取得中に承認→先頭ページ再取得→旧 page1 応答が後着、のような競合を防ぐ）。
      if (seq !== fetchSeq) return
      if (append) {
        const existingIds = new Set(requests.value.map(r => r.id))
        const newOnes = res.data.content.filter(r => !existingIds.has(r.id))
        requests.value = [...requests.value, ...newOnes]
      }
      else {
        requests.value = res.data.content
      }
      totalElements.value = res.data.totalElements
      page.value = targetPage
    }
    catch (error) {
      if (seq !== fetchSeq) return
      handleApiError(error, '参加申請一覧取得')
      requestsError.value = true
      if (!append) {
        requests.value = []
        totalElements.value = 0
        page.value = 0
      }
    }
    finally {
      if (seq === fetchSeq) requestsLoading.value = false
    }
  }

  /** 追加読み込み（次ページ取得）。既に全件取得済み・取得中なら何もしない。 */
  async function loadMore() {
    if (!hasMore.value || requestsLoading.value) return
    await fetchRequests(true)
  }

  /** 一覧が空 or 取得失敗のときは先頭ページから、それ以外は追加読み込みを再試行する。 */
  async function retry() {
    if (requests.value.length === 0) {
      await fetchRequests()
    }
    else {
      await loadMore()
    }
  }

  async function approve(id: string) {
    processingIds.value.push(id)
    try {
      await approveJoinRequest(scopeType.value, scopeId.value, id)
      notification.success(t('joinRequest.admin.approveSuccess'))
      await fetchRequests()
    }
    catch (error) {
      handleApiError(error, '参加申請承認')
    }
    finally {
      processingIds.value = processingIds.value.filter(pid => pid !== id)
    }
  }

  async function reject(id: string) {
    processingIds.value.push(id)
    try {
      await rejectJoinRequest(scopeType.value, scopeId.value, id)
      notification.success(t('joinRequest.admin.rejectSuccess'))
      await fetchRequests()
    }
    catch (error) {
      handleApiError(error, '参加申請却下')
    }
    finally {
      processingIds.value = processingIds.value.filter(pid => pid !== id)
    }
  }

  async function init() {
    await fetchRequests()
  }

  return {
    requests,
    requestsLoading,
    requestsError,
    processingIds,
    totalElements,
    hasMore,
    approve,
    reject,
    loadMore,
    retry,
    init,
  }
}
