export type MemberCardScopeType = 'PLATFORM' | 'TEAM' | 'ORGANIZATION'
export type MemberCardStatus = 'ACTIVE' | 'SUSPENDED' | 'REVOKED'
export type CheckinType = 'STAFF_SCAN' | 'SELF'

export interface MemberCard {
  id: number
  userId: number
  scopeType: MemberCardScopeType
  scopeId: number | null
  scopeName: string | null
  cardCode: string
  cardNumber: string
  status: MemberCardStatus
  checkinCount: number
  lastCheckinAt: string | null
  createdAt: string
}

export interface MemberCardQr {
  token: string
  expiresAt: string
}

export interface CheckinRecord {
  id: number
  memberCardId: number
  checkinType: CheckinType
  checkedInBy: { id: number; displayName: string } | null
  location: string | null
  checkedInAt: string
}

export interface CheckinStats {
  totalCheckins: number
  byDayOfWeek: Record<string, number>
  byHour: Record<string, number>
  monthlyTrend: { month: string; count: number }[]
}

export interface CheckinLocation {
  id: number
  name: string
  locationCode: string
  isActive: boolean
  autoCompleteReservation: boolean
  createdAt: string
}

export interface VerifyRequest {
  token: string
}

export interface VerifyResponse {
  memberCard: MemberCard
  userName: string
  checkinId: number
}

export interface CheckinHistoryRecord {
  id: number
  checkinType: string
  checkedInAt: string
  checkedInByName: string
  location: string
  cardNumber: string
  displayName: string
}

export interface CreateCheckinLocationRequest {
  name: string
  autoCompleteReservation: boolean
}
