import type { Ref } from 'vue'
import type {
  AbsenceNoticeRequest,
  AdvanceNoticeResponse,
  LateNoticeRequest,
} from '~/types/care'
import { useOfflineCareQueue } from '~/composables/jobs/useOfflineCareQueue'

/**
 * F03.12 §15 事前遅刻・欠席連絡の上位 composable。
 *
 * <p>API 呼び出しと Dexie オフラインキューを束ねるラッパ。
 * オンライン時は {@link useAdvanceNoticeApi} を直接叩き、
 * オフライン時は {@code useOfflineCareQueue().enqueueCareJob} に積み
 * Background Sync で送信する。</p>
 *
 * <p>UI コンポーネント（{@code LateNoticeDialog} / {@code AbsenceNoticeDialog} /
 * {@code LateAbsenceNoticeBar} / {@code AdvanceNoticeList}）から本 composable を経由して
 * 通信を行う。トースト通知は本 composable では行わず、呼び出し側に戻り値で結果を伝える。</p>
 */
export function useAdvanceNotice(teamId: Ref<number>, eventId: Ref<number>) {
  const api = useAdvanceNoticeApi()
  const careQueue = useOfflineCareQueue()
  const online = useOnline()

  const notices = ref<AdvanceNoticeResponse[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 事前通知一覧を読み込む（主催者向け）。 */
  async function loadAdvanceNotices(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      notices.value = await api.getAdvanceNotices(teamId.value, eventId.value)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      notices.value = []
    } finally {
      loading.value = false
    }
  }

  /**
   * 事前遅刻連絡を送信する。
   *
   * <p>オンライン時: API を直接呼び、レスポンスを返す。</p>
   * <p>オフライン時: {@code useOfflineCareQueue} にジョブを積み、
   * {@code null} を返す（=オフラインキュー積み）。呼び出し側は戻り値の null/値で
   * トースト文言（送信完了 vs 同期待ち保存）を出し分ける。</p>
   */
  async function submitLate(
    body: LateNoticeRequest,
  ): Promise<AdvanceNoticeResponse | null> {
    if (online.value) {
      const created = await api.submitLateNotice(teamId.value, eventId.value, body)
      // 一覧 state を読み込んでいる場合は楽観反映
      if (notices.value.length > 0) {
        notices.value = [...notices.value, created]
      }
      return created
    }
    await careQueue.enqueueCareJob({
      type: 'LATE_NOTICE',
      teamId: teamId.value,
      eventId: eventId.value,
      payload: body,
    })
    return null
  }

  /** 事前欠席連絡を送信する。動作は {@link submitLate} と対称。 */
  async function submitAbsence(
    body: AbsenceNoticeRequest,
  ): Promise<AdvanceNoticeResponse | null> {
    if (online.value) {
      const created = await api.submitAbsenceNotice(teamId.value, eventId.value, body)
      if (notices.value.length > 0) {
        notices.value = [...notices.value, created]
      }
      return created
    }
    await careQueue.enqueueCareJob({
      type: 'ABSENCE_NOTICE',
      teamId: teamId.value,
      eventId: eventId.value,
      payload: body,
    })
    return null
  }

  return {
    notices,
    loading,
    error,
    loadAdvanceNotices,
    submitLate,
    submitAbsence,
  }
}
