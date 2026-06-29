/**
 * F13 ストレージ容量使用量 — ユーザー向け API 型
 * GET /api/v1/me/storage/usage
 */
export interface StorageScopeUsage {
  /** スコープ種別 */
  scopeType: 'PERSONAL' | 'TEAM' | 'ORGANIZATION'
  /** スコープID */
  scopeId: number
  /** スコープ名（PERSONAL は BE ハードコード "個人"、TEAM/ORG は実名） */
  scopeName: string
  /** スラッグ（PERSONAL は null） */
  slug: string | null
  /** 使用バイト数 */
  usedBytes: number
  /** ファイル数 */
  fileCount: number
  /** 無料枠バイト数 */
  includedBytes: number
  /** ハード上限バイト数（null = 無制限） */
  maxBytes: number | null
  /**
   * 使用率（used / included * 100）
   * included = 0 のとき 0。100 超もありうる（無制限プランでの超過表示用）
   */
  usagePercent: number
}

export type StorageUsageResponse = StorageScopeUsage[]
