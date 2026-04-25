export type ShiftScheduleStatus = 'DRAFT' | 'COLLECTING' | 'ADJUSTING' | 'PUBLISHED' | 'ARCHIVED'
export type ShiftRequestPreference = 'WANT' | 'DONT_WANT' | 'NEUTRAL'
export type ShiftSwapStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'

export interface ShiftScheduleResponse {
  id: number
  teamId: number
  title: string
  periodStart: string
  periodEnd: string
  status: ShiftScheduleStatus
  collectingDeadline: string | null
  publishedAt: string | null
  createdBy: { id: number; displayName: string }
  createdAt: string
}

export interface ShiftPositionResponse {
  id: number
  name: string
  description: string | null
  requiredCount: number
  color: string | null
  sortOrder: number
}

export interface ShiftSlotResponse {
  id: number
  scheduleId: number
  positionId: number
  positionName: string
  date: string
  startTime: string
  endTime: string
  requiredCount: number
  assignedMembers: Array<{
    userId: number
    displayName: string
    avatarUrl: string | null
  }>
}

export interface ShiftRequestResponse {
  id: number
  scheduleId: number
  userId: number
  displayName: string
  date: string
  startTime: string
  endTime: string
  preference: ShiftRequestPreference
  note: string | null
  submittedAt: string
}

export interface ShiftSwapResponse {
  id: number
  requesterId: number
  requesterName: string
  targetId: number
  targetName: string
  slotId: number
  date: string
  positionName: string
  status: ShiftSwapStatus
  reason: string | null
  createdAt: string
}

export interface AvailabilityDefaultResponse {
  dayOfWeek: number
  startTime: string | null
  endTime: string | null
  isAvailable: boolean
}

export interface CreateShiftScheduleRequest {
  title: string
  periodStart: string
  periodEnd: string
  collectingDeadline?: string
}

export interface SubmitShiftRequestRequest {
  date: string
  startTime: string
  endTime: string
  preference: ShiftRequestPreference
  note?: string
}

// 自動割当
export type AssignmentStrategyType = 'MANUAL' | 'GREEDY_V1' | 'CSP_V1'
export type ShiftAssignmentStatus = 'PROPOSED' | 'CONFIRMED' | 'REVOKED'
export type ShiftAssignmentRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CONFIRMED' | 'REVOKED'

export interface AssignmentParameters {
  preferenceWeight?: number
  fairnessWeight?: number
  consecutivePenaltyWeight?: number
  respectWorkConstraints?: boolean
  overwriteExisting?: boolean
}

export interface AssignmentWarning {
  code: string
  message: string
  slotId?: number
  userId?: number
}

export interface ProposedAssignment {
  id: number
  slotId: number
  userId: number
  status: ShiftAssignmentStatus
  score?: number
  note?: string
}

export interface AssignmentRun {
  id: number
  scheduleId: number
  strategy: AssignmentStrategyType
  status: ShiftAssignmentRunStatus
  triggeredBy: number
  slotsTotal: number
  slotsFilled: number
  warnings?: AssignmentWarning[]
  parameters?: AssignmentParameters
  errorMessage?: string
  visualReviewConfirmedBy?: number
  visualReviewConfirmedAt?: string
  visualReviewNote?: string
  startedAt: string
  completedAt?: string
  assignments?: ProposedAssignment[]
}

// 勤務制約
export interface WorkConstraint {
  id?: number
  teamId: number
  userId?: number
  maxMonthlyHours?: number
  maxMonthlyDays?: number
  maxConsecutiveDays?: number
  maxNightShiftsPerMonth?: number
  minRestHoursBetweenShifts?: number
  note?: string
}
