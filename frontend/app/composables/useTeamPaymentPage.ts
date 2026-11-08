/** チーム請求3画面の権限確認・取得・再試行を同じ経路で実行する。 */
export function useTeamPaymentPage<T>(
  teamSlug: Ref<string>,
  fetchData: (teamId: number) => Promise<T>,
  emptyData: () => T,
  requestId?: Ref<string>,
) {
  const access = useRoleAccess('team', teamSlug, { watchScope: false })
  const { resolveScopeId } = useActivityScopeId()
  const { handleApiError } = useErrorHandler()
  const teamId = ref<number | null>(null)
  const data = shallowRef<T>(emptyData())
  const state = ref<'loading' | 'loaded' | 'denied' | 'error'>('loading')
  const loadError = shallowRef<unknown>(null)
  let requestSeq = 0

  function fail(error: unknown): void {
    data.value = emptyData()
    loadError.value = error
    state.value = 'error'
    handleApiError(error, 'payment-request.team.load')
  }

  async function load(): Promise<void> {
    const seq = ++requestSeq
    const slug = teamSlug.value
    const id = requestId?.value
    const isCurrent = () => seq === requestSeq && slug === teamSlug.value && id === requestId?.value
    // watchの同期実行時点で、前のチームの金融情報を描画対象から外す。
    data.value = emptyData()
    teamId.value = null
    loadError.value = null
    state.value = 'loading'
    try {
      const result = await access.loadPermissions()
      if (!isCurrent()) return
      if (!result.ok) throw result.error
      if (!access.isAdminOrDeputy.value) {
        state.value = 'denied'
        return
      }
      const resolvedId = await resolveScopeId('TEAM', slug)
      if (!isCurrent()) return
      if (resolvedId === null) throw new Error('payment request team scope could not be resolved')
      const response = await fetchData(resolvedId)
      if (!isCurrent()) return
      teamId.value = resolvedId
      data.value = response
      state.value = 'loaded'
    } catch (error: unknown) {
      if (isCurrent()) fail(error)
    }
  }

  watch(() => [teamSlug.value, requestId?.value], load, { flush: 'sync' })
  onMounted(load)
  onUnmounted(() => { requestSeq += 1 })

  return {
    data, teamId, state, loadError, load, fail,
    loading: computed(() => state.value === 'loading'),
    isAdminOrDeputy: computed(() => state.value === 'loaded'),
  }
}
