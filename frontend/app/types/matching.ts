// === Enums ===
export type MatchActivityType = 'COMPETITION' | 'PRACTICE' | 'EXCHANGE' | 'RECRUIT' | 'OTHER'
export type MatchCategory = 'ELEMENTARY' | 'JUNIOR_HIGH' | 'HIGH_SCHOOL' | 'UNIVERSITY' | 'ADULT' | 'SENIOR' | 'ANY'
export type MatchLevel = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED' | 'ANY'
export type MatchRequestStatus = 'OPEN' | 'MATCHED' | 'CANCELLED' | 'EXPIRED'
export type MatchProposalStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED' | 'WITHDRAWN'
export type MatchVisibility = 'PLATFORM' | 'ORGANIZATION'
export type MatchCancellationType = 'UNILATERAL' | 'MUTUAL_PENDING' | 'MUTUAL'
export type MatchRequestSort = 'newest' | 'expiring_soon' | 'most_proposals'

// === Response Types ===

export interface MatchTeamSummary {
  id: number
  name: string
  averageRating: number | null
  reviewCount: number
  cancelCount: number
}

export interface RequestContentDto {
  title: string
  description: string | null
  activityType: string
  activityDetail: string | null
  category: string
  visibility: string
}

export interface RequestLocationDto {
  prefectureCode: string
  cityCode: string | null
  venueName: string | null
}

export interface RequestScheduleDto {
  preferredDateFrom: string | null
  preferredDateTo: string | null
  preferredTimeFrom: string | null
  preferredTimeTo: string | null
}

export interface RequestParticipantsDto {
  level: string
  minParticipants: number | null
  maxParticipants: number | null
}

export interface RequestStatusDto {
  status: string
  proposalCount: number
  expiresAt: string | null
  cancelCount: number
}

export interface MatchRequestResponse {
  id: number
  team: MatchTeamSummary
  content: RequestContentDto
  location: RequestLocationDto
  schedule: RequestScheduleDto
  participants: RequestParticipantsDto
  status: RequestStatusDto
  createdAt: string
}

export interface MatchProposalDateResponse {
  id: number
  proposed_date: string
  proposed_time_from: string | null
  proposed_time_to: string | null
  is_selected: boolean
}

export interface ProposalContentDto {
  message: string | null
  proposedVenue: string | null
}

export interface ProposalStatusDto {
  status: string
  statusReason: string | null
  cancelledByTeamId: number | null
  cancellationType: string | null
  mutualAgreedAt: string | null
}

export interface ProposalAuditDto {
  createdAt: string
  updatedAt: string
}

export interface MatchProposalResponse {
  id: number
  requestId: number
  proposingTeamId: number
  proposingTeamName?: string
  content: ProposalContentDto
  status: ProposalStatusDto
  proposedDates: MatchProposalDateResponse[]
  audit: ProposalAuditDto
}

export interface MatchReviewResponse {
  id: number
  proposal_id: number
  activity_type: MatchActivityType
  reviewer_team: { id: number; name: string }
  rating: number
  comment: string | null
  is_public: boolean
  created_at: string
}

export interface MatchReviewSummary {
  team_id: number
  average_rating: number | null
  review_count: number
  reviews: MatchReviewResponse[]
}

export interface NgTeamResponse {
  blocked_team_id: number
  blocked_team_name: string
  created_at: string
}

export interface MatchRequestTemplateResponse {
  id: number
  name: string
  template_json: Record<string, unknown>
  created_at: string
}

export interface MatchNotificationPreferencesResponse {
  prefecture_code: string | null
  city_code: string | null
  activity_type: MatchActivityType | null
  category: MatchCategory | null
  is_enabled: boolean
}

export interface MatchCancellationResponse {
  proposal_id: number
  request_title: string
  opponent_team: { id: number; name: string }
  cancellation_type: MatchCancellationType
  status_reason: string | null
  cancelled_at: string
}

export interface PrefectureResponse {
  code: string
  name: string
}

export interface CityResponse {
  code: string
  name: string
}

export interface ActivitySuggestionResponse {
  activity_detail: string
  usage_count: number
}

// === Request Types ===

export interface CreateMatchRequestBody {
  title: string
  description?: string
  activity_type: MatchActivityType
  activity_detail?: string
  category: MatchCategory
  prefecture_code: string
  city_code?: string
  venue_name?: string
  preferred_date_from?: string
  preferred_date_to?: string
  preferred_time_from?: string
  preferred_time_to?: string
  level: MatchLevel
  min_participants?: number
  max_participants?: number
  visibility: MatchVisibility
  expires_at?: string
}

export interface CreateMatchProposalBody {
  message?: string
  proposed_dates: { date: string; time_from?: string; time_to?: string }[]
  proposed_venue?: string
}

export interface MatchRequestSearchParams {
  // BE MatchRequestController が読むクエリパラメータ名は camelCase。
  // snake_case で送ると null バインドされ無視されるため camelCase に揃える。
  prefectureCode?: string
  cityCode?: string
  activityType?: MatchActivityType
  category?: MatchCategory
  keyword?: string
  level?: MatchLevel
  status?: MatchRequestStatus
  visibility?: MatchVisibility
  sort?: MatchRequestSort
  page?: number
  per_page?: number
}
