export type ParkingSpaceStatus = 'AVAILABLE' | 'ASSIGNED' | 'RESERVED' | 'MAINTENANCE'

export interface ParkingSpaceResponse {
  id: number
  teamId: number
  spaceNumber: string
  area: string | null
  spaceType: 'STANDARD' | 'COMPACT' | 'LARGE' | 'EV_CHARGING' | 'MOTORCYCLE' | 'DISABLED'
  status: ParkingSpaceStatus
  assignedTo: { userId: number; displayName: string; vehiclePlate: string } | null
  monthlyFee: number | null
  createdAt: string
}

export interface VehicleResponse {
  id: number
  userId: number
  plateNumber: string
  vehicleType: string | null
  make: string | null
  model: string | null
  color: string | null
  isDefault: boolean
  createdAt: string
}

export interface ParkingListing {
  id: number
  spaceId: number
  spaceNumber: string
  availableFrom: string
  availableUntil: string | null
  monthlyFee: number
  status: 'ACTIVE' | 'CLOSED'
}

export interface ApplicationResponse {
  id: number
  spaceId: number
  userId: number
  vehicleId: number
  sourceType: string
  listingId: number | null
  status: string
  priority: number
  message: string | null
  rejectionReason: string | null
  lotteryNumber: number | null
  decidedAt: string | null
  createdAt: string
}

export interface ListingResponse {
  id: number
  spaceId: number
  assignmentId: number
  listedBy: number
  reason: string | null
  desiredTransferDate: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface ListingDetailResponse {
  id: number
  spaceId: number
  assignmentId: number
  listedBy: number
  reason: string | null
  desiredTransferDate: string | null
  status: string
  transfereeUserId: number | null
  transfereeVehicleId: number | null
  transferredAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ParkingSettingsResponse {
  id: number
  scopeType: string
  scopeId: number
  maxSpacesPerUser: number
  maxVisitorReservationsPerDay: number
  visitorReservationMaxDaysAhead: number
  visitorReservationRequiresApproval: boolean
}

export interface ParkingStatsResponse {
  totalSpaces: number
  vacantSpaces: number
  occupiedSpaces: number
  maintenanceSpaces: number
  pendingApplications: number
  activeListings: number
  activeSubleases: number
}

export interface SubleaseResponse {
  id: number
  spaceId: number
  offeredBy: number
  title: string
  pricePerMonth: number
  paymentMethod: string
  availableFrom: string
  availableTo: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface SubleaseDetailResponse {
  id: number
  spaceId: number
  assignmentId: number
  offeredBy: number
  title: string
  description: string | null
  pricePerMonth: number
  paymentMethod: string
  availableFrom: string
  availableTo: string | null
  status: string
  matchedApplicationId: number | null
  createdAt: string
  updatedAt: string
}

export interface SubleasePaymentResponse {
  id: number
  subleaseId: number
  payerUserId: number
  payeeUserId: number
  amount: number
  stripeFee: number
  platformFee: number
  netAmount: number
  billingMonth: string
  status: string
  paidAt: string | null
  createdAt: string
}

export interface SubleaseApplicationResponse {
  id: number
  subleaseId: number
  userId: number
  message: string | null
  status: string
  createdAt: string
}

export interface VisitorReservationResponse {
  id: number
  spaceId: number
  reservedBy: number
  visitorName: string
  visitorPlateNumber: string
  reservedDate: string
  timeFrom: string
  timeTo: string
  purpose: string | null
  adminComment: string | null
  approvedBy: number | null
  approvedAt: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface VisitorRecurringResponse {
  id: number
  userId: number
  spaceId: number
  recurrenceType: string
  dayOfWeek: number | null
  dayOfMonth: number | null
  timeFrom: string
  timeTo: string
  visitorName: string
  visitorPlateNumber: string
}

export interface WatchlistResponse {
  id: number
  userId: number
  spaceId: number | null
  area: string | null
  spaceType: string | null
  createdAt: string
}
