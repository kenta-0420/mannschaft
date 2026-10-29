import { inject, provide, watch, type InjectionKey } from 'vue'
import { getAuthSessionContext } from '~/composables/authSessionContext'
import type { components } from '~/types/generated'

type WidgetSettingResponse = components['schemas']['WidgetSettingResponse']

/** ページと個人パネルで、同じ本人・セッションの取得handleを共有する。 */
const personalSettingsKey: InjectionKey<ReturnType<typeof createPersonalSettings>> = Symbol('personal-dashboard-widget-settings')

function createPersonalSettings() {
  const auth = useAuthStore()
  const api = useApi()
  const session = getAuthSessionContext(auth)
  let owner = auth.user ? session.capture() : undefined
  const settings = useAsyncData(
    `dashboard-widgets:${auth.user?.id ?? 'anonymous'}:personal:0`,
    async (_nuxtApp, options) => {
      owner ??= session.capture()
      if (owner.accountId === null || !session.current(owner)) {
        throw new Error('DASHBOARD_SETTINGS_SESSION_CHANGED')
      }
      const res = await api<{ data: WidgetSettingResponse[] }>('/api/v1/dashboard/widgets', {
        query: { scopeType: 'personal', scopeId: undefined },
        signal: options?.signal,
      })
      // 同じIDでログインし直した場合も、旧セッションの成功を設定へ反映しない。
      if (!session.current(owner)) throw new Error('DASHBOARD_SETTINGS_SESSION_CHANGED')
      return res.data
    },
    { server: false, default: () => [] as WidgetSettingResponse[], immediate: owner !== undefined },
  )
  if (!owner) {
    // 単独mount時の初回認証復元だけ待つ。取得失敗に対する追加retryは行わない。
    watch(() => auth.user?.id ?? null, id => {
      if (id !== null) void settings.execute()
    }, { once: true })
  }
  return { settings, current: () => owner !== undefined && session.current(owner) }
}

/** 本人が確定したページsetupで開始し、個人パネルのmountを待たない。 */
export function providePersonalDashboardWidgetSettings() {
  if (useAuthStore().user) provide(personalSettingsKey, createPersonalSettings())
}

export function usePersonalDashboardWidgetSettings() {
  const shared = inject(personalSettingsKey, null)
  return shared?.current() ? shared.settings : createPersonalSettings().settings
}
