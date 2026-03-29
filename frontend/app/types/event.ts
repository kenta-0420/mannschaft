// === Event ===
export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'CANCELLED' | 'CLOSED'

export interface EventResponse {
  id: number
  scopeType: string
  scopeId: number
  slug: string | null
  subtitle: string | null
  coverImageKey: string | null
  status: string
  isPublic: boolean
  registrationStartsAt: string | null
  registrationEndsAt: string | null
  maxCapacity: number | null
  registrationCount: number
  checkinCount: number
  createdAt: string
  updatedAt: string
}

export interface EventDetailResponse {
  id: number
  scopeType: string
  scopeId: number
  scheduleId: number | null
  slug: string | null
  subtitle: string | null
  summary: string | null
  coverImageKey: string | null
  venueName: string | null
  venueAddress: string | null
  venueLatitude: number | null
  venueLongitude: number | null
  venueAccessInfo: string | null
  status: string
  isPublic: boolean
  minRegistrationRole: string | null
  registrationStartsAt: string | null
  registrationEndsAt: string | null
  maxCapacity: number | null
  isApprovalRequired: boolean
  postSurveyId: number | null
  workflowRequestId: number | null
  ogpTitle: string | null
  ogpDescription: string | null
  ogpImageKey: string | null
  registrationCount: number
  checkinCount: number
  createdBy: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface CreateEventRequest {
  scheduleId?: number
  slug?: string
  subtitle?: string
  summary?: string
  coverImageKey?: string
  venueName?: string
  venueAddress?: string
  venueLatitude?: number
  venueLongitude?: number
  venueAccessInfo?: string
  isPublic?: boolean
  minRegistrationRole?: string
  registrationStartsAt?: string
  registrationEndsAt?: string
  maxCapacity?: number
  isApprovalRequired?: boolean
  ogpTitle?: string
  ogpDescription?: string
  ogpImageKey?: string
}

export interface UpdateEventRequest {
  slug?: string
  subtitle?: string
  summary?: string
  coverImageKey?: string
  venueName?: string
  venueAddress?: string
  venueLatitude?: number
  venueLongitude?: number
  venueAccessInfo?: string
  isPublic?: boolean
  minRegistrationRole?: string
  registrationStartsAt?: string
  registrationEndsAt?: string
  maxCapacity?: number
  isApprovalRequired?: boolean
  ogpTitle?: string
  ogpDescription?: string
  ogpImageKey?: string
}

// === Registration ===
export interface RegistrationResponse {
  id: number
  eventId: number
  userId: number | null
  ticketTypeId: number | null
  guestName: string | null
  guestEmail: string | null
  guestPhone: string | null
  status: string
  quantity: number
  note: string | null
  approvedBy: number | null
  approvedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  inviteTokenId: number | null
  createdAt: string
  updatedAt: string
}

export interface CreateRegistrationRequest {
  ticketTypeId: number
  quantity?: number
  note?: string
}

export interface GuestRegistrationRequest {
  ticketTypeId: number
  guestName?: string
  guestEmail?: string
  guestPhone?: string
  quantity?: number
  note?: string
  inviteToken?: string
}

// === Checkin ===
export interface CheckinResponse {
  id: number
  eventId: number
  ticketId: number | null
  checkinType: string
  checkedInBy: number | null
  checkedInAt: string
  note: string | null
  createdAt: string
}

export interface CheckinRequest {
  qrToken: string
  note?: string
}

export interface SelfCheckinRequest {
  locationQrToken: string
}

// === TicketType ===
export interface TicketTypeResponse {
  id: number
  eventId: number
  name: string
  description: string | null
  price: number | null
  currency: string | null
  maxQuantity: number | null
  issuedCount: number
  minRegistrationRole: string | null
  isActive: boolean
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface CreateTicketTypeRequest {
  name?: string
  description?: string
  price?: number
  currency?: string
  maxQuantity?: number
  minRegistrationRole?: string
  sortOrder?: number
}

export interface UpdateTicketTypeRequest {
  name?: string
  description?: string
  price?: number
  currency?: string
  maxQuantity?: number
  minRegistrationRole?: string
  isActive?: boolean
  sortOrder?: number
}

// === Timetable Item (Event) ===
export interface TimetableItemResponse {
  id: number
  eventId: number
  title: string
  description: string | null
  speaker: string | null
  startAt: string | null
  endAt: string | null
  location: string | null
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface CreateTimetableItemRequest {
  title?: string
  description?: string
  speaker?: string
  startAt?: string
  endAt?: string
  location?: string
  sortOrder?: number
}

export interface UpdateTimetableItemRequest {
  title?: string
  description?: string
  speaker?: string
  startAt?: string
  endAt?: string
  location?: string
  sortOrder?: number
}

export interface ReorderTimetableRequest {
  itemIds: number[]
}

// === Invite Token ===
export interface InviteTokenResponse {
  id: number
  token: string
  roleName: string | null
  expiresAt: string | null
  maxUses: number | null
  usedCount: number
  revokedAt: string | null
  createdAt: string
}

export interface CreateInviteTokenRequest {
  roleId: number
  expiresIn?: string
  maxUses?: number
}

// === EventCategory ===
export interface EventCategoryResponse {
  id: number
  name: string
  color: string | null
  icon: string | null
  isDayOffCategory: boolean
  sortOrder: number
  scope: string | null
}
