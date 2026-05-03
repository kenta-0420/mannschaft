import { ref, type Ref } from 'vue'
import type {
  LocationChangeRequest,
  LocationChangeResponse,
  LocationListResponse,
  LocationTimelineResponse,
} from '~/types/school'

export function useAttendanceLocation(teamIdRef: Ref<number>) {
  const api = useAttendanceLocationApi()
  const { error: notifyError, success: notifySuccess } = useNotification()
  const { t } = useI18n()

  const locationList = ref<LocationListResponse | null>(null)
  const timeline = ref<LocationTimelineResponse | null>(null)
  const loading = ref(false)
  const submitting = ref(false)

  async function loadTeamLocations(date: string): Promise<void> {
    loading.value = true
    try {
      locationList.value = await api.getTeamLocations(teamIdRef.value, date)
    } catch {
      notifyError(t('school.location.title'))
    } finally {
      loading.value = false
    }
  }

  async function loadTimeline(studentUserId: number, date: string): Promise<void> {
    loading.value = true
    try {
      timeline.value = await api.getLocationTimeline(studentUserId, date)
    } catch {
      notifyError(t('school.location.title'))
    } finally {
      loading.value = false
    }
  }

  async function changeLocation(
    request: LocationChangeRequest,
    date: string,
  ): Promise<LocationChangeResponse | null> {
    submitting.value = true
    try {
      const response = await api.recordLocationChange(teamIdRef.value, request)
      notifySuccess(t('school.location.submitSuccess'))
      await loadTeamLocations(date)
      return response
    } catch {
      notifyError(t('school.location.changeTitle'))
      return null
    } finally {
      submitting.value = false
    }
  }

  return {
    locationList,
    timeline,
    loading,
    submitting,
    loadTeamLocations,
    loadTimeline,
    changeLocation,
  }
}
