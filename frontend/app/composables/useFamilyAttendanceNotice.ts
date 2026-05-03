import { ref, type Ref } from 'vue'
import type {
  FamilyAttendanceNoticeRequest,
  FamilyAttendanceNoticeResponse,
  FamilyNoticeListResponse,
} from '~/types/school'

/** 担任側: チームの保護者連絡管理 */
export function useFamilyAttendanceNotice(teamId: Ref<number>) {
  const api = useFamilyAttendanceNoticeApi()
  const { error: notifyError, success: notifySuccess } = useNotification()
  const { t } = useI18n()

  const noticeList = ref<FamilyNoticeListResponse | null>(null)
  const loading = ref(false)
  const processing = ref(false)

  async function loadTeamNotices(date: string): Promise<void> {
    loading.value = true
    try {
      noticeList.value = await api.getTeamNotices(teamId.value, date)
    } catch {
      notifyError(t('school.familyNotice.title'))
    } finally {
      loading.value = false
    }
  }

  async function acknowledge(noticeId: number): Promise<void> {
    processing.value = true
    try {
      const updated = await api.acknowledgeNotice(teamId.value, noticeId)
      if (noticeList.value) {
        noticeList.value.records = noticeList.value.records.map((r) =>
          r.id === noticeId ? updated : r,
        )
        noticeList.value.unacknowledgedCount = noticeList.value.records.filter(
          (r) => r.status === 'PENDING',
        ).length
      }
      notifySuccess(t('school.familyNotice.acknowledgeSuccess'))
    } catch {
      notifyError(t('school.familyNotice.title'))
    } finally {
      processing.value = false
    }
  }

  async function apply(noticeId: number): Promise<void> {
    processing.value = true
    try {
      const updated = await api.applyNotice(teamId.value, noticeId)
      if (noticeList.value) {
        noticeList.value.records = noticeList.value.records.map((r) =>
          r.id === noticeId ? updated : r,
        )
      }
      notifySuccess(t('school.familyNotice.applySuccess'))
    } catch {
      notifyError(t('school.familyNotice.title'))
    } finally {
      processing.value = false
    }
  }

  return {
    noticeList,
    loading,
    processing,
    loadTeamNotices,
    acknowledge,
    apply,
  }
}

/** 保護者側: 連絡送信・履歴管理 */
export function useFamilyAttendanceNoticeForm() {
  const api = useFamilyAttendanceNoticeApi()
  const { error: notifyError, success: notifySuccess } = useNotification()
  const { t } = useI18n()

  const submitting = ref(false)
  const myNotices = ref<FamilyAttendanceNoticeResponse[]>([])
  const historyLoading = ref(false)

  async function submitNotice(body: FamilyAttendanceNoticeRequest): Promise<boolean> {
    submitting.value = true
    try {
      await api.submitNotice(body)
      notifySuccess(t('school.familyNotice.submitSuccess'))
      return true
    } catch {
      notifyError(t('school.familyNotice.title'))
      return false
    } finally {
      submitting.value = false
    }
  }

  async function loadMyNotices(from: string, to: string): Promise<void> {
    historyLoading.value = true
    try {
      myNotices.value = await api.getMyNotices(from, to)
    } catch {
      notifyError(t('school.familyNotice.title'))
    } finally {
      historyLoading.value = false
    }
  }

  return {
    submitting,
    myNotices,
    historyLoading,
    submitNotice,
    loadMyNotices,
  }
}
