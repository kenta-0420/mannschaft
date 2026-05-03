// === Event ===
export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'REGISTRATION_OPEN' | 'REGISTRATION_CLOSED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'

export type EventVisibility = 'PUBLIC' | 'SUPPORTERS_AND_ABOVE' | 'MEMBERS_ONLY'

export type AttendanceMode = 'NONE' | 'RSVP' | 'REGISTRATION'

export type RsvpResponse = 'ATTENDING' | 'NOT_ATTENDING' | 'MAYBE' | 'UNDECIDED'

export interface EventRsvpResponseItem {
  id: number
  eventId: number
  userId: number
  userName: string
  response: RsvpResponse
  comment: string | null
  respondedAt: string | null
}

export interface EventRsvpSummary {
  attending: number
  notAttending: number
  maybe: number
  undecided: number
  total: number
}

export interface SubmitRsvpRequest {
  response: RsvpResponse
  comment?: string
}

export interface EventResponse {
  id: number
  scopeType: string
  scopeId: number
  slug: string | null
  subtitle: string | null
  coverImageKey: string | null
  status: string
  visibility: EventVisibility
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
  visibility: EventVisibility
  registrationStartsAt: string | null
  registrationEndsAt: string | null
  maxCapacity: number | null
  isApprovalRequired: boolean
  attendanceMode: AttendanceMode | null
  preSurveyId: number | null
  postSurveyId: number | null
  workflowRequestId: number | null
  ogpTitle: string | null
  ogpDescription: string | null
  ogpImageKey: string | null
  registrationCount: number
  checkinCount: number
  rsvpSummary: EventRsvpSummary | null
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
  visibility?: EventVisibility
  registrationStartsAt?: string
  registrationEndsAt?: string
  maxCapacity?: number
  isApprovalRequired?: boolean
  attendanceMode?: AttendanceMode
  preSurveyId?: number | null
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
  visibility?: EventVisibility
  registrationStartsAt?: string
  registrationEndsAt?: string
  maxCapacity?: number
  isApprovalRequired?: boolean
  attendanceMode?: AttendanceMode
  preSurveyId?: number | null
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
// member.ts と名前が衝突するため EventInviteTokenResponse として定義
export interface EventInviteTokenResponse {
  id: number
  token: string
  roleName: string | null
  expiresAt: string | null
  maxUses: number | null
  usedCount: number
  revokedAt: string | null
  createdAt: string
}

export interface EventCreateInviteTokenRequest {
  roleId: number
  expiresIn?: string
  maxUses?: number
}

// === EventCategory ===
// schedule.ts と名前が衝突するため EventCategoryItem として定義
export interface EventCategoryItem {
  id: number
  name: string
  color: string | null
  icon: string | null
  isDayOffCategory: boolean
  sortOrder: number
  scope: string | null
}
