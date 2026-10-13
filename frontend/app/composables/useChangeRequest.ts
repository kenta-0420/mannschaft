import type { Ref } from 'vue'
import type { ChangeRequest, CreateChangeRequestPayload } from '~/types/shift'

/**
 * シフト変更依頼の取得・作成・審査・取下を管理する composable。
 * F03.5 §A-1確定前変更 / A-2個別交代 / A-3オープンコール に対応。
 */
export function useChangeRequest(scheduleId: Ref<number>) {
  const shiftApi = useShiftApi()
  const { error: showError, success: showSuccess } = useNotification()
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
        if (a.reviewInfo.status === 'OPEN' && b.reviewInfo.status !== 'OPEN') return -1
        if (a.reviewInfo.status !== 'OPEN' && b.reviewInfo.status === 'OPEN') return 1
        return new Date(b.timing.createdAt).getTime() - new Date(a.timing.createdAt).getTime()
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
  ): Promise<void> {
    try {
      const target = requests.value.find((request) => request.id === id)
      if (!target || !Number.isSafeInteger(target.version) || target.version < 0) {
        throw new Error('変更依頼の版番号を取得し直してください')
      }
      const updated = await shiftApi.reviewChangeRequest(id, {
        decision,
        reviewComment: comment,
        version: target.version,
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
    } catch (error) {
      const apiError = error as { data?: { error?: { code?: string } } }
      showError(t(apiError?.data?.error?.code === 'SHIFT_018'
        ? 'error.COMMON_003'
        : 'shift.notification.errorUpdate'))
      throw error
    }
  }

  /** 変更依頼を取り下げる（依頼者のみ） */
  async function withdraw(id: number): Promise<void> {
    try {
      await shiftApi.withdrawChangeRequest(id)
      const idx = requests.value.findIndex((r) => r.id === id)
      if (idx !== -1) {
        const target = requests.value[idx]!
        requests.value[idx] = {
          ...target,
          reviewInfo: { ...target.reviewInfo, status: 'WITHDRAWN' },
        }
      }
      showSuccess(t('shift.changeRequest.withdraw'))
    } catch {
      showError(t('shift.changeRequest.fetchError'))
      throw new Error('withdraw failed')
    }
  }

  return { requests, isLoading, fetchRequests, createRequest, review, withdraw }
}
