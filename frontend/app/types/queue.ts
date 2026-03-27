export type QueueMode = 'INDIVIDUAL' | 'SHARED'
export type TicketStatus = 'WAITING' | 'CALLED' | 'SERVING' | 'COMPLETED' | 'NO_SHOW' | 'CANCELLED'
export type ReceptionMethod = 'QR' | 'ONLINE' | 'BOTH'

export interface QueueCategoryResponse {
  id: number
  name: string
  queueMode: QueueMode
  prefix: string
  maxQueueSize: number | null
  sortOrder: number
}

export interface QueueCounterResponse {
  id: number
  name: string
  categoryId: number
  categoryName: string
  receptionMethod: ReceptionMethod
  averageServiceMinutes: number
  isActive: boolean
  currentTicket: string | null
}

export interface QueueTicketResponse {
  id: number
  ticketNumber: string
  categoryId: number
  categoryName: string
  counterId: number | null
  counterName: string | null
  userId: number | null
  displayName: string | null
  phoneNumber: string | null
  status: TicketStatus
  position: number | null
  estimatedWaitMinutes: number | null
  calledAt: string | null
  servingStartedAt: string | null
  completedAt: string | null
  createdAt: string
}

export interface QueueStatusResponse {
  counters: Array<{
    id: number
    name: string
    isActive: boolean
    currentTicket: string | null
    waitingCount: number
    estimatedWaitMinutes: number
  }>
  totalWaiting: number
  averageWaitMinutes: number
}

export interface QueueSettingsResponse {
  ticketResetMode: 'DAILY' | 'CONTINUOUS'
  callTimeoutMinutes: number
  noShowPenaltyThreshold: number
  noShowBanDays: number
}

export interface CreateQueueTicketRequest {
  categoryId: number
  counterId?: number
  phoneNumber?: string
}
