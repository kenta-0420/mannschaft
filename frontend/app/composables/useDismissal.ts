import type { Ref } from 'vue'
import type { DismissalRequest, DismissalStatusResponse } from '~/types/care'
import { useOfflineCareQueue } from '~/composables/jobs/useOfflineCareQueue'

/**
 * F03.12 §16 イベント解散通知の上位 composable。
 *
 * <p>足軽 D 担当: 中身実装。骨格（state とシグネチャ）は足軽 A が確定。</p>
 *
 * <h3>動作</h3>
 * <ul>
 *   <li>{@link loadStatus}: GET /dismissal/status を呼んで {@link status} を更新する。</li>
 *   <li>{@link send} / {@link submit}: POST /dismissal を呼ぶ。
 *     オフライン時は {@code useOfflineCareQueue} のキューへ積み、後続のオンライン復帰時に
 *     {@code flushPendingCareJobs} 経由で送信される。</li>
 * </ul>
 *
 * <p>二重送信防止: {@code status.value?.dismissed === true} の状態で {@link send} を呼ぶと
 * エラーを {@link error} に設定し、API 呼び出しは行わない（BE は 409 を返す仕様だが、
 * 余計なネットワーク呼び出しを避ける目的）。</p>
 */
export function useDismissal(teamId: Ref<number>, eventId: Ref<number>) {
  const status = ref<DismissalStatusResponse | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  const api = useDismissalApi()
  const careQueue = useOfflineCareQueue()
  const notify = useNotification()

  /** ブラウザのオンライン状態を返す（SSR/テスト環境では true 扱い）。 */
  function isOnline(): boolean {
    if (typeof navigator === 'undefined') return true
    // navigator.onLine は false が「明確にオフライン」の意味で、true は「不明 or オンライン」。
    return navigator.onLine !== false
  }

  /** 解散通知の送信状態を取得する。 */
  async function loadStatus(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const res = await api.getDismissalStatus(teamId.value, eventId.value)
      status.value = res
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  }

  /**
   * 解散通知を送信する。
   *
   * <p>オンライン時は API を呼んで成功時に {@link status} を更新する。
   * オフライン時はジョブをオフラインキューに積み、後続の sync 時に送信する。</p>
   *
   * @returns 送信後の最新ステータス。オフラインキュー積みの場合と失敗時は null。
   */
  async function send(body: DismissalRequest): Promise<DismissalStatusResponse | null> {
    // 二重送信防止
    if (status.value?.dismissed === true) {
      const message = '既に解散通知を送信済みです'
      error.value = message
      notify.warn(message)
      return null
    }

    loading.value = true
    error.value = null

    // オフラインの場合はキューへ積んで終了
    if (!isOnline()) {
      try {
        await careQueue.enqueueCareJob({
          type: 'DISMISSAL',
          teamId: teamId.value,
          eventId: eventId.value,
          payload: body,
        })
        notify.info('オフライン保存（同期待ち）')
        return null
      } catch (e) {
        error.value = e instanceof Error ? e.message : String(e)
        notify.error('オフライン保存に失敗しました')
        return null
      } finally {
        loading.value = false
      }
    }

    // オンライン: 直接送信
    try {
      const next = await api.submitDismissal(teamId.value, eventId.value, body)
      status.value = next
      notify.success('解散通知を送信しました')
      return next
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      notify.error('解散通知の送信に失敗しました')
      return null
    } finally {
      loading.value = false
    }
  }

  return {
    status,
    loading,
    error,
    loadStatus,
    send,
    /** {@link send} のエイリアス（task 説明上の名前）。 */
    submit: send,
  }
}
