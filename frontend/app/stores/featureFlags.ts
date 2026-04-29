import { defineStore } from 'pinia'

export const useFeatureFlagStore = defineStore('featureFlags', () => {
  const flags = ref<Record<string, boolean>>({})
  const loaded = ref(false)

  async function loadFlags() {
    const authStore = useAuthStore()
    if (!authStore.isSystemAdmin) return

    try {
      const { getFeatureFlags } = useSystemAdminApi()
      const res = await getFeatureFlags()
      flags.value = Object.fromEntries(res.data.map((f) => [f.flagKey, f.isEnabled]))
      loaded.value = true
    } catch {
      // サイレント失敗 — 一般ユーザーやネットワークエラー時はデフォルト(false)を維持
    }
  }

  function isEnabled(flagKey: string): boolean {
    return flags.value[flagKey] ?? false
  }

  return { flags, loaded, loadFlags, isEnabled }
})
