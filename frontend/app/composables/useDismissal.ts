import type { Ref } from 'vue'
import type { DismissalRequest, DismissalStatusResponse } from '~/types/care'

/**
 * F03.12 §16 イベント解散通知の上位 composable（骨格）。
 *
 * <p>足軽 A はシグネチャと state を確定するのみ。中身は足軽 D が実装する。</p>
 */
export function useDismissal(teamId: Ref<number>, eventId: Ref<number>) {
  const status = ref<DismissalStatusResponse | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 解散通知の送信状態を取得する。 */
  async function loadStatus(): Promise<void> {
    void teamId
    void eventId
    /* TODO 足軽D: useDismissalApi().getDismissalStatus() を呼び status を更新 */
  }

  /**
   * 解散通知を送信する。
   *
   * @returns 送信後の最新ステータス。失敗時は null。
   */
  async function send(body: DismissalRequest): Promise<DismissalStatusResponse | null> {
    void body
    /* TODO 足軽D: useDismissalApi().submitDismissal() を呼ぶ。
       オフライン時は useOfflineCareQueue.enqueueCareJob を使うこと */
    return null
  }

  return {
    status,
    loading,
    error,
    loadStatus,
    send,
  }
}
