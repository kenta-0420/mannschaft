export function useAppealApi() {
  const api = useApi()

  async function getAppeal(id: number) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/appeals/${id}`)
  }

  async function submitAppeal(id: number, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/appeals/${id}/submit`, {
      method: 'PATCH',
      body,
    })
  }

  return { getAppeal, submitAppeal }
}
