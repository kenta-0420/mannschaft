/**
 * 「自分のフォロー（サポーター）状態」の取得・申請・解除を扱う共通 composable
 * （CMP-261001-0835）。
 *
 * 組織・チーム双方の `useOrgDetail.ts` / `pages/teams/[slug].vue` が同じロジックを
 * 個別実装しており、2つの fail-open 不具合を抱えていた:
 *
 * 1. `fetchFollowStatus` が `if (roleName.value) return` で早期 return していたため、
 *    フォロー承認で付与される SUPPORTER ロール自身の状態が一度も取得されず、
 *    常に初期値 'NONE' のまま固まっていた（AC-1/AC-5: ロールの有無に関係なく取得する）。
 * 2. 取得失敗を握りつぶして 'NONE' に潰していたため、既知の APPROVED/PENDING が
 *    BE 障害時に誤って「未フォロー」へ倒れていた（AC-6: UNKNOWN/LOADING/ERROR を
 *    明示的に区別し、失敗で既知の状態を変えない）。
 *
 * `useJoinRequestSelfStatus` と同型の fail-close パターンを採用する。
 */

export type FollowUiStatus = 'UNKNOWN' | 'LOADING' | 'NONE' | 'PENDING' | 'APPROVED' | 'ERROR'

export interface FollowStatusApi {
  follow: (scopeId: string) => Promise<unknown>
  unfollow: (scopeId: string) => Promise<unknown>
  getStatus: (scopeId: string) => Promise<{ data: { status: 'NONE' | 'PENDING' | 'APPROVED' } }>
}

/** `useRoleAccess().loadPermissions` の戻り値型（権限取得の成否）。 */
export type PermissionReloadResult = { ok: true } | { ok: false, error: unknown }

export function useFollowSelfStatus(api: FollowStatusApi) {
  const { handleApiError } = useErrorHandler()
  const notification = useNotification()
  // useI18n() は setup コンテキスト外（素の関数呼び出し）では呼べないため、
  // useApi.ts / useJoinRequestSelfStatus.ts と同様に $i18n 経由でアクセスする。
  const t = (key: string, params: Record<string, unknown> = {}) => useNuxtApp().$i18n.t(key, params)

  const followStatus = ref<FollowUiStatus>('UNKNOWN')
  const followLoading = ref(false)
  /**
   * AC-9: フォロー解除は成功したが権限（roleName）の再取得が失敗したときに立てるフラグ。
   * `useRoleAccess.loadPermissions` は失敗時 `roleName=null` を返すため、これを検証なしに
   * 「未所属確定」として扱うと、実際には同期が取れていない状態を誤って確定させてしまう。
   * このフラグが立っている間はヘッダ側で「フォローする」等の操作ボタンを出さず、
   * 権限再取得のみ再試行できる手段を出す。
   */
  const followPermissionSyncError = ref(false)

  // 旧スコープの遅い応答が新スコープの状態を上書きしないための世代番号 + 対象 scopeId 検証
  // （useJoinRequestSelfStatus と同型）。
  let fetchSeq = 0
  let currentScopeId: string | null = null

  /**
   * 自分のフォロー状態を取得する（AC-5: ロールの有無に関係なく常に呼ぶ）。
   * 取得中は `LOADING`、失敗時は `ERROR` を保持し、どちらも `NONE` へは潰さない（AC-6）。
   */
  async function fetchFollowStatus(scopeId: string) {
    // 永続シェルでのスコープ遷移競合対策: スコープが変わったら世代を進め、
    // 旧スコープに紐づく同期状態（AC-9 の同期失敗フラグ）を初期化する。
    // これを怠ると、別スコープへ遷移した後に旧スコープの遅延応答がヘッダを上書きする。
    if (currentScopeId !== scopeId) {
      followPermissionSyncError.value = false
    }
    const seq = ++fetchSeq
    currentScopeId = scopeId
    followStatus.value = 'LOADING'
    try {
      const res = await api.getStatus(scopeId)
      if (seq !== fetchSeq || currentScopeId !== scopeId) return
      followStatus.value = res.data.status
    }
    catch (error) {
      if (seq !== fetchSeq || currentScopeId !== scopeId) return
      handleApiError(error, 'フォロー状態取得')
      followStatus.value = 'ERROR'
    }
  }

  async function applySupporter(scopeId: string) {
    // 開始時点のスコープ/世代に束縛し、完了時にスコープが変わっていたら表示へ反映しない
    // （永続シェルでのスコープ遷移競合対策）。
    currentScopeId = scopeId
    const seq = fetchSeq
    followLoading.value = true
    try {
      await api.follow(scopeId)
      const res = await api.getStatus(scopeId)
      if (seq !== fetchSeq || currentScopeId !== scopeId) return
      followStatus.value = res.data.status
      notification.success(
        res.data.status === 'APPROVED'
          ? t('common.scopeShell.supporter_registered')
          : t('common.scopeShell.supporter_applied'),
      )
    }
    catch (error) {
      handleApiError(error, 'サポーター申請')
    }
    finally {
      followLoading.value = false
    }
  }

  /**
   * フォロー解除。
   *
   * - AC-7: 成功後は followStatus を NONE にしてから、呼び出し元が渡す権限再取得
   *   コールバック（`useRoleAccess().loadPermissions`）を呼ぶ。
   * - AC-8: 解除 API 自体の失敗はエラー通知のみで、followStatus は変更しない。
   * - AC-9: 解除は成功したが権限再取得が失敗（`{ ok: false }`）した場合は、
   *   解除成功の通知とは別に同期失敗を通知し、`followPermissionSyncError` を立てる。
   */
  async function cancelSupporter(
    scopeId: string,
    reloadPermissions: () => Promise<PermissionReloadResult>,
  ) {
    // 開始時点のスコープ/世代に束縛する（永続シェルでのスコープ遷移競合対策）。
    currentScopeId = scopeId
    const seq = fetchSeq
    const boundScopeId = scopeId
    followLoading.value = true
    try {
      await api.unfollow(scopeId)
      if (seq !== fetchSeq || currentScopeId !== boundScopeId) return
      followStatus.value = 'NONE'
      followPermissionSyncError.value = false
      notification.success(t('common.scopeShell.supporter_canceled'))

      // 権限再取得コールバックも開始時スコープ基準で呼ぶ。世代が変わっていたら
      // 新スコープの表示へ無関係な旧スコープの権限再取得を呼ばない。
      const result = await reloadPermissions()
      if (seq !== fetchSeq || currentScopeId !== boundScopeId) return
      if (!result.ok) {
        followPermissionSyncError.value = true
        notification.error(
          t('common.scopeShell.follow_permission_sync_error_title'),
          t('common.scopeShell.follow_permission_sync_error_body'),
        )
      }
    }
    catch (error) {
      handleApiError(error, 'サポーター解除')
    }
    finally {
      followLoading.value = false
    }
  }

  /** AC-9 の再試行導線: 権限再取得のみをやり直す。 */
  async function retryFollowPermissionSync(
    reloadPermissions: () => Promise<PermissionReloadResult>,
  ) {
    // 開始時点のスコープ/世代に束縛する（永続シェルでのスコープ遷移競合対策）。
    const seq = fetchSeq
    const boundScopeId = currentScopeId
    const result = await reloadPermissions()
    if (seq !== fetchSeq || currentScopeId !== boundScopeId) return
    followPermissionSyncError.value = !result.ok
    if (!result.ok) {
      notification.error(
        t('common.scopeShell.follow_permission_sync_error_title'),
        t('common.scopeShell.follow_permission_sync_error_body'),
      )
    }
  }

  return {
    followStatus,
    followLoading,
    followPermissionSyncError,
    fetchFollowStatus,
    applySupporter,
    cancelSupporter,
    retryFollowPermissionSync,
  }
}
