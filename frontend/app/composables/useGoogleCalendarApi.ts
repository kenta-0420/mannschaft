import type { components } from '~/types/generated'

type PersonalSyncStatusResponse = components['schemas']['PersonalSyncStatusResponse']

export function useGoogleCalendarApi() {
  const api = useApi()

  // Google カレンダー連携状態（コンポーネント側で fetchPersonalSyncStatus() を呼んでから参照する）
  const personalSyncStatus = ref<PersonalSyncStatusResponse | null>(null)

  // active === true かつ personalSyncEnabled === true のとき true
  const googleSyncEnabled = computed(
    () =>
      personalSyncStatus.value?.active === true &&
      personalSyncStatus.value?.personalSyncEnabled === true,
  )

  async function fetchPersonalSyncStatus(): Promise<void> {
    try {
      const res = await api<{ data: PersonalSyncStatusResponse }>(
        '/api/v1/me/google-calendar/personal-sync',
      )
      personalSyncStatus.value = (res as { data: PersonalSyncStatusResponse }).data ?? null
    } catch {
      personalSyncStatus.value = null
    }
  }

  async function getConnectionStatus() {
    return api<{
      data: {
        isConnected: boolean
        googleAccountEmail: string | null
        googleCalendarId: string | null
        isActive: boolean
        personalSyncEnabled: boolean
        lastSyncError: { type: string; message: string; occurredAt: string } | null
      }
    }>('/api/v1/me/google-calendar/status')
  }

  async function connect(body?: Record<string, unknown>) {
    return api<{ data: { authUrl: string } }>('/api/v1/me/google-calendar/connect', {
      method: 'POST',
      body,
    })
  }

  async function disconnect() {
    return api('/api/v1/me/google-calendar/disconnect', { method: 'DELETE' })
  }

  async function getPersonalSync() {
    return api<{ data: PersonalSyncStatusResponse }>('/api/v1/me/google-calendar/personal-sync')
  }

  async function updatePersonalSync(body: Record<string, unknown>) {
    return api('/api/v1/me/google-calendar/personal-sync', { method: 'PUT', body })
  }

  async function manualSync() {
    return api('/api/v1/me/google-calendar/sync', { method: 'POST' })
  }

  // === Team / Org Sync ===
  async function toggleTeamSync(teamId: string, body: Record<string, unknown>) {
    return api(`/api/v1/me/teams/${teamId}/calendar-sync`, { method: 'PUT', body })
  }

  async function toggleOrgSync(orgId: string, body: Record<string, unknown>) {
    return api(`/api/v1/me/organizations/${orgId}/calendar-sync`, { method: 'PUT', body })
  }

  async function getSyncSettings() {
    return api<{ data: unknown }>('/api/v1/me/calendar-sync-settings')
  }

  // === iCal ===
  async function getIcalToken() {
    return api<{ data: { token: string } }>('/api/v1/me/ical/token')
  }

  async function regenerateIcalToken() {
    return api<{ data: { token: string } }>('/api/v1/me/ical/token/regenerate', { method: 'POST' })
  }

  async function deleteIcalToken() {
    return api('/api/v1/me/ical/token', { method: 'DELETE' })
  }

  async function getIcalFeedUrl(token: string) {
    return `/ical/${token}.ics`
  }

  return {
    personalSyncStatus,
    googleSyncEnabled,
    fetchPersonalSyncStatus,
    getConnectionStatus,
    connect,
    disconnect,
    getPersonalSync,
    updatePersonalSync,
    manualSync,
    toggleTeamSync,
    toggleOrgSync,
    getSyncSettings,
    getIcalToken,
    regenerateIcalToken,
    deleteIcalToken,
    getIcalFeedUrl,
  }
}
