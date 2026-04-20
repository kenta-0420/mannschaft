// === レスポンス ===
export interface OrganizationResponse {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  orgType: 'GOVERNMENT' | 'MUNICIPALITY' | 'COMPANY' | 'HOSPITAL' | 'ASSOCIATION' | 'SCHOOL' | 'NPO' | 'COMMUNITY' | 'OTHER'
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
  iconUrl: string | null
  bannerUrl: string | null
}

export interface OrganizationSummaryResponse {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  orgType: 'GOVERNMENT' | 'MUNICIPALITY' | 'COMPANY' | 'HOSPITAL' | 'ASSOCIATION' | 'SCHOOL' | 'NPO' | 'COMMUNITY' | 'OTHER'
  memberCount: number
  supporterEnabled: boolean
}

// === リクエスト ===
export interface CreateOrganizationRequest {
  name: string
  nameKana?: string
  nickname1?: string
  nickname2?: string
  orgType: 'GOVERNMENT' | 'MUNICIPALITY' | 'COMPANY' | 'HOSPITAL' | 'ASSOCIATION' | 'SCHOOL' | 'NPO' | 'COMMUNITY' | 'OTHER'
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

// === F01.2 拡張プロフィール ===

export type EstablishedDatePrecision = 'YEAR' | 'YEAR_MONTH' | 'FULL'

export interface ProfileVisibility {
  homepage_url?: boolean
  established_date?: boolean
  philosophy?: boolean
  officers?: boolean
  custom_fields?: boolean
}

export interface OrganizationProfileResponse {
  id: number
  homepage_url: string | null
  established_date: string | null
  established_date_precision: EstablishedDatePrecision | null
  philosophy: string | null
  profile_visibility: ProfileVisibility | null
}

export interface UpdateOrgProfileRequest {
  homepage_url?: string | null
  established_date?: string | null
  established_date_precision?: EstablishedDatePrecision | null
  philosophy?: string | null
  profile_visibility?: ProfileVisibility | null
}

export interface OfficerResponse {
  id: number
  organization_id: number
  name: string
  title: string
  display_order: number
  is_visible: boolean
  is_publicly_visible: boolean | null
}

export interface CreateOfficerRequest {
  name: string
  title: string
  is_visible?: boolean
}

export interface UpdateOfficerRequest {
  name?: string
  title?: string
  is_visible?: boolean
}

export interface CustomFieldResponse {
  id: number
  organization_id: number
  label: string
  value: string
  display_order: number
  is_visible: boolean
  is_publicly_visible: boolean | null
}

export interface CreateCustomFieldRequest {
  label: string
  value: string
  is_visible?: boolean
}

export interface UpdateCustomFieldRequest {
  label?: string
  value?: string
  is_visible?: boolean
}

export interface ReorderItem {
  id: number
  displayOrder: number
}

export interface ReorderRequest {
  orders: ReorderItem[]
}
