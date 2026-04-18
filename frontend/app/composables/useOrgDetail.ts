import type { OrgTeam, OrgPermissionGroup } from '~/types/organization'

export interface OrgDetail {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  template: string
  prefecture: string | null
  city: string | null
  description: string | null
  visibility: string
  supporterEnabled: boolean
  version: number
  memberCount: number
  supporterCount?: number
  archivedAt: string | null
  createdAt: string
  iconUrl: string | null
  bannerUrl: string | null
}

export function useOrgDetail(orgId: Ref<number>) {
  const orgApi = useOrganizationApi()
  const notification = useNotification()
  const { handleApiError } = useErrorHandler()

  const org = ref<OrgDetail | null>(null)
  const orgTeams = ref<OrgTeam[]>([])
  const permissionGroups = ref<OrgPermissionGroup[]>([])
  const loading = ref(false)

  const followStatus = ref<'NONE' | 'PENDING' | 'APPROVED'>('NONE')
  const followLoading = ref(false)
  const showCancelSupporterConfirm = ref(false)
  const showLeaveConfirm = ref(false)

  async function fetchOrg() {
    loading.value = true
    try {
      const result = await orgApi.getOrganization(orgId.value)
      org.value = result.data as OrgDetail
    } catch (error) {
      handleApiError(error, '組織詳細取得')
    } finally {
      loading.value = false
    }
  }

  async function fetchOrgTeams() {
    try {
      const result = await orgApi.getTeamsInOrg(orgId.value)
      orgTeams.value = result.data
    } catch {
      orgTeams.value = []
    }
  }

  async function fetchPermissionGroups() {
    try {
      const result = await orgApi.getPermissionGroups(orgId.value)
      permissionGroups.value = result.data
    } catch {
      permissionGroups.value = []
    }
  }

  async function fetchFollowStatus(roleName: Ref<string | null>) {
    if (roleName.value) return
    try {
      const res = await orgApi.getFollowStatus(orgId.value)
      followStatus.value = res.data.status
    } catch {
      followStatus.value = 'NONE'
    }
  }

  async function applySupporter() {
    followLoading.value = true
    try {
      await orgApi.followOrganization(orgId.value)
      const res = await orgApi.getFollowStatus(orgId.value)
      followStatus.value = res.data.status
      notification.success(
        followStatus.value === 'APPROVED'
          ? 'サポーターとして登録しました'
          : 'サポーター申請を送信しました',
      )
    } catch (error) {
      handleApiError(error, 'サポーター申請')
    } finally {
      followLoading.value = false
    }
  }

  async function cancelSupporter() {
    followLoading.value = true
    try {
      await orgApi.unfollowOrganization(orgId.value)
      followStatus.value = 'NONE'
      showCancelSupporterConfirm.value = false
      notification.success('サポーターをやめました')
    } catch (error) {
      handleApiError(error, 'サポーター解除')
    } finally {
      followLoading.value = false
    }
  }

  async function leaveOrganization() {
    try {
      await orgApi.leaveOrganization(orgId.value)
      notification.success('組織から退出しました')
      navigateTo('/dashboard')
    } catch (error) {
      handleApiError(error, '組織退出')
    } finally {
      showLeaveConfirm.value = false
    }
  }

  return {
    org,
    orgTeams,
    permissionGroups,
    loading,
    followStatus,
    followLoading,
    showCancelSupporterConfirm,
    showLeaveConfirm,
    fetchOrg,
    fetchOrgTeams,
    fetchPermissionGroups,
    fetchFollowStatus,
    applySupporter,
    cancelSupporter,
    leaveOrganization,
  }
}
