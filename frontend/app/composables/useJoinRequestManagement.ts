import type { JoinRequestResponse, JoinRequestScopeType } from '~/composables/useJoinRequestApi'

/**
 * 参加申請の ADMIN 向け審査一覧・承認/却下を扱うページレベル composable。
 * `useSupporterManagement` と同型（金型: `frontend/app/composables/useSupporterManagement.ts`）。
 */
export function useJoinRequestManagement(scopeType: Ref<JoinRequestScopeType>, scopeId: Ref<number>) {
  const { listJoinRequestsForReview, approveJoinRequest, rejectJoinRequest } = useJoinRequestApi()
  const notification = useNotification()
  const { handleApiError } = useErrorHandler()

  const requests = ref<JoinRequestResponse[]>([])
  const requestsLoading = ref(false)
  const processingIds = ref<string[]>([])

  async function fetchRequests() {
    requestsLoading.value = true
    try {
      const res = await listJoinRequestsForReview(scopeType.value, scopeId.value, { status: 'PENDING' })
      requests.value = res.data.content
    }
    catch (error) {
      handleApiError(error, '参加申請一覧取得')
      requests.value = []
    }
    finally {
      requestsLoading.value = false
    }
  }

  async function approve(id: string) {
    processingIds.value.push(id)
    try {
      await approveJoinRequest(scopeType.value, scopeId.value, id)
      notification.success('参加申請を承認しました')
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
      notification.success('参加申請を却下しました')
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
    processingIds,
    approve,
    reject,
    init,
  }
}
