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

  // フォロー（サポーター）状態の取得・申請・解除はページ側の `useScopeFollowWiring` に一本化
  // （CMP-261001-0835。権限再取得 loadPermissions との結線をページ単位で持つため）。
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
    joinRequestStatus,
    joinRequestLoading,
    showLeaveConfirm,
    fetchOrg,
    fetchOrgTeams,
    fetchPermissionGroups,
    fetchJoinRequestStatus,
    applyJoinRequest,
    leaveOrganization,
  }
}
