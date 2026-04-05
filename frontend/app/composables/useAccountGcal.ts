interface GcalStatus {
  isConnected: boolean
  email: string | null
  lastSyncedAt: string | null
}

interface GcalSync {
  personalSync: boolean
  teamSyncIds: number[]
  orgSyncIds: number[]
}

export function useAccountGcal() {
  const notification = useNotification()
  const gcalApi = useGoogleCalendarApi()

  const gcalStatus = ref<GcalStatus | null>(null)
  const gcalSyncSettings = ref<GcalSync | null>(null)
  const gcalSyncing = ref(false)

  async function loadGcal() {
    try {
      const [statusRes, settingsRes] = await Promise.all([
        gcalApi.getConnectionStatus(),
        gcalApi.getPersonalSync(),
      ])
      gcalStatus.value = statusRes.data as GcalStatus
      gcalSyncSettings.value = settingsRes as unknown as GcalSync
    } catch {
      /* silent */
    }
  }

  async function connectGoogle() {
    try {
      const res = await gcalApi.connect()
      window.location.href = (res.data as { authUrl: string }).authUrl
    } catch {
      notification.error('接続に失敗しました')
    }
  }

  async function disconnectGoogle() {
    if (!confirm('Google Calendar連携を解除しますか？')) return
    try {
      await gcalApi.disconnect()
      notification.success('連携を解除しました')
      await loadGcal()
    } catch {
      notification.error('解除に失敗しました')
    }
  }

  async function saveGcalSettings() {
    if (!gcalSyncSettings.value) return
    try {
      await gcalApi.updatePersonalSync(gcalSyncSettings.value as unknown as Record<string, unknown>)
      notification.success('同期設定を保存しました')
    } catch {
      notification.error('保存に失敗しました')
    }
  }

  async function manualGcalSync() {
    gcalSyncing.value = true
    try {
      await gcalApi.manualSync()
      notification.success('同期を実行しました')
      await loadGcal()
    } catch {
      notification.error('同期に失敗しました')
    } finally {
      gcalSyncing.value = false
    }
  }

  function toggleTeamSync(teamId: number) {
    if (!gcalSyncSettings.value) return
    const idx = gcalSyncSettings.value.teamSyncIds.indexOf(teamId)
    if (idx >= 0) gcalSyncSettings.value.teamSyncIds.splice(idx, 1)
    else gcalSyncSettings.value.teamSyncIds.push(teamId)
  }

  function toggleOrgSync(orgId: number) {
    if (!gcalSyncSettings.value) return
    const idx = gcalSyncSettings.value.orgSyncIds.indexOf(orgId)
    if (idx >= 0) gcalSyncSettings.value.orgSyncIds.splice(idx, 1)
    else gcalSyncSettings.value.orgSyncIds.push(orgId)
  }

  return {
    gcalStatus,
    gcalSyncSettings,
    gcalSyncing,
    loadGcal,
    connectGoogle,
    disconnectGoogle,
    saveGcalSettings,
    manualGcalSync,
    toggleTeamSync,
    toggleOrgSync,
  }
}
