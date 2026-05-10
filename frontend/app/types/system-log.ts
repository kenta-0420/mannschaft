// F10.6 Phase 10-γ-③-b: システムログ型定義

export type SystemLogType = 'slow-query' | 'ssr-error'

export interface SystemLogFileResponse {
  type: SystemLogType
  date: string
  fileName: string
  sizeBytes: number
  downloadUrl: string
}
