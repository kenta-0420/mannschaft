export interface DataExportResponse {
  exportId: number
  status: string
  progressPercent: number
  currentStep: string
  fileSizeBytes: number
  expiresAt: string
  createdAt: string
}

/**
 * 柱①「ADMINゼロ根治」— 退会予定ユーザーが唯一のADMINであるスコープ。
 * BE {@code com.mannschaft.app.role.dto.LastAdminScope} と 1:1。
 *
 * <p>Optionality は生成型 {@code components["schemas"]["LastAdminScope"]}
 * （`frontend/app/types/generated/index.ts`）に合わせ、全フィールド optional とする
 * （BE は Jackson デフォルトで null 許容な DTO のため、生成型が正）。</p>
 */
export interface LastAdminScope {
  /** ORGANIZATION / TEAM */
  scopeType?: string
  /** teams.id または organizations.id */
  scopeId?: number
  /** 表示用スコープ名 */
  scopeName?: string
  /** 自分以外のメンバー数（0人なら purge 時に自動 archive の対象） */
  otherMembersCount?: number
}

/**
 * <p>Optionality は生成型 {@code components["schemas"]["DeletionPreviewResponse"]} に合わせ、
 * 全フィールド optional とする（`ApiResponse<T>.data` 自体も生成型では optional）。
 * 呼び出し側（{@code SettingsDeletionPreviewDialog.vue}）はこれを「欠落し得る」ものとして扱い、
 * {@code lastAdminScopes} が欠落した応答は「0件」ではなく「取得失敗」として安全側（削除不可）に倒すこと。</p>
 */
export interface DeletionPreviewResponse {
  retentionDays?: number
  dataSummary?: Record<string, number>
  anonymized?: Array<{ entity: string; field: string }>
  /** 柱①ADMINゼロ根治 AC1: 他メンバーが残る唯一ADMINスコープ一覧。1件でもあれば退会不可（GDPR_011）。 */
  lastAdminScopes?: LastAdminScope[]
  warnings?: string[]
}

export interface ActiveIncident {
  id: number
  title: string
  severity: string
  startedAt: string
  status: string
}

export function useGdprApi() {
  const api = useApi()

  // === GDPR Data Export ===
  async function requestDataExport(body: {
    categories?: string[]
    password?: string
    otp?: string
  }) {
    return api('/api/v1/account/data-export', { method: 'POST', body })
  }

  async function getExportStatus() {
    return api<{ data: DataExportResponse }>('/api/v1/account/data-export/status')
  }

  async function getExportDownloadUrl() {
    return api<{ data: Record<string, string> }>('/api/v1/account/data-export/download')
  }

  // === GDPR Deletion Preview ===
  async function getDeletionPreview() {
    return api<{ data: DeletionPreviewResponse }>('/api/v1/account/deletion-preview')
  }

  // === Active Incidents ===
  async function getActiveIncidents() {
    return api<{ data: { incidents: ActiveIncident[] } }>('/api/v1/active-incidents')
  }

  return {
    requestDataExport,
    getExportStatus,
    getExportDownloadUrl,
    getDeletionPreview,
    getActiveIncidents,
  }
}
