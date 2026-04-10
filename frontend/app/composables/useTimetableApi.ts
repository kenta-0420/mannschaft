import type {
  Timetable,
  TimetableSlot,
  TimetableChange,
  TimetableTerm,
  TimetablePeriod,
  WeeklyView,
  TimetableVisibility,
} from '~/types/timetable'

export function useTimetableApi() {
  const api = useApi()

  async function listTerms(scopeType: 'team' | 'organization', scopeId: number) {
    const base =
      scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
    const res = await api<{ data: TimetableTerm[] }>(`${base}/timetable-terms`)
    return res.data
  }

  async function listPeriods(orgId: number) {
    const res = await api<{ data: TimetablePeriod[] }>(
      `/api/v1/organizations/${orgId}/timetable-periods`,
    )
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

  async function create(
    teamId: number,
    body: {
      name: string
      termId: number
      effectiveFrom: string
      effectiveUntil?: string | null
      visibility?: TimetableVisibility
      weekPatternEnabled?: boolean
      weekPatternBaseDate?: string | null
      periodOverride?: string | null
      notes?: string | null
    },
  ) {
    const res = await api<{ data: Timetable }>(`/api/v1/teams/${teamId}/timetables`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function activate(teamId: number, timetableId: number) {
    await api(`/api/v1/teams/${teamId}/timetables/${timetableId}/activate`, { method: 'POST' })
  }

  async function archive(teamId: number, timetableId: number) {
    await api(`/api/v1/teams/${teamId}/timetables/${timetableId}/archive`, { method: 'POST' })
  }

  async function duplicate(
    teamId: number,
    timetableId: number,
    body?: { name?: string; targetTermId?: number; effectiveFrom?: string; effectiveUntil?: string | null },
  ) {
    const res = await api<{ data: Timetable }>(
      `/api/v1/teams/${teamId}/timetables/${timetableId}/duplicate`,
      { method: 'POST', body },
    )
    return res.data
  }

  async function getSlots(timetableId: number) {
    const res = await api<{ data: TimetableSlot[] }>(`/api/v1/timetables/${timetableId}/slots`)
    return res.data
  }

  async function updateSlots(timetableId: number, slots: Partial<TimetableSlot>[]) {
    await api(`/api/v1/timetables/${timetableId}/slots`, { method: 'PUT', body: { slots } })
  }

  async function getWeekly(teamId: number, timetableId: number, weekOf?: string) {
    const qs = weekOf ? `?week_of=${weekOf}` : ''
    const res = await api<{ data: WeeklyView }>(
      `/api/v1/teams/${teamId}/timetables/${timetableId}/weekly${qs}`,
    )
    return res.data
  }

  async function listChanges(timetableId: number, params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    const qs = query.toString()
    const res = await api<{ data: TimetableChange[] }>(
      `/api/v1/timetables/${timetableId}/changes${qs ? `?${qs}` : ''}`,
    )
    return res.data
  }

  async function createChange(
    timetableId: number,
    body: {
      targetDate: string
      periodNumber?: number | null
      changeType: string
      subjectName?: string | null
      teacherName?: string | null
      roomName?: string | null
      reason?: string | null
      notifyMembers?: boolean
      createSchedule?: boolean
    },
  ) {
    const res = await api<{ data: TimetableChange }>(`/api/v1/timetables/${timetableId}/changes`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function exportPdf(teamId: number, timetableId: number) {
    const res = await api<{ data: { url: string } }>(
      `/api/v1/teams/${teamId}/timetables/${timetableId}/export/pdf`,
    )
    return res.data
  }

  async function getTodaySlots(timetableId: number) {
    const res = await api<{ data: TimetableSlot[] }>(
      `/api/v1/timetables/${timetableId}/slots/today`,
    )
    return res.data
  }

  async function getSubjectSuggestions(timetableId: number) {
    const res = await api<{ data: string[] }>(
      `/api/v1/timetables/${timetableId}/subject-suggestions`,
    )
    return res.data
  }

  async function revertToDraft(teamId: number, timetableId: number) {
    await api(`/api/v1/teams/${teamId}/timetables/${timetableId}/revert-to-draft`, {
      method: 'POST',
    })
  }

  async function update(
    teamId: number,
    timetableId: number,
    body: Partial<{ name: string; termId: number; weekPatternEnabled: boolean }>,
  ) {
    const res = await api<{ data: Timetable }>(
      `/api/v1/teams/${teamId}/timetables/${timetableId}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  async function remove(teamId: number, timetableId: number) {
    await api(`/api/v1/teams/${teamId}/timetables/${timetableId}`, { method: 'DELETE' })
  }

  async function updateChange(
    timetableId: number,
    changeId: number,
    body: Partial<TimetableChange>,
  ) {
    const res = await api<{ data: TimetableChange }>(
      `/api/v1/timetables/${timetableId}/changes/${changeId}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  async function deleteChange(timetableId: number, changeId: number) {
    await api(`/api/v1/timetables/${timetableId}/changes/${changeId}`, { method: 'DELETE' })
  }

  // === Terms ===
  async function createTeamTerm(teamId: number, body: Partial<TimetableTerm>) {
    const res = await api<{ data: TimetableTerm }>(`/api/v1/teams/${teamId}/timetable-terms`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function createOrgTerm(orgId: number, body: Partial<TimetableTerm>) {
    const res = await api<{ data: TimetableTerm }>(
      `/api/v1/organizations/${orgId}/timetable-terms`,
      { method: 'POST', body },
    )
    return res.data
  }

  async function updateTerm(termId: number, body: Partial<TimetableTerm>) {
    const res = await api<{ data: TimetableTerm }>(`/api/v1/timetable-terms/${termId}`, {
      method: 'PATCH',
      body,
    })
    return res.data
  }

  async function deleteTerm(termId: number) {
    await api(`/api/v1/timetable-terms/${termId}`, { method: 'DELETE' })
  }

  // === Period Templates ===
  async function updatePeriodTemplates(orgId: number, body: Partial<TimetablePeriod>[]) {
    await api(`/api/v1/organizations/${orgId}/timetable-periods`, {
      method: 'PUT',
      body: { periods: body },
    })
  }

  return {
    listTerms,
    listPeriods,
    list,
    getCurrent,
    get,
    create,
    update,
    remove,
    activate,
    archive,
    duplicate,
    getSlots,
    updateSlots,
    getWeekly,
    listChanges,
    createChange,
    updateChange,
    deleteChange,
    exportPdf,
    getTodaySlots,
    getSubjectSuggestions,
    revertToDraft,
    createTeamTerm,
    createOrgTerm,
    updateTerm,
    deleteTerm,
    updatePeriodTemplates,
  }
}
