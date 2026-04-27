import type { Ref } from 'vue'
import type {
  AbsenceNoticeRequest,
  AdvanceNoticeResponse,
  LateNoticeRequest,
} from '~/types/care'

/**
 * F03.12 §15 事前遅刻・欠席連絡の上位 composable（骨格）。
 *
 * <p>足軽 A はシグネチャと state を確定するのみ。中身は足軽 C が実装する。</p>
 */
export function useAdvanceNotice(teamId: Ref<number>, eventId: Ref<number>) {
  const notices = ref<AdvanceNoticeResponse[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 事前通知一覧を読み込む。 */
  async function loadAdvanceNotices(): Promise<void> {
    void teamId
    void eventId
    /* TODO 足軽C: useAdvanceNoticeApi().getAdvanceNotices() を呼び notices を更新 */
  }

  /** 事前遅刻連絡を送信する。 */
  async function submitLate(body: LateNoticeRequest): Promise<AdvanceNoticeResponse | null> {
    void body
    /* TODO 足軽C: API 呼び出し + オフライン時は useOfflineCareQueue を使う */
    return null
  }

  /** 事前欠席連絡を送信する。 */
  async function submitAbsence(
    body: AbsenceNoticeRequest,
  ): Promise<AdvanceNoticeResponse | null> {
    void body
    /* TODO 足軽C: API 呼び出し + オフライン時は useOfflineCareQueue を使う */
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
