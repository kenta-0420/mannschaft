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

  /**
   * 世代管理は2本立て（CMP-261001-0835 検分修繕2）。
   *
   * - scopeGen: スコープ（slug）が変わった瞬間に進める世代。申請/解除/権限再試行は開始時の
   *   scopeGen を捕捉し、await 後に一致すれば「同一スコープ」なので後処理（通知・状態更新・
   *   権限再取得）を必ず完了する。不一致なら何も反映せず通知も出さず、権限再取得も呼ばない。
   * - getSeq: follow/status GET の要求番号。GET 結果は scopeGen と getSeq の双方が一致する
   *   ときだけ反映する。解除/申請の成功時は getSeq を進めて、操作前に飛ばした古い GET
   *   （例: 解除前の APPROVED）が後から返っても表示を巻き戻さないようにする。
   *
   * 1本の番号で両方を兼ねると、同一スコープの再取得が成功した操作の後処理まで捨ててしまう
   * （解除成功通知・NONE 反映・権限再取得の欠落）。
   */
  let scopeGen = 0
  let getSeq = 0
  let currentScopeId: string | null = null

  /**
   * スコープ切替を同期的に確定する。ページの slug 変更を検知した瞬間（詳細取得を await する
   * 前）に呼ぶこと。旧スコープの操作・GET の遅延応答はこれ以降すべて無視される。
   */
  function resetForScope(scopeId: string) {
    scopeGen++
    getSeq++
    currentScopeId = scopeId
    followStatus.value = 'UNKNOWN'
    followLoading.value = false
    followPermissionSyncError.value = false
  }

  /** 呼び出し側が resetForScope を経ずに別スコープを渡した場合の安全網。 */
  function ensureScope(scopeId: string) {
    if (currentScopeId !== scopeId) resetForScope(scopeId)
  }

  /**
   * 自分のフォロー状態を取得する（AC-5: ロールの有無に関係なく常に呼ぶ）。
   * 取得中は `LOADING`、失敗時は `ERROR` を保持し、どちらも `NONE` へは潰さない（AC-6）。
   */
  async function fetchFollowStatus(scopeId: string) {
    ensureScope(scopeId)
    const gen = scopeGen
    const seq = ++getSeq
    followStatus.value = 'LOADING'
    try {
      const res = await api.getStatus(scopeId)
      if (gen !== scopeGen || seq !== getSeq) return
      followStatus.value = res.data.status
    }
    catch (error) {
      if (gen !== scopeGen || seq !== getSeq) return
      handleApiError(error, 'フォロー状態取得')
      followStatus.value = 'ERROR'
    }
  }

  async function applySupporter(scopeId: string) {
    ensureScope(scopeId)
    const gen = scopeGen
    followLoading.value = true
    try {
      await api.follow(scopeId)
      if (gen !== scopeGen) return
      // 申請前に飛ばした古い GET の結果で申請後の状態を巻き戻さない。
      const seq = ++getSeq
      const res = await api.getStatus(scopeId)
      if (gen !== scopeGen) return
      // 申請後にさらに新しい GET が始まっていればそちらが新しい状態を反映する。
      if (seq === getSeq) followStatus.value = res.data.status
      notification.success(
        res.data.status === 'APPROVED'
          ? t('common.scopeShell.supporter_registered')
          : t('common.scopeShell.supporter_applied'),
      )
    }
    catch (error) {
      if (gen !== scopeGen) return
      handleApiError(error, 'サポーター申請')
    }
    finally {
      if (gen === scopeGen) followLoading.value = false
    }
  }

  /**
   * フォロー解除。戻り値は「同一スコープのまま解除 API が成功したか」。
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
  ): Promise<boolean> {
    ensureScope(scopeId)
    const gen = scopeGen
    followLoading.value = true
    try {
      await api.unfollow(scopeId)
      if (gen !== scopeGen) return false
      // 解除前に飛ばした GET（APPROVED）が後から返っても NONE を巻き戻させない。
      getSeq++
      followStatus.value = 'NONE'
      followPermissionSyncError.value = false
      notification.success(t('common.scopeShell.supporter_canceled'))

      const result = await reloadPermissions()
      // 権限再取得の待機中に別スコープへ遷移していたら、戻り値は呼び出し元（旧スコープの
      // 確認ダイアログ）を操作させないよう false にする。解除 API 自体は成功済みのため
      // followStatus は巻き戻さないが、新スコープの UI（ダイアログ）には触れない。
      if (gen !== scopeGen) return false
      if (!result.ok) {
        followPermissionSyncError.value = true
        notification.error(
          t('common.scopeShell.follow_permission_sync_error_title'),
          t('common.scopeShell.follow_permission_sync_error_body'),
        )
      }
      return true
    }
    catch (error) {
      if (gen === scopeGen) handleApiError(error, 'サポーター解除')
      return false
    }
    finally {
      if (gen === scopeGen) followLoading.value = false
    }
  }

  /** AC-9 の再試行導線: 権限再取得のみをやり直す。 */
  async function retryFollowPermissionSync(
    reloadPermissions: () => Promise<PermissionReloadResult>,
  ) {
    const gen = scopeGen
    const result = await reloadPermissions()
    if (gen !== scopeGen) return
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
    resetForScope,
    fetchFollowStatus,
    applySupporter,
    cancelSupporter,
    retryFollowPermissionSync,
  }
}
