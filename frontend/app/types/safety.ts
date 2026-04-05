export type SafetyCheckStatus = 'ACTIVE' | 'CLOSED'
export type SafetyResponseStatus = 'SAFE' | 'NEED_SUPPORT' | 'OTHER'
export type FollowupStatus = 'PENDING' | 'RESOLVED' | 'ESCALATED'

export interface SafetyCheckResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  title: string
  description: string | null
  status: SafetyCheckStatus
  isDrill: boolean
  createdBy: { id: number; displayName: string }
  responseStats: {
    total: number
    responded: number
    safe: number
    needSupport: number
    other: number
    responseRate: number
  }
  createdAt: string
  closedAt: string | null
}

export interface SafetyResponseResponse {
  id: number
  userId: number
  displayName: string
  avatarUrl: string | null
  status: SafetyResponseStatus
  message: string | null
  latitude: number | null
  longitude: number | null
  respondedAt: string
}

export interface SafetyCheckResultsResponse {
  safetyCheck: SafetyCheckResponse
  responses: SafetyResponseResponse[]
  unrespondedMembers: Array<{
    userId: number
    displayName: string
    avatarUrl: string | null
  }>
  followups: SafetyFollowupResponse[]
}

export interface SafetyFollowupResponse {
  id: number
  responseId: number
  userId: number
  displayName: string
  status: FollowupStatus
  note: string | null
  updatedBy: { id: number; displayName: string } | null
  updatedAt: string | null
}

export interface CreateSafetyCheckRequest {
  title: string
  description?: string
  isDrill?: boolean
}

export interface RespondSafetyCheckRequest {
  status: SafetyResponseStatus
  message?: string
  latitude?: number
  longitude?: number
}

export interface SafetyTemplateResponse {
  id: number
  name: string
  title: string
  description: string | null
  responseOptions: string[]
  createdAt: string
}
