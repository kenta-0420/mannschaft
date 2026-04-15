export type TeamTemplate =
  | 'CLUB'
  | 'CLINIC'
  | 'CLASS'
  | 'COMMUNITY'
  | 'COMPANY'
  | 'FAMILY'
  | 'RESTAURANT'
  | 'BEAUTY'
  | 'STORE'
  | 'VOLUNTEER'
  | 'NEIGHBORHOOD'
  | 'CONDO'
  | 'OTHER'
export type TeamVisibility = 'PUBLIC' | 'ORGANIZATION_ONLY' | 'PRIVATE'

export interface TeamResponse {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  template: TeamTemplate
  prefecture: string | null
  city: string | null
  description: string | null
  visibility: TeamVisibility
  supporterEnabled: boolean
  version: number
  memberCount: number
  archivedAt: string | null
  createdAt: string
}

export interface TeamSummaryResponse {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  template: TeamTemplate
  memberCount: number
  supporterEnabled: boolean
}

export interface CreateTeamRequest {
  name: string
  nameKana?: string
  nickname1?: string
  nickname2?: string
  template: TeamTemplate
  prefecture?: string
  city?: string
  description?: string
  visibility: TeamVisibility
  supporterEnabled: boolean
}

export interface UpdateTeamRequest {
  name?: string
  nameKana?: string
  nickname1?: string
  nickname2?: string
  prefecture?: string
  city?: string
  description?: string
  visibility?: TeamVisibility
  supporterEnabled?: boolean
}

// === F01.2 拡張プロフィール ===

export type EstablishedDatePrecision = 'YEAR' | 'YEAR_MONTH' | 'FULL'

export interface ProfileVisibility {
  homepage_url?: boolean
  established_date?: boolean
  philosophy?: boolean
  officers?: boolean
  custom_fields?: boolean
}

export interface TeamProfileResponse {
  id: number
  homepage_url: string | null
  established_date: string | null
  established_date_precision: EstablishedDatePrecision | null
  philosophy: string | null
  profile_visibility: ProfileVisibility | null
}

export interface UpdateTeamProfileRequest {
  homepage_url?: string | null
  established_date?: string | null
  established_date_precision?: EstablishedDatePrecision | null
  philosophy?: string | null
  profile_visibility?: ProfileVisibility | null
}

export interface TeamOfficerResponse {
  id: number
  team_id: number
  name: string
  title: string
  display_order: number
  is_visible: boolean
  is_publicly_visible: boolean | null
}

export interface CreateTeamOfficerRequest {
  name: string
  title: string
  is_visible?: boolean
}

export interface UpdateTeamOfficerRequest {
  name?: string
  title?: string
  is_visible?: boolean
}

export interface TeamCustomFieldResponse {
  id: number
  team_id: number
  label: string
  value: string
  display_order: number
  is_visible: boolean
  is_publicly_visible: boolean | null
}

export interface CreateTeamCustomFieldRequest {
  label: string
  value: string
  is_visible?: boolean
}

export interface UpdateTeamCustomFieldRequest {
  label?: string
  value?: string
  is_visible?: boolean
}
