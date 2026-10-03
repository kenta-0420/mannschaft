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

  /**
   * フォロー（サポーター）状態の取得・申請・解除は共通 composable に一本化（CMP-261001-0835）。
   * 詳細は `useFollowSelfStatus.ts` のコメントを参照（SUPPORTER ロール自身の状態が
   * 永遠に NONE に固まる fail-open と、取得失敗を NONE に潰す fail-open の是正）。
   */
  const {
    followStatus,
    followLoading,
    followPermissionSyncError,
    fetchFollowStatus: fetchFollowStatusRaw,
    applySupporter: applySupporterRaw,
    cancelSupporter: cancelSupporterRaw,
    retryFollowPermissionSync: retryFollowPermissionSyncRaw,
  } = useFollowSelfStatus({
    follow: orgApi.followOrganization,
    unfollow: orgApi.unfollowOrganization,
    getStatus: orgApi.getFollowStatus,
  })

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

  /** AC-5: ロールの有無に関係なく常に取得する（SUPPORTER ロール自身の状態も含む）。 */
  async function fetchFollowStatus() {
    await fetchFollowStatusRaw(orgId.value)
  }

  async function applySupporter() {
    await applySupporterRaw(orgId.value)
  }

  /**
   * フォロー解除。成功後は呼び出し元が渡す権限再取得コールバック（`loadPermissions`）を
   * 実行する（AC-7/AC-9）。ダイアログは解除 API 自体が成功した場合のみ閉じる
   * （AC-8: 解除失敗時は表示を変えず、ダイアログも開いたままにして再試行させる）。
   */
  async function cancelSupporter(reloadPermissions: () => Promise<{ ok: true } | { ok: false, error: unknown }>) {
    await cancelSupporterRaw(orgId.value, reloadPermissions)
    if (followStatus.value === 'NONE') {
      showCancelSupporterConfirm.value = false
    }
  }

  /** AC-9 の再試行導線: 権限再取得のみをやり直す。 */
  async function retryFollowPermissionSync(reloadPermissions: () => Promise<{ ok: true } | { ok: false, error: unknown }>) {
    await retryFollowPermissionSyncRaw(reloadPermissions)
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
    followPermissionSyncError,
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
    retryFollowPermissionSync,
    fetchJoinRequestStatus,
    applyJoinRequest,
    leaveOrganization,
  }
}
