export function useNavSettingsApi() {
  const api = useApi()

  async function getNavSettings() {
    const res = await api<{ data: import('~/types/nav').NavSettingsResponse }>('/api/v1/settings/nav')
    return res.data
  }

  async function updateNavSettings(hiddenNavKeys: string[]): Promise<void> {
    await api('/api/v1/settings/nav', {
      method: 'PUT',
      body: { hiddenNavKeys },
    })
  }

  async function listNavFeatures() {
    const res = await api<{ data: import('~/types/nav').NavFeatureAdminItem[] }>('/api/v1/system-admin/nav-features')
    return res.data
  }

  async function createNavFeature(body: import('~/types/nav').NavFeatureCreateRequest) {
    const res = await api<{ data: import('~/types/nav').NavFeatureAdminItem }>('/api/v1/system-admin/nav-features', {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function updateNavFeature(key: string, body: import('~/types/nav').NavFeatureUpdateRequest) {
    const res = await api<{ data: import('~/types/nav').NavFeatureAdminItem }>(`/api/v1/system-admin/nav-features/${key}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  async function deleteNavFeature(key: string): Promise<void> {
    await api(`/api/v1/system-admin/nav-features/${key}`, { method: 'DELETE' })
  }

  return {
    getNavSettings,
    updateNavSettings,
    listNavFeatures,
    createNavFeature,
    updateNavFeature,
    deleteNavFeature,
  }
}
