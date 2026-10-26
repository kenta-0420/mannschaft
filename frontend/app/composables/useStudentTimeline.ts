import { ref } from 'vue'
import type { StudentTimelineResponse } from '~/types/school'

export function useStudentTimeline() {
  const api = useStudentTimelineApi()
  const { error: notifyError } = useNotification()
  const { t } = useI18n()

  const timeline = ref<StudentTimelineResponse | null>(null)
  const loading = ref(false)

  async function loadTimeline(date: string): Promise<void> {
    loading.value = true
    try {
      timeline.value = await api.getMyTimeline(date)
    } catch {
      notifyError(t('school.timeline.title'))
    } finally {
      loading.value = false
    }
  }

  return {
    timeline,
    loading,
    loadTimeline,
  }
}
