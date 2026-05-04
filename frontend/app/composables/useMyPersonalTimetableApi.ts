import type {
  CreatePersonalTimetableInput,
  DuplicatePersonalTimetableInput,
  PersonalTimetable,
  PersonalTimetablePeriod,
  PersonalTimetablePeriodInput,
  PersonalTimetableSlot,
  PersonalTimetableSlotInput,
  PersonalWeeklyView,
  UpdatePersonalTimetableInput,
} from '~/types/personal-timetable'
import type { DayOfWeekKey } from '~/types/timetable'

/**
 * F03.15 個人時間割 API クライアント（Phase 1 + Phase 2）。
 *
 * Phase 3 以降のメモ・添付・チームリンク・家族共有 API は順次追加する予定。
 */
export function useMyPersonalTimetableApi() {
  const api = useApi()
  const base = '/api/v1/me/personal-timetables'

  // ----- 個人時間割本体 (Phase 1) -----

  async function list() {
    const res = await api<{ data: PersonalTimetable[] }>(base)
    return res.data
  }

  async function get(id: number) {
    const res = await api<{ data: PersonalTimetable }>(`${base}/${id}`)
    return res.data
  }

  async function create(body: CreatePersonalTimetableInput) {
    const res = await api<{ data: PersonalTimetable }>(base, { method: 'POST', body })
    return res.data
  }

  async function update(id: number, body: UpdatePersonalTimetableInput) {
    const res = await api<{ data: PersonalTimetable }>(`${base}/${id}`, {
      method: 'PATCH',
      body,
    })
    return res.data
  }

  async function remove(id: number) {
    await api(`${base}/${id}`, { method: 'DELETE' })
  }

  async function activate(id: number) {
    const res = await api<{ data: PersonalTimetable }>(`${base}/${id}/activate`, {
      method: 'POST',
    })
    return res.data
  }

  async function archive(id: number) {
    const res = await api<{ data: PersonalTimetable }>(`${base}/${id}/archive`, {
      method: 'POST',
    })
    return res.data
  }

  async function revertToDraft(id: number) {
    const res = await api<{ data: PersonalTimetable }>(`${base}/${id}/revert-to-draft`, {
      method: 'POST',
    })
    return res.data
  }

  async function duplicate(id: number, body?: DuplicatePersonalTimetableInput) {
    const res = await api<{ data: PersonalTimetable }>(`${base}/${id}/duplicate`, {
      method: 'POST',
      body: body ?? {},
    })
    return res.data
  }

  // ----- 時限定義 (Phase 2) -----

  async function listPeriods(id: number) {
    const res = await api<{ data: PersonalTimetablePeriod[] }>(`${base}/${id}/periods`)
    return res.data
  }

  async function replacePeriods(id: number, periods: PersonalTimetablePeriodInput[]) {
    const res = await api<{ data: PersonalTimetablePeriod[] }>(`${base}/${id}/periods`, {
      method: 'PUT',
      body: { periods },
    })
    return res.data
  }

  // ----- コマ (Phase 2) -----

  async function listSlots(id: number, day?: DayOfWeekKey) {
    const url = day ? `${base}/${id}/slots?day=${day}` : `${base}/${id}/slots`
    const res = await api<{ data: PersonalTimetableSlot[] }>(url)
    return res.data
  }

  async function replaceSlots(
    id: number,
    slots: PersonalTimetableSlotInput[],
    day?: DayOfWeekKey,
  ) {
    const url = day ? `${base}/${id}/slots?day=${day}` : `${base}/${id}/slots`
    const res = await api<{ data: PersonalTimetableSlot[] }>(url, {
      method: 'PUT',
      body: { slots },
    })
    return res.data
  }

  async function listTodaySlots(id: number) {
    const res = await api<{ data: PersonalTimetableSlot[] }>(`${base}/${id}/slots/today`)
    return res.data
  }

  async function getWeekly(id: number, weekOf?: string) {
    const qs = weekOf ? `?week_of=${weekOf}` : ''
    const res = await api<{ data: PersonalWeeklyView }>(`${base}/${id}/weekly${qs}`)
    return res.data
  }

  return {
    list,
    get,
    create,
    update,
    remove,
    activate,
    archive,
    revertToDraft,
    duplicate,
    listPeriods,
    replacePeriods,
    listSlots,
    replaceSlots,
    listTodaySlots,
    getWeekly,
  }
}
