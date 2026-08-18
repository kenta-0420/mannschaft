export type ScheduleScopeType = 'TEAM' | 'ORGANIZATION'
export type ScheduleStatus = 'DRAFT' | 'PUBLISHED' | 'CANCELLED'
export type ScheduleAttendanceStatus = 'YES' | 'NO' | 'MAYBE' | 'PENDING'
export type RecurrenceType = 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY'
export type EditScope = 'THIS_ONLY' | 'THIS_AND_FOLLOWING' | 'ALL'
export type ScheduleTargetMode = 'ALL_MEMBERS' | 'SELECTED_MEMBERS'

export interface ScheduleTargetMember {
  userId: number
  displayName: string
  avatarUrl: string | null
  calendarColor: string | null
}
export type ScheduleTargetMode = 'ALL_MEMBERS' | 'SELECTED_MEMBERS'

/** 予定の対象者。予定の背景色とは別に、誰の予定かを示すためだけに用いる。 */
export interface ScheduleTargetMember {
  userId: number
  displayName: string
  avatarUrl: string | null
  calendarColor: string | null
}

export interface ScheduleResponse {
  id: number
  scopeType: ScheduleScopeType
  scopeId: string
  title: string
  description: string | null
  location: string | null
  startAt: string
  endAt: string
  allDay: boolean
  status: ScheduleStatus
  recurrenceType: RecurrenceType
  recurrenceRule: string | null
  recurrenceGroupId: number | null
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  targetMode?: ScheduleTargetMode
  targetCount?: number
  targets?: ScheduleTargetMember[]
  targetMode?: ScheduleTargetMode
  targetCount?: number
  targets?: ScheduleTargetMember[]
  responseDeadline: string | null
  createdBy: { id: number; displayName: string }
  attendanceStats: {
    yes: number
    no: number
    maybe: number
    pending: number
    total: number
  } | null
  myAttendance: ScheduleAttendanceStatus | null
  createdAt: string
  updatedAt: string
}

export interface PersonalScheduleResponse {
  id: number
  title: string
  description: string | null
  location: string | null
  startAt: string
  endAt: string
  allDay: boolean
  color: string | null
  reminders: number[]
  createdAt: string
  updatedAt: string
}

export interface CreateScheduleRequest {
  title: string
  description?: string
  location?: string
  startAt: string
  endAt: string
  allDay?: boolean
  categoryId?: number
  recurrenceType?: RecurrenceType
  recurrenceRule?: string
  responseDeadline?: string
  targetMode?: ScheduleTargetMode
  targetUserIds?: number[]
  targetMode?: ScheduleTargetMode
  targetUserIds?: number[]
}

export interface UpdateScheduleRequest {
  title?: string
  description?: string
  location?: string
  startAt?: string
  endAt?: string
  allDay?: boolean
  categoryId?: number
  responseDeadline?: string
  editScope?: EditScope
  targetMode?: ScheduleTargetMode
  targetUserIds?: number[]
  targetMode?: ScheduleTargetMode
  targetUserIds?: number[]
}

export interface CreatePersonalScheduleRequest {
  title: string
  description?: string
  location?: string
  startAt: string
  endAt: string
  allDay?: boolean
  color?: string
  reminders?: number[]
}

export interface AttendanceResponse {
  userId: number
  displayName: string
  avatarUrl: string | null
  status: ScheduleAttendanceStatus
  comment: string | null
  respondedAt: string | null
}

export interface RespondAttendanceRequest {
  status: ScheduleAttendanceStatus
  comment?: string
}

export interface EventCategoryResponse {
  id: number
  name: string
  color: string
  sortOrder: number
}

export interface CalendarMonthView {
  year: number
  month: number
  events: Array<{
    id: number
    title: string
    startAt: string
    endAt: string
    allDay: boolean
    color: string | null
    scopeType: string
    isPersonal: boolean
  }>
}

// === Annual Schedule ===
export interface AnnualScheduleParams {
  academicYear?: number
  categoryId?: number
  eventType?: string
  termStartDate?: string
  termEndDate?: string
}

export interface AnnualCopyPreviewParams {
  sourceYear: number
  targetYear: number
  dateShiftMode?: string
  categoryId?: number
}

export interface ExecuteCopyRequest {
  sourceYear: number
  targetYear: number
  dateShiftMode?: string
  items?: Array<{ id: number; targetDate?: string }>
}

// === Bulk Attendance ===
export interface BulkAttendanceItem {
  userId: number
  status: ScheduleAttendanceStatus
  comment?: string
}

export interface BulkAttendanceRequest {
  attendances: BulkAttendanceItem[]
}

// === Cross Invite ===
export interface CrossInviteRequest {
  targetType: string
  targetId: number
  message?: string
}

// === Performance ===
export interface PerformanceRecordEntry {
  userId: number
  value: string | number
  note?: string
}

export interface ScheduleBulkRecordRequest {
  template?: string
  entries?: PerformanceRecordEntry[]
}

// === Schedule Invitation ===
export interface ScheduleInvitationResponse {
  id: number
  scheduleId: number
  scheduleTitle: string
  inviterTeamId: number
  inviterTeamName: string
  message: string | null
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CONFIRMED'
  createdAt: string
}

// === Schedule Stats ===
export interface ScheduleStatsResponse {
  scheduleId: number
  totalInvited: number
  responded: number
  yes: number
  no: number
  maybe: number
  pending: number
}

// ─── F03.10 代理出席 ───────────────────────────────────────
export type ScheduleDelegationStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'

export interface ScheduleDelegationResponse {
  id: string  // UUIDv7
  scheduleId: number
  delegatorId: number
  delegatorName: string
  delegateId: number
  delegateName: string
  status: ScheduleDelegationStatus
  reason: string | null
  reviewedAt: string | null
  createdAt: string
}

export interface CreateScheduleDelegationRequest {
  delegateId: number
  reason?: string
}

export interface ScheduleDelegationListResponse {
  data: ScheduleDelegationResponse[]
  total: number
  page: number
  size: number
}

export interface ScheduleDelegationMeResponse {
  asDelegator: ScheduleDelegationResponse | null  // 自分が委任者
  asDelegate: ScheduleDelegationResponse | null   // 自分が代理人
}
