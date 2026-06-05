export type FacilityBookingStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'CHECKED_IN'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_SHOW'

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

export interface FacilityDetailResponse {
  id: number
  scopeType: string
  scopeId: string
  name: string
  facilityType: string
  facilityTypeLabel: string
  capacity: number
  floor: string | null
  locationDetail: string | null
  description: string | null
  imageUrls: string[]
  ratePerSlot: number | null
  ratePerNight: number | null
  checkInTime: string | null
  checkOutTime: string | null
  cleaningBufferMinutes: number
  autoApprove: boolean
  isActive: boolean
  displayOrder: number
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface FacilitySettingsResponse {
  id: number
  scopeType: string
  scopeId: string
  requiresApproval: boolean
  maxBookingsPerDayPerUser: number
  allowStripePayment: boolean
  cancellationDeadlineHours: number
  noShowPenaltyEnabled: boolean
  noShowPenaltyThreshold: number
  noShowPenaltyDays: number
  createdAt: string
  updatedAt: string
}

export interface FacilityStatsResponse {
  totalFacilities: number
  activeFacilities: number
  totalBookings: number
  completedBookings: number
  cancelledBookings: number
  noShowBookings: number
  totalRevenue: number
  totalPlatformFee: number
}

// Wave 1 DTO刷新: フラット構造からネスト構造へ移行

export interface BookingFacilityDto {
  facilityId?: number
  facilityName?: string | null
  bookedBy?: number
  createdByAdmin?: number | null
}

export interface BookingScheduleDto {
  bookingDate?: string
  checkOutDate?: string | null
  stayNights?: number | null
  timeFrom?: string
  timeTo?: string
  slotCount?: number
}

export interface BookingUsageDto {
  purpose?: string | null
  attendeeCount?: number | null
}

export interface BookingFeeDto {
  usageFee?: number
  equipmentFee?: number
  totalFee?: number
}

export interface BookingApprovalDto {
  approvedBy?: number | null
  approvedAt?: string | null
  adminComment?: string | null
}

export interface BookingLifecycleDto {
  checkedInAt?: string | null
  completedAt?: string | null
  cancelledAt?: string | null
  cancelledBy?: number | null
  cancellationReason?: string | null
}

export interface BookingAuditDto {
  createdAt?: string
  updatedAt?: string
}

export interface BookingDetailResponse {
  id?: number
  status?: string
  facility?: BookingFacilityDto
  schedule?: BookingScheduleDto
  usage?: BookingUsageDto
  fee?: BookingFeeDto
  approval?: BookingApprovalDto
  lifecycle?: BookingLifecycleDto
  audit?: BookingAuditDto
  equipment?: BookingEquipmentResponse[]
}

export interface BookingEquipmentResponse {
  equipmentId: number
  equipmentName: string
  quantity: number
  pricePerUse: number
}

export interface BookingPaymentResponse {
  id: number
  bookingId: number
  payerUserId: number
  paymentMethod: string
  amount: number
  stripeFee: number
  platformFee: number
  platformFeeRate: number
  netAmount: number
  status: string
  failedReason: string | null
  paidAt: string | null
  refundedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CalendarBookingResponse {
  id: number
  facilityId: number
  facilityName: string
  bookingDate: string
  checkOutDate: string | null
  timeFrom: string
  timeTo: string
  status: string
  bookedBy: number
}

export interface FacilityEquipmentResponse {
  id: number
  facilityId: number
  name: string
  description: string | null
  totalQuantity: number
  pricePerUse: number
  isAvailable: boolean
  displayOrder: number
  createdAt: string
  updatedAt: string
}

export interface TimeRateResponse {
  id: number
  facilityId: number
  dayType: string
  timeFrom: string
  timeTo: string
  ratePerSlot: number
}

export interface UsageRuleResponse {
  id: number
  facilityId: number
  maxHoursPerBooking: number
  minHoursPerBooking: number
  maxBookingsPerMonthPerUser: number
}
