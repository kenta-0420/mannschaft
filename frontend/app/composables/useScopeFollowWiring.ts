import type { Ref } from 'vue'
import type { FollowStatusApi, PermissionReloadResult } from './useFollowSelfStatus'

/**
 * 組織・チームのシェルページ（`pages/organizations/[slug].vue` / `pages/teams/[slug].vue`）の
 * フォロー（サポーター）結線を1か所にまとめる composable（CMP-261001-0835 検分修繕2）。
 *
 * ページ側はこの戻り値をヘッダ・確認ダイアログへ渡すだけにし、ラッパー（状態取得・解除・
 * 権限再試行）をこの composable の単体テストで検証できるようにする
 * （ページ本体の mountSuspended は実行時依存が重く、ソーステキスト検査ではラッパー内部の
 * 退行を検出できないため）。
 */
export interface ScopeFollowRoleAccess {
  roleName: Ref<string | null>
  loadPermissions: () => Promise<PermissionReloadResult>
}

export function useScopeFollowWiring(options: {
  scopeSlug: Ref<string>
  api: FollowStatusApi
  roleAccess: ScopeFollowRoleAccess
  /**
   * 応援（follow）・解除（unfollow）が成功した直後に呼ぶ、スコープ詳細（人数等）の
   * 取り直し関数（CMP-261004-1942。チーム=fetchTeam、組織=fetchOrg）。
   * follow/unfollow 自体が失敗した場合や、操作中に別スコープへ切り替わった場合は呼ばない。
   * reject しても応援/解除自体の失敗として扱わない（取り直し関数側が失敗時の通知・状態反映を
   * 内部で済ませているため、ここでは二重通知も状態巻き戻しもしない）。
   */
  refreshDetail?: () => Promise<unknown>
}) {
  const { scopeSlug, api, roleAccess, refreshDetail } = options
  const core = useFollowSelfStatus(api)
  const showCancelSupporterConfirm = ref(false)

  // スコープ（slug）が変わった瞬間に同期的に世代を進める。ページの slug watch（詳細取得を
  // await してから状態を取り直す）より前に旧スコープの遅延応答を無効化するため flush: 'sync'。
  watch(scopeSlug, (next) => {
    showCancelSupporterConfirm.value = false
    core.resetForScope(next)
  }, { flush: 'sync' })

  /** AC-5: ロールの有無に関係なく常に取得する（SUPPORTER ロール自身の状態も含む）。 */
  async function fetchFollowStatus() {
    await core.fetchFollowStatus(scopeSlug.value)
  }

  /**
   * refreshDetail の reject は応援/解除自体の失敗として扱わない（CMP-261004-1942）。
   * fetchTeam/fetchOrg は失敗時に内部で handleApiError 済みで reject しない実装だが、
   * 呼び出し元（試練のモック等）が reject する場合に備えた防御。
   */
  async function refreshDetailSafely() {
    if (!refreshDetail) return
    try {
      await refreshDetail()
    }
    catch (error) {
      // 応援/解除の失敗通知や状態巻き戻しは行わない（取り直し関数側の責務ではないため）。
      // ただし例外を握りつぶさず、根治調査のために表面化させる。
      console.warn('[useScopeFollowWiring] 詳細の取り直しに失敗', error)
    }
  }

  async function applySupporter() {
    const succeeded = await core.applySupporter(scopeSlug.value)
    if (succeeded) await refreshDetailSafely()
  }

  /**
   * フォロー解除。成功後は権限（loadPermissions）を再取得する（AC-7/AC-9）。
   * ダイアログは同一スコープのまま解除 API が成功した場合のみ閉じる（AC-8）。
   * 同様に成功後は詳細（人数）の取り直しを行う（CMP-261004-1942）。
   */
  async function cancelSupporter() {
    const succeeded = await core.cancelSupporter(scopeSlug.value, roleAccess.loadPermissions)
    if (succeeded) {
      showCancelSupporterConfirm.value = false
      await refreshDetailSafely()
    }
  }

  /** AC-6: フォロー状態取得エラー時の再試行導線。 */
  async function retryFollowStatus() {
    await fetchFollowStatus()
  }

  /** AC-9 の再試行導線: 権限再取得のみをやり直す。 */
  async function retryFollowPermissionSync() {
    await core.retryFollowPermissionSync(roleAccess.loadPermissions)
  }

  return {
    followStatus: core.followStatus,
    followLoading: core.followLoading,
    followPermissionSyncError: core.followPermissionSyncError,
    showCancelSupporterConfirm,
    fetchFollowStatus,
    applySupporter,
    cancelSupporter,
    retryFollowStatus,
    retryFollowPermissionSync,
  }
}
