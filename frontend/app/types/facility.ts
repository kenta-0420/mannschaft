export type FacilityBookingStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CHECKED_IN' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW'

export interface FacilityResponse {
  id: number
  teamId: number
  name: string
  description: string | null
  location: string | null
  capacity: number | null
  imageUrls: string[]
  isActive: boolean
  requiresApproval: boolean
  availableHours: { dayOfWeek: number; startTime: string; endTime: string }[]
  createdAt: string
}

export interface FacilityBookingResponse {
  id: number
  facilityId: number
  facilityName: string
  userId: number
  displayName: string
  bookingDate: string
  startTime: string
  endTime: string
  purpose: string | null
  attendeeCount: number | null
  status: FacilityBookingStatus
  totalFee: number | null
  checkedInAt: string | null
  createdAt: string
}

export interface FacilityRate {
  id: number
  facilityId: number
  name: string
  ratePerHour: number
  ratePerDay: number | null
  isDefault: boolean
}
