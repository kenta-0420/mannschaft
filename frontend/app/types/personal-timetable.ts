import type { DayOfWeekKey, WeekPatternType } from '~/types/timetable'

/** F03.15 個人時間割のステータス。 */
export type PersonalTimetableStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'

/** F03.15 個人時間割の公開モード。 */
export type PersonalTimetableVisibility = 'PRIVATE' | 'FAMILY_SHARED'

/** Phase 1 で受け付ける時限テンプレート種別。 */
export type PersonalPeriodTemplateKind =
  | 'ELEMENTARY'
  | 'JUNIOR_HIGH'
  | 'HIGH_SCHOOL'
  | 'UNIV_90MIN'
  | 'UNIV_100MIN'
  | 'CUSTOM'

/** 個人時間割本体。 */
export interface PersonalTimetable {
  id: number
  name: string
  academic_year?: number | null
  term_label?: string | null
  effective_from: string
  effective_until?: string | null
  status: PersonalTimetableStatus
  visibility: PersonalTimetableVisibility
  week_pattern_enabled: boolean
  week_pattern_base_date?: string | null
  notes?: string | null
  created_at: string
  updated_at: string
}

/** 個人時間割作成リクエスト。 */
export interface CreatePersonalTimetableInput {
  name: string
  academic_year?: number | null
  term_label?: string | null
  effective_from: string
  effective_until?: string | null
  visibility?: PersonalTimetableVisibility
  week_pattern_enabled?: boolean
  week_pattern_base_date?: string | null
  notes?: string | null
  init_period_template?: PersonalPeriodTemplateKind | null
}

/** 個人時間割更新リクエスト。 */
export type UpdatePersonalTimetableInput = Partial<
  Omit<CreatePersonalTimetableInput, 'init_period_template'>
>

/** 個人時間割複製リクエスト。 */
export interface DuplicatePersonalTimetableInput {
  name?: string
  academic_year?: number | null
  term_label?: string | null
  effective_from?: string
  effective_until?: string | null
}

/** 時限定義（Phase 2）。 */
export interface PersonalTimetablePeriod {
  id: number
  period_number: number
  label: string
  start_time: string
  end_time: string
  is_break: boolean
}

/** 時限定義入力要素。 */
export interface PersonalTimetablePeriodInput {
  period_number: number
  label: string
  start_time: string
  end_time: string
  is_break?: boolean
}

/** コマ（Phase 2）。 */
export interface PersonalTimetableSlot {
  id: number
  day_of_week: DayOfWeekKey
  period_number: number
  week_pattern: WeekPatternType
  subject_name: string
  course_code?: string | null
  teacher_name?: string | null
  room_name?: string | null
  credits?: number | null
  color?: string | null
  linked_team_id?: number | null
  linked_timetable_id?: number | null
  linked_slot_id?: number | null
  auto_sync_changes: boolean
  notes?: string | null
}

/** コマ入力要素。Phase 4 から link 列も受け付ける（保存はされない；POST /link 経由で別途登録）。 */
export interface PersonalTimetableSlotInput {
  day_of_week: DayOfWeekKey
  period_number: number
  week_pattern?: WeekPatternType
  subject_name: string
  course_code?: string | null
  teacher_name?: string | null
  room_name?: string | null
  credits?: number | null
  color?: string | null
  linked_team_id?: number | null
  linked_timetable_id?: number | null
  linked_slot_id?: number | null
  auto_sync_changes?: boolean
  notes?: string | null
}

/** Phase 4 チームリンク登録/更新リクエスト。 */
export interface PersonalSlotLinkRequest {
  linked_team_id: number
  linked_timetable_id: number
  linked_slot_id?: number | null
  auto_sync_changes?: boolean
}

/** 週間ビューの 1 コマ。 */
export interface PersonalWeeklySlotInfo {
  id: number
  period_number: number
  week_pattern: WeekPatternType
  subject_name: string
  course_code?: string | null
  teacher_name?: string | null
  room_name?: string | null
  color?: string | null
  notes?: string | null
}

/** 週間ビューの曜日ごとの情報。 */
export interface PersonalWeeklyDayInfo {
  date: string
  slots: PersonalWeeklySlotInfo[]
}

/** 週間ビューレスポンス。 */
export interface PersonalWeeklyView {
  personal_timetable_id: number
  personal_timetable_name: string
  week_start: string
  week_end: string
  week_pattern_enabled: boolean
  current_week_pattern: WeekPatternType
  periods: PersonalTimetablePeriod[]
  days: Record<DayOfWeekKey, PersonalWeeklyDayInfo>
}
