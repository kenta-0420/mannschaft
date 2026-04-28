import type {
  CheckinResponse,
  EventDetailResponse,
  EventRsvpResponseItem,
  EventRsvpSummary,
  RegistrationResponse,
  RsvpResponse,
  TimetableItemResponse,
} from '~/types/event'

interface UseEventDetailOptions {
  scopeType: Ref<'team' | 'organization'>
  scopeId: Ref<number>
  eventId: Ref<number>
}

export function useEventDetail({ scopeType, scopeId, eventId }: UseEventDetailOptions) {
  const eventApi = useEventApi()
  const rsvpApi = useEventRsvpApi()
  const notification = useNotification()
  const authStore = useAuthStore()

  const event = ref<EventDetailResponse | null>(null)
  const registrations = ref<RegistrationResponse[]>([])
  const checkins = ref<CheckinResponse[]>([])
  const timetableItems = ref<TimetableItemResponse[]>([])
  const rsvpList = ref<EventRsvpResponseItem[]>([])
  const rsvpSummary = ref<EventRsvpSummary | null>(null)
  // 認証ユーザーの RSVP 回答を rsvpList から派生させる
  const myRsvpResponse = computed<RsvpResponse | null>(() => {
    const currentUserId = authStore.currentUser?.id
    if (!currentUserId) return null
    const mine = rsvpList.value.find((item) => item.userId === currentUserId)
    return mine?.response ?? null
  })
  const loading = ref(true)

  async function loadEvent() {
    loading.value = true
    try {
      const res = await eventApi.getEvent(scopeType.value, scopeId.value, eventId.value)
      event.value = res.data
    } catch {
      notification.error('イベント情報の取得に失敗しました')
    } finally {
      loading.value = false
    }
  }

  async function loadRegistrations() {
    try {
      const res = await eventApi.listRegistrations(eventId.value)
      registrations.value = res.data
    } catch {
      registrations.value = []
    }
  }

  async function loadCheckins() {
    try {
      const res = await eventApi.listCheckins(eventId.value)
      checkins.value = res.data
    } catch {
      checkins.value = []
    }
  }

  async function loadTimetable() {
    try {
      const res = await eventApi.getTimetable(eventId.value)
      timetableItems.value = res.data
    } catch {
      timetableItems.value = []
    }
  }

  async function loadRsvp() {
    if (!event.value || (event.value.attendanceMode ?? 'NONE') !== 'RSVP') return
    try {
      const [listRes, summaryRes] = await Promise.all([
        rsvpApi.fetchRsvpList(scopeType.value, scopeId.value, eventId.value),
        rsvpApi.fetchRsvpSummary(scopeType.value, scopeId.value, eventId.value),
      ])
      rsvpList.value = listRes.data
      rsvpSummary.value = summaryRes.data
    } catch {
      rsvpList.value = []
      rsvpSummary.value = null
    }
  }

  async function publishEvent() {
    if (!confirm('このイベントを公開しますか？')) return
    try {
      await eventApi.publishEvent(scopeType.value, scopeId.value, eventId.value)
      notification.success('イベントを公開しました')
      await loadEvent()
    } catch {
      notification.error('公開に失敗しました')
    }
  }

  async function cancelEvent() {
    if (!confirm('このイベントをキャンセルしますか？')) return
    try {
      await eventApi.cancelEvent(scopeType.value, scopeId.value, eventId.value)
      notification.success('イベントをキャンセルしました')
      await loadEvent()
    } catch {
      notification.error('キャンセルに失敗しました')
    }
  }

  async function closeRegistration() {
    try {
      await eventApi.closeRegistration(scopeType.value, scopeId.value, eventId.value)
      notification.success('受付を終了しました')
      await loadEvent()
    } catch {
      notification.error('受付終了に失敗しました')
    }
  }

  async function openRegistration() {
    try {
      await eventApi.openRegistration(scopeType.value, scopeId.value, eventId.value)
      notification.success('受付を再開しました')
      await loadEvent()
    } catch {
      notification.error('受付再開に失敗しました')
    }
  }

  async function approveRegistration(regId: number) {
    try {
      await eventApi.approveRegistration(eventId.value, regId)
      notification.success('参加を承認しました')
      await loadRegistrations()
    } catch {
      notification.error('承認に失敗しました')
    }
  }

  async function rejectRegistration(regId: number) {
    try {
      await eventApi.rejectRegistration(eventId.value, regId)
      notification.success('参加を拒否しました')
      await loadRegistrations()
    } catch {
      notification.error('拒否に失敗しました')
    }
  }

  async function init() {
    await loadEvent()
    await Promise.all([loadRegistrations(), loadCheckins(), loadTimetable(), loadRsvp()])
  }

  return {
    event,
    registrations,
    checkins,
    timetableItems,
    rsvpList,
    rsvpSummary,
    myRsvpResponse,
    loading,
    loadEvent,
    loadRsvp,
    publishEvent,
    cancelEvent,
    closeRegistration,
    openRegistration,
    approveRegistration,
    rejectRegistration,
    init,
  }
}
