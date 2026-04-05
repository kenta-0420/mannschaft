export function useExportApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}`
      : `/api/v1/organizations/${scopeId}`
  }

  async function startExport(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: { targets: string[]; format: 'CSV' | 'JSON' },
  ) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: { jobId: string } }>(`${base}/export`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function getStatus(jobId: string) {
    const res = await api<{ data: { jobId: string; status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'; progress: number; downloadUrl: string | null } }>(
      `/api/v1/export/status/${jobId}`,
    )
    return res.data
  }

  async function download(jobId: string) {
    const res = await api<{ data: { url: string } }>(`/api/v1/export/download/${jobId}`)
    return res.data
  }

  return { startExport, getStatus, download }
}
