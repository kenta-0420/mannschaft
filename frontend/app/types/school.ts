export type AttendanceStatus = 'ATTENDING' | 'PARTIAL' | 'ABSENT' | 'UNDECIDED'
export type AbsenceReason = 'ILLNESS' | 'INJURY' | 'FAMILY' | 'OTHER'

export interface DailyAttendanceResponse {
  id: number
  teamId: number
  studentUserId: number
  attendanceDate: string
  status: AttendanceStatus
  absenceReason?: AbsenceReason
  arrivalTime?: string
  leaveTime?: string
  comment?: string
  familyNoticeId?: number
  recordedAt: string
  createdAt: string
  updatedAt: string
}

export interface DailyAttendanceListResponse {
  records: DailyAttendanceResponse[]
}

export interface DailyRollCallEntry {
  studentUserId: number
  status: AttendanceStatus
  absenceReason?: AbsenceReason
  arrivalTime?: string
  leaveTime?: string
  comment?: string
  familyNoticeId?: number
}

export interface DailyRollCallRequest {
  attendanceDate: string
  entries: DailyRollCallEntry[]
}

export interface DailyRollCallSummary {
  date: string
  teamId: number
  total: number
  attending: number
  partial: number
  absent: number
  undecided: number
}

export interface DailyAttendanceUpdateRequest {
  status?: AttendanceStatus
  absenceReason?: AbsenceReason
  arrivalTime?: string
  leaveTime?: string
  comment?: string
}

export interface AttendanceHistoryItem {
  id: number
  teamId: number
  attendanceDate: string
  status: AttendanceStatus
  absenceReason?: AbsenceReason
  arrivalTime?: string
  leaveTime?: string
  comment?: string
  recordedAt: string
}

export interface PeriodAttendanceResponse {
  id: number
  teamId: number
  studentUserId: number
  attendanceDate: string
  periodNumber: number
  status: AttendanceStatus
  absenceReason?: AbsenceReason
  comment?: string
  recordedAt: string
  createdAt: string
  updatedAt: string
}

export interface PeriodAttendanceListResponse {
  records: PeriodAttendanceResponse[]
}

export interface PeriodAttendanceEntry {
  studentUserId: number
  status: AttendanceStatus
  absenceReason?: AbsenceReason
  comment?: string
}

export interface PeriodAttendanceRequest {
  attendanceDate: string
  entries: PeriodAttendanceEntry[]
}

export interface PeriodAttendanceSummary {
  date: string
  teamId: number
  periodNumber: number
  total: number
  attending: number
  partial: number
  absent: number
  undecided: number
}

export interface PeriodAttendanceUpdateRequest {
  status?: AttendanceStatus
  absenceReason?: AbsenceReason
  comment?: string
}

export interface CandidateItem {
  studentUserId: number
  displayName: string
  dailyStatus: AttendanceStatus
  previousPeriodStatus?: AttendanceStatus
}

export interface PeriodCandidatesResponse {
  teamId: number
  periodNumber: number
  date: string
  candidates: CandidateItem[]
}

export interface PeriodTimelineItem {
  periodNumber: number
  status: AttendanceStatus
  absenceReason?: AbsenceReason
  comment?: string
}

export interface StudentTimelineResponse {
  studentUserId: number
  date: string
  dailyStatus: AttendanceStatus
  periods: PeriodTimelineItem[]
}

// --- FamilyAttendanceNotice ---

export type FamilyNoticeType = 'ABSENCE' | 'LATE' | 'EARLY_LEAVE' | 'OTHER'

export type FamilyNoticeReason =
  | 'SICK'
  | 'INJURY'
  | 'FAMILY_REASON'
  | 'BEREAVEMENT'
  | 'INFECTIOUS_DISEASE'
  | 'MENTAL_HEALTH'
  | 'OFFICIAL_BUSINESS'
  | 'OTHER'

export type FamilyNoticeStatus = 'PENDING' | 'ACKNOWLEDGED' | 'APPLIED'

export interface FamilyAttendanceNoticeRequest {
  teamId: number
  studentUserId: number
  attendanceDate: string
  noticeType: FamilyNoticeType
  reason?: FamilyNoticeReason
  reasonDetail?: string
  expectedArrivalTime?: string
  expectedLeaveTime?: string
}

export interface FamilyAttendanceNoticeResponse {
  id: number
  teamId: number
  studentUserId: number
  submitterUserId: number
  attendanceDate: string
  noticeType: FamilyNoticeType
  reason?: FamilyNoticeReason
  reasonDetail?: string
  expectedArrivalTime?: string
  expectedLeaveTime?: string
  attachedDownloadUrls?: string[]
  status: FamilyNoticeStatus
  acknowledgedBy?: number
  acknowledgedAt?: string
  appliedToRecord: boolean
  createdAt: string
  updatedAt: string
}

export interface FamilyNoticeListResponse {
  teamId: number
  attendanceDate: string
  records: FamilyAttendanceNoticeResponse[]
  totalCount: number
  unacknowledgedCount: number
}

// --- TransitionAlert ---

export type TransitionAlertLevel = 'NORMAL' | 'URGENT'

export interface TransitionAlertResponse {
  id: number
  teamId: number
  studentUserId: number
  attendanceDate: string
  previousPeriodNumber: number
  currentPeriodNumber: number
  previousPeriodStatus: AttendanceStatus
  currentPeriodStatus: AttendanceStatus
  alertLevel: TransitionAlertLevel
  resolved: boolean
  resolvedAt?: string
  resolvedBy?: number
  resolutionNote?: string
  createdAt: string
}

export interface TransitionAlertListResponse {
  teamId: number
  attendanceDate: string
  alerts: TransitionAlertResponse[]
  totalCount: number
  unresolvedCount: number
}

export interface TransitionAlertResolveRequest {
  note: string
}

// --- Statistics ---

export interface AttendanceStatisticsSummary {
  studentUserId: number
  presentDays: number
  absentDays: number
  lateCount: number
  earlyLeaveCount: number
  attendanceRate: number
}

export interface MonthlyStatisticsResponse {
  year: number
  month: number
  teamId: number
  totalSchoolDays: number
  totalStudents: number
  presentCount: number
  absentCount: number
  undecidedCount: number
  attendanceRate: number
  studentBreakdown: AttendanceStatisticsSummary[]
}

export interface SubjectAttendanceBreakdown {
  subjectName: string
  presentPeriods: number
  totalPeriods: number
  attendanceRate: number
}

export interface StudentTermStatisticsResponse {
  studentUserId: number
  from: string
  to: string
  totalSchoolDays: number
  presentDays: number
  absentDays: number
  lateCount: number
  earlyLeaveCount: number
  attendanceRate: number
  subjectBreakdown: SubjectAttendanceBreakdown[]
}

// --- ClassHomeroom ---

export interface ClassHomeroomResponse {
  id: number
  teamId: number
  homeroomTeacherUserId: number
  assistantTeacherUserIds: number[]
  academicYear: number
  effectiveFrom: string
  effectiveUntil?: string
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface ClassHomeroomCreateRequest {
  homeroomTeacherUserId: number
  assistantTeacherUserIds?: number[]
  academicYear: number
  effectiveFrom: string
  effectiveUntil?: string
}

export interface ClassHomeroomUpdateRequest {
  homeroomTeacherUserId?: number
  assistantTeacherUserIds?: number[]
  effectiveUntil?: string
}

// --- AttendanceLocation ---
export type AttendanceLocation =
  | 'CLASSROOM' | 'SICK_BAY' | 'SEPARATE_ROOM' | 'LIBRARY'
  | 'ONLINE' | 'HOME_LEARNING' | 'OUT_OF_SCHOOL' | 'NOT_APPLICABLE'

export type AttendanceLocationChangeReason =
  | 'FELT_SICK' | 'INJURY' | 'MENTAL_HEALTH' | 'SCHEDULED'
  | 'RECOVERED' | 'RETURNED_TO_CLASS' | 'OTHER'

export interface LocationChangeRequest {
  fromLocation: AttendanceLocation
  toLocation: AttendanceLocation
  changedAtPeriod?: number
  changedAtTime?: string   // "HH:mm"
  reason: AttendanceLocationChangeReason
  note?: string
}

export interface LocationChangeResponse {
  id: number
  teamId: number
  studentUserId: number
  attendanceDate: string
  fromLocation: AttendanceLocation
  toLocation: AttendanceLocation
  changedAtPeriod?: number
  changedAtTime?: string
  reason: AttendanceLocationChangeReason
  note?: string
  recordedBy: number
  recordedAt: string
}

export interface LocationTimelineResponse {
  studentUserId: number
  attendanceDate: string
  changes: LocationChangeResponse[]
  currentLocation: AttendanceLocation
}

export interface LocationListItem {
  studentUserId: number
  currentLocation: AttendanceLocation
  locationChangedDuringDay: boolean
}

export interface LocationListResponse {
  teamId: number
  attendanceDate: string
  items: LocationListItem[]
}

// ===== Phase 10: 出席要件規程 =====

export type RequirementCategory =
  | 'GRADE_PROMOTION'
  | 'GRADUATION'
  | 'SUBJECT_CREDIT'
  | 'PERFECT_ATTENDANCE'
  | 'CUSTOM'

export interface AttendanceRequirementRule {
  id: number
  organizationId: number | null
  teamId: number | null
  termId: number | null
  academicYear: number
  category: RequirementCategory
  name: string
  description: string | null
  minAttendanceRate: number | null
  maxAbsenceDays: number | null
  maxAbsenceRate: number | null
  countSickBayAsPresent: boolean
  countSeparateRoomAsPresent: boolean
  countLibraryAsPresent: boolean
  countOnlineAsPresent: boolean
  countHomeLearningAsOfficialAbsence: boolean
  countLateAsAbsenceThreshold: number
  warningThresholdRate: number | null
  effectiveFrom: string
  effectiveUntil: string | null
  createdAt: string
  updatedAt: string
}

export interface AttendanceRequirementRuleListResponse {
  rules: AttendanceRequirementRule[]
  total: number
}

export interface CreateRequirementRuleRequest {
  organizationId?: number | null
  teamId?: number | null
  termId?: number | null
  academicYear: number
  category: RequirementCategory
  name: string
  description?: string | null
  minAttendanceRate?: number | null
  maxAbsenceDays?: number | null
  maxAbsenceRate?: number | null
  countSickBayAsPresent?: boolean
  countSeparateRoomAsPresent?: boolean
  countLibraryAsPresent?: boolean
  countOnlineAsPresent?: boolean
  countHomeLearningAsOfficialAbsence?: boolean
  countLateAsAbsenceThreshold?: number
  warningThresholdRate?: number | null
  effectiveFrom: string
  effectiveUntil?: string | null
}

export interface UpdateRequirementRuleRequest {
  name?: string
  description?: string | null
  minAttendanceRate?: number | null
  maxAbsenceDays?: number | null
  maxAbsenceRate?: number | null
  countSickBayAsPresent?: boolean
  countSeparateRoomAsPresent?: boolean
  countLibraryAsPresent?: boolean
  countOnlineAsPresent?: boolean
  countHomeLearningAsOfficialAbsence?: boolean
  countLateAsAbsenceThreshold?: number
  warningThresholdRate?: number | null
  effectiveFrom?: string
  effectiveUntil?: string | null
}

// ===== Phase 11: 出席集計 =====

export interface StudentSummaryResponse {
  id: number
  teamId: number
  studentUserId: number
  termId?: number
  academicYear: number
  periodFrom: string
  periodTo: string
  totalSchoolDays: number
  presentDays: number
  absentDays: number
  lateCount: number
  earlyLeaveCount: number
  officialAbsenceDays: number
  schoolActivityDays: number
  sickBayDays: number
  separateRoomDays: number
  onlineDays: number
  homeLearningDays: number
  attendanceRate: number
  totalPeriods: number
  presentPeriods: number
  periodAttendanceRate: number
  subjectBreakdown?: string
  lastRecalculatedAt: string
}

export interface ClassSummaryListResponse {
  teamId: number
  academicYear: number
  termId?: number
  total: number
  summaries: StudentSummaryResponse[]
}

export interface RecalculateSummaryRequest {
  teamId: number
  academicYear: number
  termId?: number
  periodFrom: string
  periodTo: string
}

export interface RecalculateSummaryResponse {
  studentUserId: number
  teamId: number
  academicYear: number
  termId?: number
  recalculatedAt: string
  summary: StudentSummaryResponse
}

// ===== Phase 12: 出席要件評価 =====

export type EvaluationStatus = 'OK' | 'WARNING' | 'RISK' | 'VIOLATION'

export interface AttendanceRequirementEvaluation {
  id: number
  requirementRuleId: number
  studentUserId: number
  summaryId: number
  status: EvaluationStatus
  currentAttendanceRate: number
  remainingAllowedAbsences: number
  evaluatedAt: string
  notifiedUserIds?: number[]
  resolvedAt?: string
  resolutionNote?: string
  resolverUserId?: number
}

export interface StudentEvaluationListResponse {
  studentUserId: number
  evaluations: AttendanceRequirementEvaluation[]
}

export interface AtRiskStudentResponse {
  studentUserId: number
  status: EvaluationStatus
  requirementRuleId: number
  currentAttendanceRate: number
  remainingAllowedAbsences: number
  evaluatedAt: string
}

export interface AtRiskStudentListResponse {
  teamId: number
  total: number
  students: AtRiskStudentResponse[]
}

export interface ResolveEvaluationRequest {
  resolutionNote: string
}

export interface ResolveEvaluationResponse {
  id: number
  status: EvaluationStatus
  resolvedAt: string
  resolutionNote: string
  resolverUserId: number
}
