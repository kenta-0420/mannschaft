export interface SyncConflict {
  id: number
  userId: number
  entityType: string
  entityId: number
  localData: Record<string, unknown>
  serverData: Record<string, unknown>
  resolvedAt: string | null
  resolvedBy: 'LOCAL' | 'SERVER' | 'MANUAL' | null
  createdAt: string
}

export interface TranslationConfig {
  defaultLocale: string
  supportedLocales: string[]
  autoTranslateEnabled: boolean
}

export interface TranslationResponse {
  id: number
  entityType: string
  entityId: number
  locale: string
  fieldTranslations: Record<string, string>
  status: 'DRAFT' | 'PUBLISHED' | 'NEEDS_UPDATE'
  translatedBy: { id: number; displayName: string } | null
  createdAt: string
  updatedAt: string
}
