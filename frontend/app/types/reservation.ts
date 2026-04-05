export type ReservationStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'COMPLETED' | 'NO_SHOW'

export interface ReservationLineResponse {
  id: number
  name: string
  description: string | null
  capacity: number
  isActive: boolean
  sortOrder: number
  defaultStaffId: number | null
  defaultStaffName: string | null
}

export interface ReservationSlotResponse {
  id: number
  lineId: number
  lineName: string
  date: string
  startTime: string
  endTime: string
  capacity: number
  bookedCount: number
  staffId: number | null
  staffName: string | null
  isClosed: boolean
}

export interface ReservationResponse {
  id: number
  slotId: number
  lineId: number
  lineName: string
  date: string
  startTime: string
  endTime: string
  userId: number
  displayName: string
  status: ReservationStatus
  serviceNotes: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateReservationRequest {
  slotId: number
  serviceNotes?: string
}

export interface BusinessHourResponse {
  dayOfWeek: number
  openTime: string | null
  closeTime: string | null
  isClosed: boolean
}

export interface CreateSlotRequest {
  lineId: number
  date: string
  startTime: string
  endTime: string
  capacity?: number
  staffId?: number
}
