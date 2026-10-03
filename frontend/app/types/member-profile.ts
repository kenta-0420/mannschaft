// F06.2 メンバー紹介: バックエンド実装（TeamPageController / MemberProfileController /
// MemberProfileFieldController / TeamPageSectionController）に合わせた型定義。
// 設計書 docs/features/F06.2_member_gallery.md とはフィールド名が一部食い違うため注意
// （例: photo_r2_key ではなく photoS3Key、custom_fields ではなく customFieldValues の
// JSON 文字列。詳細は PR 本文を参照）。

export type PageType = 'MAIN' | 'YEARLY'
export type PageStatus = 'DRAFT' | 'PUBLISHED'
export type PageVisibility = 'PUBLIC' | 'MEMBERS_ONLY'
export type SectionType = 'TEXT' | 'IMAGE' | 'MEMBER_LIST' | 'HEADING'
// バックエンドの FieldType enum は TEXT/NUMBER/DATE/SELECT の4種のみ
export type FieldType = 'TEXT' | 'NUMBER' | 'DATE' | 'SELECT'

export interface TeamPageSection {
  id: number
  sectionType: SectionType
  title: string | null
  content: string | null
  imageS3Key: string | null
  imageCaption: string | null
  sortOrder: number
}

export interface MemberProfile {
  id: number
  teamPageId: number
  userId: number | null
  displayName: string
  memberNumber: string | null
  photoS3Key: string | null
  bio: string | null
  position: string | null
  /** JSON文字列（{"fieldId": "value", ...}）。表示時は JSON.parse して使う */
  customFieldValues: string | null
  sortOrder: number
  isVisible: boolean
  createdAt: string
  updatedAt: string
}

export interface TeamPage {
  id: number
  teamId: number | null
  organizationId: number | null
  title: string
  slug: string
  pageType: PageType
  year: number | null
  description: string | null
  coverImageS3Key: string | null
  visibility: PageVisibility
  status: PageStatus
  allowSelfEdit: boolean
  sortOrder: number
  createdBy: number | null
  createdAt: string
  updatedAt: string
  sections: TeamPageSection[] | null
  members: MemberProfile[] | null
}

export interface MemberProfileField {
  id: number
  teamId: number | null
  organizationId: number | null
  fieldName: string
  fieldType: FieldType
  /** JSON文字列（SELECT型の選択肢配列） */
  options: string | null
  isRequired: boolean
  sortOrder: number
  isActive: boolean
  createdAt: string
  updatedAt: string
}

export interface MemberLookupResult {
  memberProfileId: number
  userId: number | null
  displayName: string
  memberNumber: string | null
  position: string | null
  photoS3Key: string | null
}

// --- リクエストDTO ---

export interface CreateTeamPageRequest {
  teamId?: number
  organizationId?: number
  title: string
  slug: string
  pageType: PageType
  year?: number
  description?: string
  coverImageS3Key?: string
  visibility?: PageVisibility
}

export interface UpdateTeamPageRequest {
  title: string
  slug: string
  description?: string
  coverImageS3Key?: string
  visibility?: PageVisibility
  allowSelfEdit?: boolean
  sortOrder?: number
}

export interface CreateMemberProfileRequest {
  teamPageId: number
  userId?: number
  displayName: string
  memberNumber?: string
  photoS3Key?: string
  bio?: string
  position?: string
  customFieldValues?: string
}

export interface UpdateMemberProfileRequest {
  displayName: string
  memberNumber?: string
  photoS3Key?: string
  bio?: string
  position?: string
  customFieldValues?: string
  sortOrder?: number
  isVisible?: boolean
}

export interface BulkCreateMemberItem {
  userId?: number
  displayName: string
  memberNumber?: string
  photoS3Key?: string
  bio?: string
  position?: string
  customFields?: string
}

export interface BulkCreateMemberRequest {
  teamPageId: number
  members: BulkCreateMemberItem[]
}

export interface BulkCreateMemberResponse {
  createdCount: number
  skippedCount: number
  skippedUserIds: number[]
}

export interface CopyMembersResponse {
  copiedCount: number
  skippedCount: number
  skippedUserIds: number[]
}

export interface ReorderOrderItem {
  id: number
  sortOrder: number
}

export interface ReorderRequest {
  teamPageId: number
  orders: ReorderOrderItem[]
}

export interface CreateFieldRequest {
  teamId?: number
  organizationId?: number
  fieldName: string
  fieldType: FieldType
  options?: string
  isRequired?: boolean
  sortOrder?: number
}

export interface UpdateFieldRequest {
  fieldName: string
  fieldType?: FieldType
  options?: string
  isRequired?: boolean
  sortOrder?: number
}

export interface PreviewTokenResult {
  id: number
  previewToken: string
  previewUrl: string
  expiresAt: string
}

export interface PageMeta {
  total: number
  page: number
  size: number
  totalPages: number
}
