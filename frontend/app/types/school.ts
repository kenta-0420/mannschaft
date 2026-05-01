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
