import type { components } from '~/types/generated'

type PersonalSyncStatusResponse = components['schemas']['PersonalSyncStatusResponse']

interface GcalStatus {
  isConnected: boolean
  email: string | null
  lastSyncedAt: string | null
}

interface GcalSync {
  personalSync: boolean
  teamSyncIds: string[]
  orgSyncIds: string[]
}

export function useAccountGcal() {
  const notification = useNotification()
  const gcalApi = useGoogleCalendarApi()

  const gcalStatus = ref<GcalStatus | null>(null)
  const gcalSyncSettings = ref<GcalSync | null>(null)
  /** BE の PersonalSyncStatusResponse をそのまま保持する（接続状態・同期有効フラグ等）。
   *  GcalSync（チーム・org 同期 ID リスト）と別物なので別 ref で管理。 */
  const personalSyncStatus = ref<PersonalSyncStatusResponse | null>(null)
  const gcalSyncing = ref(false)

  async function loadGcal() {
    try {
      const [statusRes, personalSyncRes] = await Promise.all([
        gcalApi.getConnectionStatus(),
        gcalApi.getPersonalSync(),
      ])
      gcalStatus.value = statusRes.data as GcalStatus
      personalSyncStatus.value = personalSyncRes.data ?? null
      // gcalSyncSettings は UI 側で teamSyncIds/orgSyncIds を扱うための別 ref
      // 既存 UI との互換のため初期値は personalSyncEnabled を personalSync にマップ
      if (gcalSyncSettings.value === null) {
        gcalSyncSettings.value = {
          personalSync: personalSyncRes.data?.personalSyncEnabled ?? false,
          teamSyncIds: [],
          orgSyncIds: [],
        }
      }
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

  function toggleTeamSync(teamId: string) {
    if (!gcalSyncSettings.value) return
    const idx = gcalSyncSettings.value.teamSyncIds.indexOf(teamId)
    if (idx >= 0) gcalSyncSettings.value.teamSyncIds.splice(idx, 1)
    else gcalSyncSettings.value.teamSyncIds.push(teamId)
  }

  function toggleOrgSync(orgId: string) {
    if (!gcalSyncSettings.value) return
    const idx = gcalSyncSettings.value.orgSyncIds.indexOf(orgId)
    if (idx >= 0) gcalSyncSettings.value.orgSyncIds.splice(idx, 1)
    else gcalSyncSettings.value.orgSyncIds.push(orgId)
  }

  return {
    gcalStatus,
    gcalSyncSettings,
    personalSyncStatus,
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
