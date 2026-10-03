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
}) {
  const { scopeSlug, api, roleAccess } = options
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

  async function applySupporter() {
    await core.applySupporter(scopeSlug.value)
  }

  /**
   * フォロー解除。成功後は権限（loadPermissions）を再取得する（AC-7/AC-9）。
   * ダイアログは同一スコープのまま解除 API が成功した場合のみ閉じる（AC-8）。
   */
  async function cancelSupporter() {
    const succeeded = await core.cancelSupporter(scopeSlug.value, roleAccess.loadPermissions)
    if (succeeded) showCancelSupporterConfirm.value = false
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
