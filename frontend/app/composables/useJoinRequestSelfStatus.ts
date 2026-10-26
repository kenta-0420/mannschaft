import type { JoinRequestResponse, JoinRequestScopeType, JoinRequestUiStatus } from '~/composables/useJoinRequestApi'

/**
 * 「自分の参加申請状態」の取得・送信を扱う共通 composable
 * （Codex 検分 CMP-260901-1538 第1巡 P1-1・第2巡 P1-1/P1-2 是正）。
 *
 * 組織・チーム双方の `[slug].vue` が同じロジックを個別実装しており、
 * 取得失敗を握りつぶして `NONE`（未申請）に潰す fail-open になっていた
 * （失敗しても「未申請」と表示され再送信できてしまう）。
 * 本 composable に一本化し、取得中・取得失敗を明示的な状態として保持することで
 * fail-close（申請操作を無効化）を徹底する（第1巡 P1-1）。
 *
 * さらに、取得世代番号と対象 scopeId を保持し、旧スコープの遅い応答が
 * 新スコープの状態を上書きしないよう検証する（第2巡 P1-2）。
 * 申請送信（applyJoinRequest）の成功時も、送信先スコープが今も表示中の
 * スコープと一致する場合のみ状態を書き換える（第3巡 P1）。
 */
export function useJoinRequestSelfStatus(scopeType: JoinRequestScopeType) {
  const { createJoinRequest, listMyJoinRequests } = useJoinRequestApi()
  const { handleApiError } = useErrorHandler()
  const notification = useNotification()
  // useI18n() は setup コンテキスト外（本 composable のような素の関数呼び出し）では
  // 呼べないため、useApi.ts と同様に useNuxtApp().$i18n 経由でアクセスする。
  const t = (key: string) => useNuxtApp().$i18n.t(key)

  const joinRequestStatus = ref<JoinRequestUiStatus>('UNKNOWN')
  const joinRequestLoading = ref(false)

  // Codex 検分第2巡 P1-2 是正: 取得世代番号と対象 scopeId を保持し、
  // 完了時に「自分が最新の呼び出しであり、かつ同一スコープ宛てである」ことを
  // 検証してから応答を適用する。slug 切替直後は旧スコープの遅い応答が後着し
  // 得るため、世代番号だけでは同一スコープへの多重呼び出し順序保証にしかならず、
  // scopeId も併せて比較することで旧スコープの応答が新スコープの状態を
  // 上書きする事故（fail-close の破壊）を防ぐ。
  let fetchSeq = 0
  let currentScopeId: number | null = null

  /**
   * BE の申請一覧（`me`）から UI 表示用ステートを導出する。
   * PENDING が1件でもあれば最優先。無ければ直近の審査結果（APPROVED/REJECTED）を見る。
   * どちらも無ければ未申請（NONE）。
   *
   * REJECTED は「再申請できない」ことを意味しない。BE は既存の PENDING のみを
   * 重複扱いし、却下後の新規申請を許可している（JoinRequestService.java）ため、
   * REJECTED は「前回は却下された」という参考情報であり、申請操作は NONE と
   * 同様に有効のままにする（呼び出し側の判定に使う）。
   */
  function deriveStatus(list: JoinRequestResponse[]): JoinRequestUiStatus {
    if (list.some(r => r.status === 'PENDING')) return 'PENDING'
    const reviewed = [...list]
      .filter(r => r.status !== 'PENDING')
      .sort((a, b) => (a.reviewedAt ?? a.createdAt).localeCompare(b.reviewedAt ?? b.createdAt))
    const latest = reviewed.at(-1)
    if (latest?.status === 'APPROVED') return 'APPROVED'
    if (latest?.status === 'REJECTED') return 'REJECTED'
    return 'NONE'
  }

  /**
   * 自分の申請状態を取得する。取得中は `LOADING`、失敗時は `ERROR` を保持し、
   * どちらも `NONE`（未申請）へは絶対に潰さない（fail-close）。
   *
   * 応答が返った時点で「自分が最新の呼び出し」かつ「呼び出し時と同一スコープ」で
   * なければ、応答を破棄して共有状態を書き換えない（P1-2 是正）。
   */
  async function fetchJoinRequestStatus(scopeId: number) {
    const seq = ++fetchSeq
    currentScopeId = scopeId
    joinRequestStatus.value = 'LOADING'
    try {
      const res = await listMyJoinRequests(scopeType, scopeId)
      if (seq !== fetchSeq || currentScopeId !== scopeId) return
      joinRequestStatus.value = deriveStatus(res.data)
    }
    catch (error) {
      if (seq !== fetchSeq || currentScopeId !== scopeId) return
      handleApiError(error, '参加申請状態取得')
      joinRequestStatus.value = 'ERROR'
    }
  }

  async function applyJoinRequest(scopeId: number) {
    joinRequestLoading.value = true
    try {
      await createJoinRequest(scopeType, scopeId)
      // Codex 検分第3巡 P1 是正: 送信先スコープが「今も表示中のスコープ」であることを
      // 確認してから状態を書き換える。確認せずに fetchSeq を進めて PENDING に上書きすると、
      // 申請中に別スコープへ遷移していた場合、後発の正当な新スコープ取得（世代検証で
      // 古い応答として弾かれる側）を巻き添えにして誤った PENDING 表示を残してしまう。
      if (currentScopeId === scopeId) {
        // 直前まで進行中だった同一スコープの fetchJoinRequestStatus の応答が後着しても、
        // 今まさに確定した PENDING を上書きしないよう世代を進めておく。
        fetchSeq += 1
        joinRequestStatus.value = 'PENDING'
      }
      notification.success(t('common.scopeShell.join_request_applied'))
    }
    catch (error) {
      handleApiError(error, '参加申請')
    }
    finally {
      joinRequestLoading.value = false
    }
  }

  return {
    joinRequestStatus,
    joinRequestLoading,
    fetchJoinRequestStatus,
    applyJoinRequest,
  }
}
