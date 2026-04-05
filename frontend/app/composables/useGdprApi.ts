export interface DataExportResponse {
  exportId: number
  status: string
  progressPercent: number
  currentStep: string
  fileSizeBytes: number
  expiresAt: string
  createdAt: string
}

export interface DeletionPreviewResponse {
  retentionDays: number
  dataSummary: Record<string, number>
  anonymized: Array<{ entity: string; field: string }>
  warnings: string[]
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
