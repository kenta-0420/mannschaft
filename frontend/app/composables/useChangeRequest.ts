import type { Ref } from 'vue'
import type { ChangeRequest, CreateChangeRequestPayload } from '~/types/shift'

/**
 * シフト変更依頼の取得・作成・審査・取下を管理する composable。
 * F03.5 §A-1確定前変更 / A-2個別交代 / A-3オープンコール に対応。
 */
export function useChangeRequest(scheduleId: Ref<number>) {
  const shiftApi = useShiftApi()
  const { showError, showSuccess } = useNotification()
  const { t } = useI18n()

  const requests = ref<ChangeRequest[]>([])
  const isLoading = ref(false)

  /** 変更依頼一覧を取得する */
  async function fetchRequests(): Promise<void> {
    isLoading.value = true
    try {
      requests.value = await shiftApi.listChangeRequests(scheduleId.value)
      // OPEN の依頼を先頭に並べる
      requests.value.sort((a, b) => {
        if (a.status === 'OPEN' && b.status !== 'OPEN') return -1
        if (a.status !== 'OPEN' && b.status === 'OPEN') return 1
        return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
      })
    } catch {
      showError(t('shift.changeRequest.fetchError'))
    } finally {
      isLoading.value = false
    }
  }

  /** 変更依頼を作成する */
  async function createRequest(payload: CreateChangeRequestPayload): Promise<void> {
    try {
      const created = await shiftApi.createChangeRequest(payload)
      requests.value.unshift(created)
      showSuccess(t('shift.changeRequest.submit'))
    } catch {
      showError(t('shift.changeRequest.fetchError'))
      throw new Error('createRequest failed')
    }
  }

  /** 変更依頼を審査する（ADMIN のみ） */
  async function review(
    id: number,
    decision: 'ACCEPTED' | 'REJECTED',
    comment?: string,
    version = 0,
  ): Promise<void> {
    try {
      const updated = await shiftApi.reviewChangeRequest(id, {
        decision,
        reviewComment: comment,
        version,
      })
      const idx = requests.value.findIndex((r) => r.id === id)
      if (idx !== -1) {
        requests.value[idx] = updated
      }
      showSuccess(
        decision === 'ACCEPTED'
          ? t('shift.changeRequest.approve')
          : t('shift.changeRequest.reject'),
      )
    } catch {
      showError(t('shift.changeRequest.fetchError'))
      throw new Error('review failed')
    }
  }

  /** 変更依頼を取り下げる（依頼者のみ） */
  async function withdraw(id: number): Promise<void> {
    try {
      await shiftApi.withdrawChangeRequest(id)
      const idx = requests.value.findIndex((r) => r.id === id)
      if (idx !== -1) {
        requests.value[idx] = { ...requests.value[idx], status: 'WITHDRAWN' }
      }
      showSuccess(t('shift.changeRequest.withdraw'))
    } catch {
      showError(t('shift.changeRequest.fetchError'))
      throw new Error('withdraw failed')
    }
  }

  return { requests, isLoading, fetchRequests, createRequest, review, withdraw }
}
