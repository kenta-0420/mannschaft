import { ref, type Ref } from 'vue'
import type {
  ClassHomeroomResponse,
  ClassHomeroomCreateRequest,
  ClassHomeroomUpdateRequest,
} from '~/types/school'

export function useClassHomeroom(teamId: Ref<number>) {
  const api = useClassHomeroomApi()
  const { error: notifyError, success: notifySuccess } = useNotification()
  const { t } = useI18n()

  const homerooms = ref<ClassHomeroomResponse[]>([])
  const loading = ref(false)
  const submitting = ref(false)

  async function loadHomerooms(academicYear: number): Promise<void> {
    loading.value = true
    try {
      homerooms.value = await api.getHomerooms(teamId.value, academicYear)
    } catch {
      notifyError(t('school.homeroom.title'))
    } finally {
      loading.value = false
    }
  }

  async function addHomeroom(body: ClassHomeroomCreateRequest): Promise<ClassHomeroomResponse | null> {
    submitting.value = true
    try {
      const created = await api.createHomeroom(teamId.value, body)
      homerooms.value = [...homerooms.value, created]
      notifySuccess(t('school.homeroom.addSuccess'))
      return created
    } catch {
      notifyError(t('school.homeroom.title'))
      return null
    } finally {
      submitting.value = false
    }
  }

  async function editHomeroom(
    id: number,
    body: ClassHomeroomUpdateRequest,
  ): Promise<ClassHomeroomResponse | null> {
    submitting.value = true
    try {
      const updated = await api.updateHomeroom(teamId.value, id, body)
      homerooms.value = homerooms.value.map((h) => (h.id === id ? updated : h))
      notifySuccess(t('school.homeroom.updateSuccess'))
      return updated
    } catch {
      notifyError(t('school.homeroom.title'))
      return null
    } finally {
      submitting.value = false
    }
  }

  return {
    homerooms,
    loading,
    submitting,
    loadHomerooms,
    addHomeroom,
    editHomeroom,
  }
}
