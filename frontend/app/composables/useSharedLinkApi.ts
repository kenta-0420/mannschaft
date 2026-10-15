export function useSharedLinkApi() {
  const api = useApi()

  async function accessSharedLink(token: string, password?: string) {
    return api<{ data: unknown }>(`/api/v1/shared-links/${token}/access`, {
      method: 'POST',
      body: password ? { password } : {},
    })
  }

  return {
    accessSharedLink,
  }
}
