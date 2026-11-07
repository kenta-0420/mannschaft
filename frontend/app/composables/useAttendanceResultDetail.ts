import { attendanceDate } from '~/utils/attendanceResults'
import type { MaybeRefOrGetter } from 'vue'
import type { NestedScheduleResponse } from '~/utils/scheduleCalendar'
import type { AttendanceResult, AttendanceSummary } from '~/utils/attendanceResults'
import type { MemberResponse } from '~/types/member'
import type { PagedResponse } from '~/types/api'

export function useAttendanceResultDetail(
  scopeType: MaybeRefOrGetter<'team' | 'organization'>,
  scopeId: MaybeRefOrGetter<string>,
  scheduleId: MaybeRefOrGetter<string>,
) {
  const api = useApi()
  const scheduleApi = useScheduleApi()
  const { handleApiError } = useErrorHandler()
  const { userTimezone, buildDayStartStr, buildDayEndStr } = useDatetime()
  const schedule = ref<NestedScheduleResponse>()
  const attendances = ref<AttendanceResult[]>([])
  const members = ref<MemberResponse[]>([])
  const summary = ref<AttendanceSummary>()
  const loading = ref(false)
  const error = shallowRef<unknown>()
  let generation = 0

  async function load() {
    const current = ++generation
    const type = toValue(scopeType)
    const scope = toValue(scopeId)
    const idText = toValue(scheduleId)
    const id = Number(idText)
    loading.value = true
    schedule.value = undefined
    summary.value = undefined
    attendances.value = []
    members.value = []
    error.value = undefined
    try {
      if (!/^\d+$/.test(idText) || !Number.isSafeInteger(id) || id <= 0) throw { statusCode: 404 }
      const response = await scheduleApi.getSchedule(type, scope, id)
      if (current !== generation) return
      const candidate = response.data as NestedScheduleResponse
      if (
        candidate.id !== id ||
        candidate.content?.attendanceRequired !== true ||
        !candidate.time?.startAt
      )
        throw { statusCode: 404 }
      // 詳細DTOはスコープIDを含まない。同じスコープの一覧に存在するまでタイトルも理由も公開しない。
      // BEはLocalDateTimeを返すため、利用者TZの壁時計として解釈する。日境界を余裕をもって含める。
      const start = attendanceDate(candidate.time.startAt, userTimezone.value)
      const scopeSchedules = await scheduleApi.listSchedules(type, scope, {
        from: buildDayStartStr(start.subtract(1, 'day').format('YYYY-MM-DD')),
        to: buildDayEndStr(start.add(1, 'day').format('YYYY-MM-DD')),
      })
      if (current !== generation) return
      if (!(scopeSchedules.data as NestedScheduleResponse[]).some((item) => item.id === id))
        throw { statusCode: 404 }
      const [attendanceResponse, summaryResponse] = await Promise.all([
        scheduleApi.getAttendances(type, scope, id),
        api<{ data: AttendanceSummary }>(`/api/v1/schedules/${id}/stats`),
      ])
      if (current !== generation) return
      const allMembers: MemberResponse[] = []
      // 対象者名がないALL_MEMBERS予定も、所属一覧から名前を解決する。
      if (candidate.targetMode !== 'SELECTED_MEMBERS' && type === 'team') {
        const response = await api<{ data: MemberResponse[] }>(`/api/v1/teams/${scope}/members/all`)
        if (current !== generation) return
        allMembers.push(...response.data)
      } else if (candidate.targetMode !== 'SELECTED_MEMBERS') {
        let memberPage = 0
        let totalPages = 1
        while (memberPage < totalPages) {
          const response = await api<PagedResponse<MemberResponse>>(
            `/api/v1/${type === 'team' ? 'teams' : 'organizations'}/${scope}/members?page=${memberPage}&size=500`,
          )
          if (current !== generation) return
          allMembers.push(...response.data)
          totalPages = response.meta.totalPages
          memberPage++
        }
      }
      if (current !== generation) return
      schedule.value = candidate
      attendances.value = attendanceResponse.data as AttendanceResult[]
      summary.value = summaryResponse.data
      members.value = allMembers
    } catch (err) {
      if (current !== generation) return
      error.value = err
      handleApiError(err)
    } finally {
      if (current === generation) loading.value = false
    }
  }
  watch([() => toValue(scopeType), () => toValue(scopeId), () => toValue(scheduleId)], load)
  onMounted(load)
  onBeforeUnmount(() => {
    generation++
  })
  return { schedule, attendances, members, summary, loading, error, load }
}
