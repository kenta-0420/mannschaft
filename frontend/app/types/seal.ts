export type SealVariant = 'LAST_NAME' | 'FULL_NAME' | 'FIRST_NAME'
export type SealScopeType = 'DEFAULT' | 'TEAM' | 'ORGANIZATION'

export interface ElectronicSeal {
  sealId: number
  variant: SealVariant
  displayText: string
  svgData: string
  createdAt: string
  updatedAt: string
}

export interface ScopeDefault {
  scopeType: SealScopeType
  scopeId: number | null
  scopeName: string | null
  variant: SealVariant
}

export interface StampLog {
  stampId: number
  sealId: number
  variant: SealVariant
  targetType: string
  targetId: number
  targetTitle: string | null
  stampedAt: string
  isRevoked: boolean
  revokedAt: string | null
  revokeReason: string | null
}

export interface VerifyResult {
  stampId: number
  userId: number
  sealVariant: SealVariant
  targetType: string
  targetId: number
  stampedAt: string
  isValid: boolean
  isRevoked: boolean
  invalidReason?: 'SEAL_NOT_FOUND' | 'SEAL_REGENERATED'
}
