import dayjs from 'dayjs'
import type { CheckinStats, MemberCardListItem, MemberCardStatus } from '~/types/member-card'

/** 管理一覧は内部IDのAPIを使い、本人専用QR APIとは分離する。 */
export function useMemberCardManagement(scopeType: 'team' | 'organization', slug: Ref<string>) {
  const api = useMemberCardApi()
  const teamApi = useTeamApi()
  const orgApi = useOrganizationApi()
  const { loadPermissions, isAdminOrDeputy } = useRoleAccess(scopeType, slug)
  const { userTimezone } = useDatetime()
  const { handleApiError } = useErrorHandler()
  const { t } = useI18n()
  const notification = useNotification()
  const cards = ref<MemberCardListItem[]>([])
  const stats = ref<CheckinStats | null>(null)
  const scopeId = ref('')
  const loading = ref(true)
  const loadError = shallowRef<unknown>(null)
  const busy = ref(false)
  let requestId = 0

  async function loadData() {
    const request = ++requestId
    const requestedSlug = slug.value
    const current = () => request === requestId && requestedSlug === slug.value
    loading.value = true
    loadError.value = null
    try {
      const [detail, permissions] = await Promise.all([
        scopeType === 'team' ? teamApi.getTeam(requestedSlug) : orgApi.getOrganization(requestedSlug),
        loadPermissions(),
      ])
      if (!current()) return
      if (!permissions.ok) throw permissions.error
      // detail.id は slug。Long を要求する会員証APIには numericId を渡す。
      const numericId = detail.data.numericId
      if (!numericId) throw new Error('member-card numericId missing')
      const id = String(numericId)
      const statuses: MemberCardStatus[] = ['ACTIVE', 'SUSPENDED', 'REVOKED']
      const list = scopeType === 'team' ? api.listByTeam : api.listByOrg
      const today = dayjs().tz(userTimezone.value)
      const range = { from: today.startOf('month').format('YYYY-MM-DD'), to: today.format('YYYY-MM-DD') }
      // いずれかの取得失敗を空の一覧として扱わない。
      const [groups, summary] = await Promise.all([
        Promise.all(statuses.map(status => list(id, status))),
        scopeType === 'team' && isAdminOrDeputy.value ? api.getTeamCheckinStats(id, range) : Promise.resolve(null),
      ])
      if (!current()) return
      scopeId.value = id
      cards.value = groups.flat()
      stats.value = summary
    } catch (error) {
      if (current()) loadError.value = error
    } finally {
      if (current()) loading.value = false
    }
  }

  async function changeStatus(id: number, action: 'suspend' | 'reactivate') {
    if (busy.value) return
    busy.value = true
    try {
      await api[action](id)
      notification.success(t(`memberCardAdmin.${action}Success`))
      await loadData()
    } catch (error) {
      handleApiError(error, `member-card-${action}`)
    } finally {
      busy.value = false
    }
  }

  return { cards, stats, scopeId, loading, loadError, busy, isAdminOrDeputy, loadData, changeStatus }
}
