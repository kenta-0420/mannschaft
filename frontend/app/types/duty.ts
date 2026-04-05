export interface DutyRotationResponse {
  id: number
  teamId: number
  dutyName: string
  rotationType: string
  memberOrder: number[]
  startDate: string
  icon: string
  todayAssignee: number | null
  createdAt: string
  enabled: boolean
}

export interface DutyRotationRequest {
  dutyName?: string
  rotationType?: string
  memberOrder?: number[]
  startDate: string
  icon?: string
  isEnabled?: boolean
}

export interface DutyTodayResponse {
  dutyId: number
  dutyName: string
  icon: string
  assigneeUserId: number
  rotationType: string
}
