import dayjs from 'dayjs'
import type { MaybeRefOrGetter } from 'vue'
import type { NestedScheduleResponse } from '~/utils/scheduleCalendar'
import type { AttendanceResultRow, AttendanceSummary } from '~/utils/attendanceResults'
import { attendanceSchedules } from '~/utils/attendanceResults'

export function useAttendanceResults(
  scopeType: MaybeRefOrGetter<'team' | 'organization'>,
  scopeId: MaybeRefOrGetter<string>,
  limit = 10,
) {
  const api = useApi()
  const scheduleApi = useScheduleApi()
  const { handleApiError } = useErrorHandler()
  const { userTimezone } = useDatetime()
  const now = dayjs().tz(userTimezone.value)
  const from = ref(now.subtract(30, 'day').tz(userTimezone.value, true).format())
  const to = ref(now.add(30, 'day').tz(userTimezone.value, true).format())
  const schedules = ref<NestedScheduleResponse[]>([])
  const results = ref<AttendanceResultRow[]>([])
  const loading = ref(false)
  const error = shallowRef<unknown>()
  let generation = 0

  async function load() {
    const current = ++generation
    const type = toValue(scopeType)
    const id = toValue(scopeId)
    loading.value = true
    error.value = undefined
    schedules.value = []
    results.value = []
    try {
      // 一覧APIは範囲全件を返す。出席確認対象に絞ってから件数上限を適用する。
      const response = await scheduleApi.listSchedules(type, id, { from: from.value, to: to.value })
      if (current !== generation) return
      schedules.value = attendanceSchedules(response.data as NestedScheduleResponse[])
      results.value = schedules.value.slice(0, limit).map((schedule) => ({ schedule }))
      await Promise.all(
        results.value.map(async (row, index) => {
          try {
            const summary = await api<{ data: AttendanceSummary }>(
              `/api/v1/schedules/${row.schedule.id}/stats`,
            )
            if (current === generation)
              results.value[index] = { schedule: row.schedule, summary: summary.data }
          } catch (err) {
            if (current !== generation) return
            results.value[index] = { schedule: row.schedule, error: err }
            handleApiError(err)
          }
        }),
      )
    } catch (err) {
      if (current !== generation) return
      error.value = err
      handleApiError(err)
    } finally {
      if (current === generation) loading.value = false
    }
  }
  watch([() => toValue(scopeType), () => toValue(scopeId)], load)
  onMounted(load)
  onBeforeUnmount(() => {
    generation++
  })
  return { from, to, schedules, results, loading, error, load }
}
