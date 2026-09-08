import type {
  OrgTeam,
  OrgPermissionGroup,
  OrgBasicInfoDto,
  OrgHierarchyDto,
  OrgLocationDto,
  OrgVisibilityDto,
  OrgMetadataDto,
  OrgTimestampsDto,
} from '~/types/organization'

// Wave 3-B: OrganizationResponse ネスト構造に対応
export interface OrgDetail {
  /**
   * URL 識別子（カスタムスラッグ）。実体は slug と同値の string 型。
   * BE #1547 slug移行対応（旧 UUID public_id 方式は廃止済み・project_url_identifier_slug_canonical）。
   */
  id: string
  /**
   * 組織の内部 BIGINT ID（F09.19.10）。URL には使わない（URL 識別子は上記 id/slug が正準）。
   * Spotlight 掲載面など BE が Long スコープ ID を要求する内部連携専用に使用する。
   */
  numericId?: number
  basicInfo?: OrgBasicInfoDto
  hierarchy?: OrgHierarchyDto
  location?: OrgLocationDto
  visibility?: OrgVisibilityDto
  metadata?: OrgMetadataDto
  timestamps?: OrgTimestampsDto
  // 旧フラット互換（別エンドポイントや内部追加フィールド）
  supporterCount?: number
  description?: string | null
}

export function useOrgDetail(orgId: Ref<string>) {
  const orgApi = useOrganizationApi()
  const notification = useNotification()
  const { handleApiError } = useErrorHandler()
  const { t } = useI18n()

  const org = ref<OrgDetail | null>(null)
  const orgTeams = ref<OrgTeam[]>([])
  const permissionGroups = ref<OrgPermissionGroup[]>([])
  const loading = ref(false)

  const followStatus = ref<'NONE' | 'PENDING' | 'APPROVED'>('NONE')
  const followLoading = ref(false)
  const showCancelSupporterConfirm = ref(false)
  const showLeaveConfirm = ref(false)

  /**
   * MEMBER 参加申請（柱③-A・CMP-260901-1538）。
   * 取り下げ API は BE 未実装（PR #3139）のため PENDING 表示のみ提供する（対処療法禁止の原則）。
   *
   * 自分の申請状態の取得・送信は `useJoinRequestSelfStatus` に一本化している
   * （Codex 検分第1巡 P1-1: 取得失敗を NONE に潰す fail-open を是正）。
   */
  const {
    joinRequestStatus,
    joinRequestLoading,
    fetchJoinRequestStatus: fetchJoinRequestStatusRaw,
    applyJoinRequest: applyJoinRequestRaw,
  } = useJoinRequestSelfStatus('organization')

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
          ? t('common.scopeShell.supporter_registered')
          : t('common.scopeShell.supporter_applied'),
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
      notification.success(t('common.scopeShell.supporter_canceled'))
    } catch (error) {
      handleApiError(error, 'サポーター解除')
    } finally {
      followLoading.value = false
    }
  }

  async function fetchJoinRequestStatus(roleName: Ref<string | null>) {
    if (roleName.value) return
    if (org.value?.visibility?.visibility !== 'PUBLIC') return
    if (!org.value?.numericId) return
    await fetchJoinRequestStatusRaw(org.value.numericId)
  }

  async function applyJoinRequest() {
    if (!org.value?.numericId) return
    await applyJoinRequestRaw(org.value.numericId)
  }

  async function leaveOrganization() {
    try {
      await orgApi.leaveOrganization(orgId.value)
      notification.success(t('orgShell.action.left'))
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
    joinRequestStatus,
    joinRequestLoading,
    showCancelSupporterConfirm,
    showLeaveConfirm,
    fetchOrg,
    fetchOrgTeams,
    fetchPermissionGroups,
    fetchFollowStatus,
    applySupporter,
    cancelSupporter,
    fetchJoinRequestStatus,
    applyJoinRequest,
    leaveOrganization,
  }
}
