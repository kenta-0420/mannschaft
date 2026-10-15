export interface PresenceStatusResponse {
  user: { id: number; displayName: string }
  status: string
  destination: string | null
  expectedReturnAt: string | null
  lastEventAt: string | null
}

export interface PresenceGoingOutRequest {
  destination?: string
  expectedReturnAt?: string
  message?: string
}

export interface PresenceHomeRequest {
  message?: string
}

export interface PresenceEventResponse {
  id: number
  eventType: string
  message: string | null
  destination: string | null
  expectedReturnAt: string | null
  user: { id: number; displayName: string }
  createdAt: string
}

export interface PresenceStatsResponse {
  period: string
  totalEvents: number
  totalHomeEvents: number
  totalGoingOutEvents: number
  overdueCount: number
  memberStats: MemberStats[]
}

export interface MemberStats {
  userId: number
  homeCount: number
  goingOutCount: number
  overdueCount: number
}

export interface PresenceIconResponse {
  eventType: string
  icon: string
}

export interface PresenceIconRequest {
  icons: IconEntry[]
}

export interface IconEntry {
  eventType: string
  icon: string
}
