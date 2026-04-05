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

export interface SkillMatrixResponse {
  [key: string]: unknown
}
