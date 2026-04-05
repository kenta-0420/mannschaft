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
  average_rating: number | null
  review_count: number
  cancel_count: number
}

export interface MatchRequestResponse {
  id: number
  team: MatchTeamSummary
  title: string
  description: string | null
  activity_type: MatchActivityType
  activity_detail: string | null
  category: MatchCategory
  visibility: MatchVisibility
  prefecture_code: string
  city_code: string | null
  venue_name: string | null
  preferred_date_from: string | null
  preferred_date_to: string | null
  preferred_time_from: string | null
  preferred_time_to: string | null
  level: MatchLevel
  min_participants: number | null
  max_participants: number | null
  status: MatchRequestStatus
  proposal_count: number
  expires_at: string | null
  created_at: string
}

export interface MatchProposalDateResponse {
  id: number
  proposed_date: string
  proposed_time_from: string | null
  proposed_time_to: string | null
  is_selected: boolean
}

export interface MatchProposalResponse {
  id: number
  request_id: number
  proposing_team_id: number
  proposing_team_name: string
  message: string | null
  proposed_venue: string | null
  proposed_dates: MatchProposalDateResponse[]
  status: MatchProposalStatus
  status_reason: string | null
  cancelled_by_team_id: number | null
  cancellation_type: MatchCancellationType | null
  mutual_agreed_at: string | null
  request?: {
    title: string
    activity_type: MatchActivityType
    prefecture_code: string
    status: MatchRequestStatus
  }
  created_at: string
  updated_at: string
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
  prefecture_code?: string
  city_code?: string
  activity_type?: MatchActivityType
  category?: MatchCategory
  keyword?: string
  level?: MatchLevel
  status?: MatchRequestStatus
  visibility?: MatchVisibility
  sort?: MatchRequestSort
  page?: number
  per_page?: number
}
