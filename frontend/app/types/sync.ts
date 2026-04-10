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

/** コンフリクト一覧レスポンス（GET /api/v1/sync/conflicts/me の各要素） */
export interface SyncConflictListItem {
  id: number
  resourceType: string
  resourceId: number
  clientVersion: number | null
  serverVersion: number | null
  resolution: string | null
  resolvedAt: string | null
  createdAt: string
}

/** コンフリクト詳細レスポンス（GET /api/v1/sync/conflicts/{id}） */
export interface SyncConflictDetail {
  id: number
  userId: number
  resourceType: string
  resourceId: number
  clientData: Record<string, unknown>
  serverData: Record<string, unknown>
  clientVersion: number | null
  serverVersion: number | null
  resolution: string | null
  resolvedAt: string | null
  createdAt: string
  updatedAt: string
}

/** コンフリクト解決リクエスト */
export interface ResolveConflictPayload {
  resolution: 'CLIENT_WIN' | 'SERVER_WIN' | 'MANUAL_MERGE'
  mergedData?: Record<string, unknown>
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
