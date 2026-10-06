import type {
  OrgTeam,
  OrgPermissionGroup,
  OrgBasicInfoDto,
  OrgHierarchyDto,
  OrgLocationDto,
  OrgVisibilityDto,
  OrgMetadataDto,
  OrgTimestampsDto,
  OrgSocialDto,
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
  /**
   * サポーター人数（CMP-261004-1942/1943）。BE OrganizationResponse.social.supporterCount に
   * 一本化し、旧フラット supporterCount は廃止した（移行完了）。
   */
  social?: OrgSocialDto
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

  /**
   * 組織詳細の取得（CMP-261004-1942/1943）。
   *
   * - 静かな再取得: 既に org が取得済み（null でない）なら `loading` を立てない。
   *   応援・解除後の人数取り直しで全画面スピナーを出し、ヘッダ・確認ダイアログを
   *   一瞬消さないため（AC-1/AC-2）。
   * - 世代ガード: 「最後に発行した要求」かつ「スコープが要求時と同じ」応答だけを反映する（AC-5）。
   *   スコープ一致だけでは、同一スコープの取り直し①（応援後・1人）が遅延中に取り直し②（解除後・0人）が
   *   先に反映されると、後着の①で古い人数に戻る。A→B→A の切替でも古い A の応答が新しい A を上書きする。
   *   403 で詳細を閉じた後に、それより前に発行した要求の遅延 200 が詳細を再表示することも防ぐ。
   *   （連番は useRoleAccess と同じ作法）
   * - AC-11: 取得失敗が 403（権限喪失）のときは org を null にして保護された詳細表示を閉じる。
   */
  let fetchOrgSeq = 0
  async function fetchOrg() {
    const seq = ++fetchOrgSeq
    const requestedId = orgId.value
    const isCurrent = () => seq === fetchOrgSeq && orgId.value === requestedId
    const quiet = org.value !== null
    if (!quiet) loading.value = true
    try {
      const result = await orgApi.getOrganization(requestedId)
      if (!isCurrent()) return
      org.value = result.data as OrgDetail
    } catch (error) {
      if (!isCurrent()) return
      const status = (error as { statusCode?: number, response?: { status?: number }, status?: number })
        ?.statusCode ?? (error as { response?: { status?: number } })?.response?.status
        ?? (error as { status?: number })?.status
      if (status === 403) org.value = null
      handleApiError(error, '組織詳細取得')
    } finally {
      // 後発の要求が無いときだけ解く（後発がある場合はその要求の完了で解く）
      if (seq === fetchOrgSeq) loading.value = false
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
