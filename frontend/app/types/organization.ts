// === レスポンス ===
// Wave 3-B: OrganizationResponse ネスト構造（BE側変更に対応）
export interface OrgBasicInfoDto {
  name?: string
  nameKana?: string | null
  nickname1?: string | null
  nickname2?: string | null
}

export interface OrgHierarchyDto {
  orgType?: 'GOVERNMENT' | 'MUNICIPALITY' | 'COMPANY' | 'HOSPITAL' | 'ASSOCIATION' | 'SCHOOL' | 'NPO' | 'COMMUNITY' | 'OTHER'
  parentOrganizationId?: number | null
}

export interface OrgLocationDto {
  prefecture?: string | null
  city?: string | null
}

export interface OrgVisibilityDto {
  visibility?: 'PUBLIC' | 'PRIVATE'
  hierarchyVisibility?: 'NONE' | 'BASIC' | 'FULL'
  supporterEnabled?: boolean
}

export interface OrgMetadataDto {
  version?: number
  memberCount?: number
  iconUrl?: string | null
  bannerUrl?: string | null
}

export interface OrgTimestampsDto {
  archivedAt?: string | null
  createdAt?: string
}

export interface OrganizationResponse {
  /** BIGINT 内部 ID（数値を string で表現）。 */
  id: string
  /** カスタムスラッグ。URLに使用する string 型。BE slug 移行対応 */
  slug: string
  basicInfo?: OrgBasicInfoDto
  hierarchy?: OrgHierarchyDto
  location?: OrgLocationDto
  visibility?: OrgVisibilityDto
  metadata?: OrgMetadataDto
  timestamps?: OrgTimestampsDto
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
  /** カスタムスラッグ（英小文字・数字・ハイフン、3〜30文字）。省略時は名前から自動生成される */
  slug?: string
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

// === F01.2 組織階層（祖先・子組織） ===

/**
 * 祖先組織1件分。`hidden: true` の場合は `id` 以外のフィールドは省略される。
 * （非公開祖先のプレースホルダ。チェーンの抜けを示す）
 */
export interface AncestorOrganization {
  id: number
  /** 祖先組織スラッグ（URL に使用。hidden=false のとき返る） */
  slug?: string | null
  name?: string | null
  nickname1?: string | null
  description?: string | null
  iconUrl?: string | null
  visibility?: 'PUBLIC' | 'PRIVATE' | null
  hidden: boolean
}

export interface AncestorsResponse {
  data: AncestorOrganization[]
  meta: { depth: number; truncated: boolean }
}

export interface ChildOrganization {
  id: number
  /** 子組織スラッグ（URL に使用） */
  slug?: string | null
  name: string
  nickname1?: string | null
  iconUrl?: string | null
  visibility: 'PUBLIC' | 'PRIVATE'
  memberCount: number
  archived: boolean
}

export interface ChildrenResponse {
  data: ChildOrganization[]
  meta: { nextCursor: string | null; size: number; hasNext: boolean }
}
