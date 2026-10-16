export function useTeamLoyaltyContext() {
  const route = useRoute()
  const slug = computed(() => String(route.params.slug))
  const teams = useTeamApi()
  const access = useRoleAccess('team', slug)
  const teamId = ref<number | null>(null)
  const teamName = ref('')
  async function load() {
    const [response, permission] = await Promise.all([teams.getTeam(slug.value), access.loadPermissions()])
    if (!permission.ok) throw permission.error
    const numericId = response.data.numericId
    if (!numericId) throw new Error('Team numericId missing')
    teamId.value = numericId
    teamName.value = response.data.basicInfo?.name ?? ''
  }
  return { slug, teamId, teamName, access, load }
}
