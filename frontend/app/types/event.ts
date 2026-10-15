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
  scopeId: string
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

// Wave 1 DTO刷新: フラット構造からネスト構造へ移行

export interface EventScopeDto {
  scopeType?: string
  scopeId?: number
  scheduleId?: number | null
  workflowRequestId?: number | null
}

export interface EventContentDto {
  slug?: string | null
  subtitle?: string | null
  summary?: string | null
  coverImageKey?: string | null
}

export interface EventVenueDto {
  venueName?: string | null
  venueAddress?: string | null
  venueLatitude?: number | null
  venueLongitude?: number | null
  venueAccessInfo?: string | null
}

export interface EventRegistrationDto {
  registrationStartsAt?: string | null
  registrationEndsAt?: string | null
  maxCapacity?: number | null
  isApprovalRequired?: boolean
  attendanceMode?: AttendanceMode | null
  preSurveyId?: number | null
  postSurveyId?: number | null
  registrationCount?: number
  checkinCount?: number
}

export interface EventMetaDto {
  status?: string
  visibility?: EventVisibility
  ogpTitle?: string | null
  ogpDescription?: string | null
  ogpImageKey?: string | null
}

export interface EventAuditDto {
  createdBy?: number | null
  createdAt?: string
  updatedAt?: string
  version?: number
}

export interface EventDetailResponse {
  id: number
  scope?: EventScopeDto
  content?: EventContentDto
  venue?: EventVenueDto
  registration?: EventRegistrationDto
  meta?: EventMetaDto
  audit?: EventAuditDto
  rsvpSummary?: EventRsvpSummary | null
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

// === EventChatChannel ===
// GET /api/v1/events/{eventId}/channel のレスポンス型
// バックエンドの com.mannschaft.app.chat.dto.ChannelResponse に対応
export interface EventChatChannelResponse {
  id: number
  channelType: string
  teamId: number | null
  organizationId: number | null
  name: string | null
  iconKey: string | null
  description: string | null
  isPrivate: boolean
  createdBy: number | null
  lastMessageAt: string | null
  lastMessagePreview: string | null
  sourceType: string | null
  sourceId: number | null
  isArchived: boolean
  version: number | null
  createdAt: string
  updatedAt: string
}

// ─── F03.10 代理出席 ───────────────────────────────────────
export type EventDelegationStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'

export interface EventDelegationResponse {
  id: string  // UUIDv7
  eventId: number
  delegatorId: number
  delegatorName: string
  delegateId: number
  delegateName: string
  status: EventDelegationStatus
  reason: string | null
  proxyVoteSessionId: number | null   // F08.3 投票代理連携
  proxyDelegationId: number | null    // F08.3 連携作成後設定
  reviewedAt: string | null
  createdAt: string
}

export interface CreateEventDelegationRequest {
  delegateId: number
  reason?: string
  proxyVoteSessionId?: number  // 任意: 投票セッションも委任する場合
}

export interface EventDelegationListResponse {
  data: EventDelegationResponse[]
  total: number
  page: number
  size: number
}

export interface EventDelegationMeResponse {
  asDelegator: EventDelegationResponse | null
  asDelegate: EventDelegationResponse | null
}

export interface ProxyCheckinResponse {
  checkinId: number
  eventId: number
  delegationId: string
  delegateId: number
  delegateName: string
  delegatorId: number
  delegatorName: string
  checkinType: 'PROXY'
  checkedInAt: string
}
