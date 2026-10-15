export type SkillStatus = 'ACTIVE' | 'EXPIRED' | 'PENDING_REVIEW'

export interface SkillCategoryResponse {
  id: number
  name: string
  description: string | null
  icon: string | null
  sortOrder: number
  isActive: boolean
  createdAt: string
}

export interface MemberSkillResponse {
  id: number
  skillCategoryId: number
  categoryName: string
  userId: number
  scopeType: string
  scopeId: number
  name: string
  issuer: string | null
  credentialNumber: string | null
  acquiredOn: string | null
  expiresAt: string | null
  status: SkillStatus
  hasCertificate: boolean
  verifiedAt: string | null
  verifiedBy: number | null
  version: number
  createdAt: string
}

/** 検索API（GET /skills/search）の1件。MemberSkillResponse とは項目名が異なる。 */
export interface SkillSearchItem {
  memberSkillId: number
  userId: number
  displayName: string | null
  categoryId: number
  skillName: string
  issuer: string | null
  acquiredOn: string | null
  expiresAt: string | null
  status: SkillStatus
  /** 証明書が登録済みか（資格番号は検索結果に含まれない。BE 側で意図的に除外） */
  hasCertificate: boolean
  verifiedAt: string | null
  expiringSoon: boolean
}

export interface RegisterSkillRequest {
  skillCategoryId: number
  name?: string
  issuer?: string
  credentialNumber?: string
  acquiredOn?: string
  expiresAt?: string
  certificateS3Key?: string
}

export interface UpdateSkillRequest {
  name?: string
  issuer?: string
  credentialNumber?: string
  acquiredOn?: string
  expiresAt?: string
  certificateS3Key?: string
  version?: number
}

export interface CreateSkillCategoryRequest {
  name: string
  description?: string
  icon?: string
  sortOrder?: number
}

export interface UpdateSkillCategoryRequest {
  name?: string
  description?: string
  icon?: string
  sortOrder?: number
  isActive?: boolean
  version?: number
}

/** マトリクスのセル（メンバー × カテゴリ）。未登録でも cells にキーがあり status=null のことがある。 */
export interface SkillMatrixCell {
  memberSkillId: number | null
  status: SkillStatus | null
  expiresAt: string | null
}

export interface SkillMatrixRow {
  userId: number
  displayName: string
  /** キーは skillCategoryId。キーが無い場合も「未登録」として扱う */
  cells: Record<number, SkillMatrixCell | undefined>
}

/** GET /skill-matrix のピボット形式。 */
export interface SkillMatrixResponse {
  categories: SkillCategoryResponse[]
  rows: SkillMatrixRow[]
}
