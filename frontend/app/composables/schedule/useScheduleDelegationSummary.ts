interface DelegationSummaryContext {
  scheduleId: number
  scopeType: 'team' | 'organization'
  scopeId: string
  canEdit: boolean
  skip: boolean
}

/** 予定詳細の補助件数。権限確認と件数取得を同じ世代で扱い、前の予定の結果を残さない。 */
export function useScheduleDelegationSummary(context: () => DelegationSummaryContext) {
  const teamId = computed(() => context().scopeType === 'team' ? context().scopeId : '')
  const organizationId = computed(() => context().scopeType === 'organization' ? context().scopeId : '')
  // 取得は下のwatchへ集約する。useRoleAccess自身のscope watchと二重発行しない。
  const access = {
    team: useRoleAccess('team', teamId, { watchScope: false }),
    organization: useRoleAccess('organization', organizationId, { watchScope: false }),
  }
  const { fetchDelegations } = useScheduleDelegationApi()
  const count = ref<number | null>(null)
  const error = shallowRef<unknown>()
  const loading = ref(false)
  let generation = 0

  async function reload(): Promise<void> {
    const request = ++generation
    const requested = { ...context() }
    count.value = null
    error.value = undefined
    loading.value = false
    if (!requested.canEdit || requested.skip || !requested.scopeId) return
    const current = () => {
      const now = context()
      return request === generation && requested.scheduleId === now.scheduleId
        && requested.scopeType === now.scopeType && requested.scopeId === now.scopeId
        && requested.canEdit === now.canEdit && requested.skip === now.skip
    }
    loading.value = true
    try {
      const role = access[requested.scopeType]
      const permissionResult = await role.loadPermissions()
      if (!current()) return
      if (!permissionResult.ok) throw permissionResult.error
      if (!role.isAdminOrDeputy.value) return
      const response = await fetchDelegations(requested.scheduleId, 0, 1)
      if (current()) count.value = response.data.total
    } catch (cause) {
      if (current()) error.value = cause
    } finally {
      if (current()) loading.value = false
    }
  }

  // 同じ描画でscopeTypeとscopeIdが変わる場合も、完成したpropsの組で一度だけ取得する。
  // pre flushは本体の描画前に件数を消す。await後はsnapshot照合でも旧応答を拒否する。
  watch([
    () => context().scheduleId,
    () => context().scopeType,
    () => context().scopeId,
    () => context().canEdit,
    () => context().skip,
  ], () => { void reload() }, { immediate: true, flush: 'pre' })
  onScopeDispose(() => { generation++ })
  return { count, error, loading, reload }
}
