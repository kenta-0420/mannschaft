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
