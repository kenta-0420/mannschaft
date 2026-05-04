/**
 * F03.15 Phase 3 個人メモ・添付・設定・ダッシュボード関連の型定義。
 */

/** メモが紐付くスロットの種別。 */
export type TimetableSlotKind = 'TEAM' | 'PERSONAL'

/** カスタムフィールドの値。 */
export interface CustomFieldValue {
  field_id: number
  value: string | null
  is_orphaned?: boolean | null
}

/** 個人メモ レスポンス。 */
export interface TimetableSlotUserNote {
  id: number
  slot_kind: TimetableSlotKind
  slot_id: number
  preparation?: string | null
  review?: string | null
  items_to_bring?: string | null
  free_memo?: string | null
  custom_fields?: CustomFieldValue[] | null
  target_date?: string | null
  created_at: string
  updated_at: string
}

/** 個人メモ アップサート入力。 */
export interface UpsertTimetableSlotUserNoteInput {
  slot_kind: TimetableSlotKind
  slot_id: number
  target_date?: string | null
  preparation?: string | null
  review?: string | null
  items_to_bring?: string | null
  free_memo?: string | null
  custom_fields?: { field_id: number, value: string }[] | null
}

/** カスタムメモ項目。 */
export interface TimetableSlotUserNoteField {
  id: number
  label: string
  placeholder?: string | null
  sortOrder: number
  maxLength: number
  createdAt: string
  updatedAt: string
}

export interface CreateTimetableSlotUserNoteFieldInput {
  label: string
  placeholder?: string | null
  sortOrder?: number
  maxLength?: 500 | 2000 | 5000
}

export type UpdateTimetableSlotUserNoteFieldInput = Partial<CreateTimetableSlotUserNoteFieldInput>

/** 添付ファイル presign リクエスト。 */
export interface AttachmentPresignInput {
  file_name: string
  content_type: string
  size_bytes: number
}

/** 添付ファイル presign レスポンス。 */
export interface AttachmentPresignResult {
  upload_url: string
  r2_object_key: string
  expires_in: number
}

export interface AttachmentConfirmInput {
  r2_object_key: string
  file_name: string
  content_type: string
  size_bytes: number
}

export interface Attachment {
  id: number
  note_id: number
  original_filename: string
  mime_type: string
  size_bytes: number
  created_at: string
}

/** 個人時間割ユーザー設定。 */
export interface PersonalTimetableSettings {
  auto_reflect_class_changes_to_calendar: boolean
  notify_team_slot_note_updates: boolean
  default_period_template: 'ELEMENTARY' | 'JUNIOR_HIGH' | 'HIGH_SCHOOL' | 'UNIV_90MIN' | 'UNIV_100MIN' | 'CUSTOM'
  visible_default_fields: string[]
  created_at: string
  updated_at: string
}

export type UpdatePersonalTimetableSettingsInput = Partial<PersonalTimetableSettings>

/** ダッシュボード「今日の時間割」 1 アイテム。 */
export interface DashboardTimetableTodayItem {
  source_kind: 'TEAM' | 'PERSONAL'
  source_team_id?: number | null
  source_team_name?: string | null
  personal_timetable_id?: number | null
  timetable_id?: number | null
  slot_id: number
  period_label?: string | null
  period_number?: number | null
  start_time?: string | null
  end_time?: string | null
  subject_name: string
  course_code?: string | null
  teacher_name?: string | null
  room_name?: string | null
  credits?: number | null
  color?: string | null
  linked_team_id?: number | null
  is_changed: boolean
  change?: unknown
  link_revoked: boolean
  user_note_id?: number | null
  has_attachments: boolean
}

export interface DashboardTimetableToday {
  date: string
  week_pattern: string
  items: DashboardTimetableTodayItem[]
}
