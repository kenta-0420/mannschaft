/** SYSTEM_ADMIN 兼任時だけ、実スコープ権限を検証するカタログ応答を利用する。 */
export function useLoyaltyStaffAccess(
  scopeId: Ref<string>,
  roleName: Ref<string | null>,
  isScopeAdmin: Ref<boolean>,
  can: (permission: string) => boolean,
) {
  const teams = useTeamApi()
  const api = useTeamLoyaltyApi()
  const systemAllowed = ref(false)
  const error = shallowRef<unknown>(null)
  let sequence = 0
  const allowed = computed(
    () =>
      isScopeAdmin.value ||
      (roleName.value === 'DEPUTY_ADMIN' && can('TEAM_LOYALTY_OPERATE')) ||
      (roleName.value === 'SYSTEM_ADMIN' && systemAllowed.value),
  )
  async function refresh() {
    const seq = ++sequence
    systemAllowed.value = false
    error.value = null
    if (roleName.value !== 'SYSTEM_ADMIN' || isScopeAdmin.value) return
    try {
      const team = await teams.getTeam(scopeId.value)
      if (!team.data.numericId) throw new Error('Team numericId missing')
      await api.catalog(team.data.numericId)
      if (seq === sequence) systemAllowed.value = true
    } catch (e) {
      if (seq !== sequence) return
      const status =
        (e as { statusCode?: number; status?: number }).statusCode ??
        (e as { status?: number }).status
      if (status !== 403 && status !== 404) error.value = e
    }
  }
  watch([scopeId, roleName, isScopeAdmin], refresh, { immediate: true })
  onUnmounted(() => {
    sequence++
  })
  return { allowed, error, refresh }
}
