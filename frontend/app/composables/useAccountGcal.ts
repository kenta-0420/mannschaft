interface GcalStatus {
  connected: boolean
  googleAccountEmail: string | null
}

/**
 * アカウント設定ページ（account.vue）向けの Google Calendar 連携状態取得。
 *
 * チーム/組織別の同期ON/OFFはすべて詳細設定ページ（/settings/calendar-sync）に一本化したため、
 * ここでは「接続状態の簡易表示」に必要な最小限のデータのみ扱う。
 * 実際のトグル操作・接続/解除・手動同期は SettingsGcalSection から
 * /settings/calendar-sync への導線のみを提供し、詳細設定ページ側で行う。
 */
export function useAccountGcal() {
  const gcalApi = useGoogleCalendarApi()

  const gcalStatus = ref<GcalStatus | null>(null)

  async function loadGcal() {
    try {
      const res = await gcalApi.getConnectionStatus()
      gcalStatus.value = res.data
    } catch {
      gcalStatus.value = null
    }
  }

  return {
    gcalStatus,
    loadGcal,
  }
}
