export function useTeamLoyaltyContext() {
  const route = useRoute()
  const slug = computed(() => String(route.params.slug))
  const teams = useTeamApi()
  const access = useRoleAccess('team', slug)
  const teamId = ref<number | null>(null)
  const teamName = ref('')
  let sequence = 0
  async function load() {
    const seq = ++sequence
    const requestedSlug = slug.value
    teamId.value = null
    teamName.value = ''
    const [response, permission] = await Promise.all([
      teams.getTeam(requestedSlug),
      access.loadPermissions(),
    ])
    if (seq !== sequence || slug.value !== requestedSlug) return false
    if (!permission.ok) throw permission.error
    const numericId = response.data.numericId
    if (!numericId) throw new Error('Team numericId missing')
    teamId.value = numericId
    teamName.value = response.data.basicInfo?.name ?? ''
    return true
  }
  return { slug, teamId, teamName, access, load }
}
