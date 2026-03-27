export type TeamTemplate = 'SPORTS' | 'CLINIC' | 'SCHOOL' | 'COMMUNITY' | 'COMPANY' | 'OTHER'
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
