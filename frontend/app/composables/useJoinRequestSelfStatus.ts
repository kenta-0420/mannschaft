import type { JoinRequestResponse, JoinRequestScopeType, JoinRequestUiStatus } from '~/composables/useJoinRequestApi'

/**
 * 「自分の参加申請状態」の取得・送信を扱う共通 composable
 * （Codex 検分 CMP-260901-1538 第1巡 P1-1 是正）。
 *
 * 組織・チーム双方の `[slug].vue` が同じロジックを個別実装しており、
 * 取得失敗を握りつぶして `NONE`（未申請）に潰す fail-open になっていた
 * （失敗しても「未申請」と表示され再送信できてしまう）。
 * 本 composable に一本化し、取得中・取得失敗を明示的な状態として保持することで
 * fail-close（申請操作を無効化）を徹底する。
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

  /**
   * BE の申請一覧（`me`）から UI 表示用ステートを導出する。
   * PENDING が1件でもあれば最優先。無ければ直近の審査結果（APPROVED/REJECTED）を見る。
   * どちらも無ければ未申請（NONE）。
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
   */
  async function fetchJoinRequestStatus(scopeId: number) {
    joinRequestStatus.value = 'LOADING'
    try {
      const res = await listMyJoinRequests(scopeType, scopeId)
      joinRequestStatus.value = deriveStatus(res.data)
    }
    catch (error) {
      handleApiError(error, '参加申請状態取得')
      joinRequestStatus.value = 'ERROR'
    }
  }

  async function applyJoinRequest(scopeId: number) {
    joinRequestLoading.value = true
    try {
      await createJoinRequest(scopeType, scopeId)
      joinRequestStatus.value = 'PENDING'
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
