// === レスポンス ===
export interface OrganizationResponse {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  orgType: 'NONPROFIT' | 'FORPROFIT'
  parentOrganizationId: number | null
  prefecture: string | null
  city: string | null
  description: string | null
  visibility: 'PUBLIC' | 'PRIVATE'
  hierarchyVisibility: 'NONE' | 'BASIC' | 'FULL'
  supporterEnabled: boolean
  version: number
  memberCount: number
  archivedAt: string | null
  createdAt: string
}

export interface OrganizationSummaryResponse {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  orgType: 'NONPROFIT' | 'FORPROFIT'
  memberCount: number
  supporterEnabled: boolean
}

// === リクエスト ===
export interface CreateOrganizationRequest {
  name: string
  nameKana?: string
  nickname1?: string
  nickname2?: string
  orgType: 'NONPROFIT' | 'FORPROFIT'
  prefecture?: string
  city?: string
  description?: string
  visibility: 'PUBLIC' | 'PRIVATE'
  supporterEnabled: boolean
}

export interface UpdateOrganizationRequest {
  name?: string
  nameKana?: string
  nickname1?: string
  nickname2?: string
  prefecture?: string
  city?: string
  description?: string
  visibility?: 'PUBLIC' | 'PRIVATE'
  hierarchyVisibility?: 'NONE' | 'BASIC' | 'FULL'
  supporterEnabled?: boolean
}

export interface OrgTeam {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  template: string
  memberCount: number
}

export interface OrgPermissionGroup {
  id: number
  name: string
  description: string | null
  permissions: string[]
  createdAt: string
}
