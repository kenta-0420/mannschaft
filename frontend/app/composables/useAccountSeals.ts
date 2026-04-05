import type { ElectronicSeal, ScopeDefault } from '~/types/seal'

export function useAccountSeals() {
  const notification = useNotification()
  const sealApi = useSealApi()

  const seals = ref<ElectronicSeal[]>([])
  const scopeDefaults = ref<ScopeDefault[]>([])
  const regeneratingSeals = ref(false)
  const sealActiveTab = ref('0')

  async function loadSeals(userId: number | undefined) {
    if (!userId) return
    try {
      const [sealsRes, defaultsRes] = await Promise.all([
        sealApi.getSeals(userId),
        sealApi.getScopeDefaults(userId),
      ])
      seals.value = sealsRes
      scopeDefaults.value = defaultsRes
    } catch {
      /* silent */
    }
  }

  async function handleRegenerateSeals(userId: number | undefined) {
    if (!userId) return
    regeneratingSeals.value = true
    try {
      seals.value = await sealApi.regenerateSeals(userId)
      notification.success('印鑑を再生成しました')
    } catch {
      notification.error('再生成に失敗しました（1時間に3回まで）')
    } finally {
      regeneratingSeals.value = false
    }
  }

  async function handleSaveDefaults(userId: number | undefined, defaults: ScopeDefault[]) {
    if (!userId) return
    try {
      scopeDefaults.value = await sealApi.updateScopeDefaults(
        userId,
        defaults.map((d) => ({ scopeType: d.scopeType, scopeId: d.scopeId, variant: d.variant })),
      )
      notification.success('デフォルト設定を保存しました')
    } catch {
      notification.error('設定の保存に失敗しました')
    }
  }

  return {
    seals,
    scopeDefaults,
    regeneratingSeals,
    sealActiveTab,
    loadSeals,
    handleRegenerateSeals,
    handleSaveDefaults,
  }
}
