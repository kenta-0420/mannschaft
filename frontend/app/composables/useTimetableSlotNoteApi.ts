import type {
  Attachment,
  AttachmentConfirmInput,
  AttachmentPresignInput,
  AttachmentPresignResult,
  CreateTimetableSlotUserNoteFieldInput,
  DashboardTimetableToday,
  PersonalTimetableSettings,
  TimetableSlotKind,
  TimetableSlotUserNote,
  TimetableSlotUserNoteField,
  UpdatePersonalTimetableSettingsInput,
  UpdateTimetableSlotUserNoteFieldInput,
  UpsertTimetableSlotUserNoteInput,
} from '~/types/timetable-note'

/**
 * F03.15 Phase 3 個人メモ・添付・設定・ダッシュボード API クライアント。
 */
export function useTimetableSlotNoteApi() {
  const api = useApi()
  const noteBase = '/api/v1/me/timetable-slot-notes'
  const fieldBase = '/api/v1/me/timetable-slot-note-fields'
  const settingsBase = '/api/v1/me/personal-timetable-settings'

  // ----- メモ -----

  async function listNotes(slotKind: TimetableSlotKind, slotId: number, targetDate?: string, includeDefault = false) {
    const params = new URLSearchParams({
      slot_kind: slotKind,
      slot_id: String(slotId),
    })
    if (targetDate) params.set('target_date', targetDate)
    if (includeDefault) params.set('include_default', 'true')
    const res = await api<{ data: TimetableSlotUserNote[] }>(`${noteBase}?${params.toString()}`)
    return res.data
  }

  async function upsertNote(body: UpsertTimetableSlotUserNoteInput, ifUnmodifiedSince?: string) {
    const res = await api<{ data: TimetableSlotUserNote }>(noteBase, {
      method: 'PUT',
      body,
      headers: ifUnmodifiedSince ? { 'If-Unmodified-Since': ifUnmodifiedSince } : undefined,
    })
    return res.data
  }

  async function deleteNote(noteId: number) {
    await api(`${noteBase}/${noteId}`, { method: 'DELETE' })
  }

  async function todayNotes() {
    const res = await api<{ data: TimetableSlotUserNote[] }>(`${noteBase}/today`)
    return res.data
  }

  async function upcomingNotes(from: string, to: string) {
    const res = await api<{ data: TimetableSlotUserNote[] }>(`${noteBase}/upcoming?from=${from}&to=${to}`)
    return res.data
  }

  // ----- カスタムフィールド -----

  async function listFields() {
    const res = await api<{ data: TimetableSlotUserNoteField[] }>(fieldBase)
    return res.data
  }

  async function createField(body: CreateTimetableSlotUserNoteFieldInput) {
    const res = await api<{ data: TimetableSlotUserNoteField }>(fieldBase, { method: 'POST', body })
    return res.data
  }

  async function updateField(id: number, body: UpdateTimetableSlotUserNoteFieldInput) {
    const res = await api<{ data: TimetableSlotUserNoteField }>(`${fieldBase}/${id}`, { method: 'PATCH', body })
    return res.data
  }

  async function deleteField(id: number) {
    await api(`${fieldBase}/${id}`, { method: 'DELETE' })
  }

  // ----- 添付ファイル -----

  async function presignAttachment(noteId: number, body: AttachmentPresignInput) {
    const res = await api<{ data: AttachmentPresignResult }>(
      `${noteBase}/${noteId}/attachments/presign`, { method: 'POST', body },
    )
    return res.data
  }

  async function confirmAttachment(noteId: number, body: AttachmentConfirmInput) {
    const res = await api<{ data: Attachment }>(
      `${noteBase}/${noteId}/attachments/confirm`, { method: 'POST', body },
    )
    return res.data
  }

  async function getAttachmentDownloadUrl(attachmentId: number) {
    const res = await api<{ data: { download_url: string, expires_in: number } }>(
      `${noteBase}/attachments/${attachmentId}/download-url`,
    )
    return res.data
  }

  async function deleteAttachment(attachmentId: number) {
    await api(`${noteBase}/attachments/${attachmentId}`, { method: 'DELETE' })
  }

  async function listAttachments(noteId: number) {
    const res = await api<{ data: Attachment[] }>(`${noteBase}/${noteId}/attachments`)
    return res.data
  }

  // ----- ユーザー設定 -----

  async function getSettings() {
    const res = await api<{ data: PersonalTimetableSettings }>(settingsBase)
    return res.data
  }

  async function updateSettings(body: UpdatePersonalTimetableSettingsInput) {
    const res = await api<{ data: PersonalTimetableSettings }>(settingsBase, {
      method: 'PUT', body,
    })
    return res.data
  }

  // ----- ダッシュボード -----

  async function dashboardToday() {
    const res = await api<{ data: DashboardTimetableToday }>('/api/v1/me/dashboard/timetable-today')
    return res.data
  }

  return {
    listNotes,
    upsertNote,
    deleteNote,
    todayNotes,
    upcomingNotes,
    listFields,
    createField,
    updateField,
    deleteField,
    presignAttachment,
    confirmAttachment,
    getAttachmentDownloadUrl,
    deleteAttachment,
    listAttachments,
    getSettings,
    updateSettings,
    dashboardToday,
  }
}
