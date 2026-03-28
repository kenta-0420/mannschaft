import type { Timetable, TimetableSlot, TimetableChange, TimetableTerm, TimetablePeriod, WeeklyView } from '~/types/timetable'

export function useTimetableApi() {
  const api = useApi()

  async function listTerms(scopeType: 'team' | 'organization', scopeId: number) {
    const base = scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
    const res = await api<{ data: TimetableTerm[] }>(`${base}/timetable-terms`)
    return res.data
  }

  async function listPeriods(orgId: number) {
    const res = await api<{ data: TimetablePeriod[] }>(`/api/v1/organizations/${orgId}/timetable-periods`)
    return res.data
  }

  async function list(teamId: number) {
    const res = await api<{ data: Timetable[] }>(`/api/v1/teams/${teamId}/timetables`)
    return res.data
  }

  async function getCurrent(teamId: number) {
    const res = await api<{ data: Timetable }>(`/api/v1/teams/${teamId}/timetables/current`)
    return res.data
  }

  async function get(teamId: number, timetableId: number) {
    const res = await api<{ data: Timetable }>(`/api/v1/teams/${teamId}/timetables/${timetableId}`)
    return res.data
  }

  async function create(teamId: number, body: { name: string; termId: number; weekPatternEnabled?: boolean }) {
    const res = await api<{ data: Timetable }>(`/api/v1/teams/${teamId}/timetables`, { method: 'POST', body })
    return res.data
  }

  async function activate(teamId: number, timetableId: number) {
    await api(`/api/v1/teams/${teamId}/timetables/${timetableId}/activate`, { method: 'POST' })
  }

  async function archive(teamId: number, timetableId: number) {
    await api(`/api/v1/teams/${teamId}/timetables/${timetableId}/archive`, { method: 'POST' })
  }

  async function duplicate(teamId: number, timetableId: number) {
    const res = await api<{ data: Timetable }>(`/api/v1/teams/${teamId}/timetables/${timetableId}/duplicate`, { method: 'POST' })
    return res.data
  }

  async function getSlots(timetableId: number) {
    const res = await api<{ data: TimetableSlot[] }>(`/api/v1/timetables/${timetableId}/slots`)
    return res.data
  }

  async function updateSlots(timetableId: number, slots: Partial<TimetableSlot>[]) {
    await api(`/api/v1/timetables/${timetableId}/slots`, { method: 'PUT', body: { slots } })
  }

  async function getWeekly(teamId: number, timetableId: number, date?: string) {
    const qs = date ? `?date=${date}` : ''
    const res = await api<{ data: WeeklyView }>(`/api/v1/teams/${teamId}/timetables/${timetableId}/weekly${qs}`)
    return res.data
  }

  async function listChanges(timetableId: number, params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    const qs = query.toString()
    const res = await api<{ data: TimetableChange[] }>(`/api/v1/timetables/${timetableId}/changes${qs ? `?${qs}` : ''}`)
    return res.data
  }

  async function createChange(timetableId: number, body: Partial<TimetableChange>) {
    const res = await api<{ data: TimetableChange }>(`/api/v1/timetables/${timetableId}/changes`, { method: 'POST', body })
    return res.data
  }

  async function exportPdf(teamId: number, timetableId: number) {
    const res = await api<{ data: { url: string } }>(`/api/v1/teams/${teamId}/timetables/${timetableId}/export/pdf`)
    return res.data
  }

  return { listTerms, listPeriods, list, getCurrent, get, create, activate, archive, duplicate, getSlots, updateSlots, getWeekly, listChanges, createChange, exportPdf }
}
