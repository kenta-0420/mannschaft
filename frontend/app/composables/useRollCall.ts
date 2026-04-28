import type { Ref } from 'vue'
import type {
  AbsenceReason,
  RollCallCandidate,
  RollCallEntry,
  RollCallEntryPatchRequest,
  RollCallSessionRequest,
  RollCallSessionResponse,
  RollCallStatus,
} from '~/types/care'
import { useOfflineCareQueue } from '~/composables/jobs/useOfflineCareQueue'

/**
 * F03.12 §14 主催者点呼の上位 composable。
 *
 * <p>足軽 A 作成の骨格（state/シグネチャ）の中身を足軽 B が実装した版。</p>
 *
 * <h3>主な責務</h3>
 * <ul>
 *   <li>点呼候補者の取得 / 結果の一括送信 / 個別修正 / 過去セッション履歴取得</li>
 *   <li>送信時はクライアントで冪等キー（UUID）を発行</li>
 *   <li>オフライン時は {@code useOfflineCareQueue().enqueueCareJob} で IndexedDB に
 *       キューイングし、オンライン復帰時に {@code flushPendingCareJobs} で再送する</li>
 * </ul>
 *
 * <p>{@code submit} は成功時には BE のレスポンスをそのまま返すが、
 * オフライン時は {@code null} を返し {@link offlineQueued} を {@code true} にする。</p>
 */
export function useRollCall(teamId: Ref<number>, eventId: Ref<number>) {
  const api = useRollCallApi()
  const careQueue = useOfflineCareQueue()

  const candidates = ref<RollCallCandidate[]>([])
  const sessionIds = ref<string[]>([])
  const loading = ref(false)
  const submitting = ref(false)
  const error = ref<string | null>(null)
  /** 直近の submit がオフラインキュー行きになったかどうか。 */
  const offlineQueued = ref(false)

  /** 点呼候補者を読み込み {@link candidates} を更新する。 */
  async function loadCandidates(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      candidates.value = await api.getCandidates(teamId.value, eventId.value)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  /**
   * 点呼結果を一括送信する。
   *
   * <p>オンライン: API.submitRollCall を呼び、結果を返す。
   * オフライン: useOfflineCareQueue.enqueueCareJob を呼び、{@code null} を返す。</p>
   *
   * @param entries           送信エントリ
   * @param notifyImmediately ケア対象 PRESENT 時に保護者通知するか
   * @returns 成功時はレスポンス、オフライン時 / 失敗時は null
   */
  async function submit(
    entries: RollCallEntry[],
    notifyImmediately: boolean,
  ): Promise<RollCallSessionResponse | null> {
    submitting.value = true
    error.value = null
    offlineQueued.value = false
    try {
      const body: RollCallSessionRequest = {
        rollCallSessionId: generateSessionId(),
        entries,
        notifyGuardiansImmediately: notifyImmediately,
      }

      if (!isOnline()) {
        await careQueue.enqueueCareJob({
          type: 'ROLL_CALL',
          teamId: teamId.value,
          eventId: eventId.value,
          payload: body,
        })
        offlineQueued.value = true
        showToast({
          severity: 'info',
          summary: 'オフライン保存',
          detail: 'オフライン保存（同期待ち）',
          life: 4000,
        })
        return null
      }

      const res = await api.submitRollCall(teamId.value, eventId.value, body)
      return res
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      submitting.value = false
    }
  }

  /** 過去の点呼セッション ID 一覧を読み込む（履歴ドロワー用）。 */
  async function loadSessions(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      sessionIds.value = await api.getSessions(teamId.value, eventId.value)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  /**
   * 点呼結果を個別修正する。
   *
   * @param userId         修正対象ユーザーID
   * @param status         新しい出欠ステータス
   * @param lateMinutes    LATE 時の遅刻分数（任意）
   * @param absenceReason  ABSENT 時の理由（任意）
   */
  async function patchEntry(
    userId: number,
    status: RollCallStatus,
    lateMinutes?: number,
    absenceReason?: AbsenceReason,
  ): Promise<void> {
    submitting.value = true
    error.value = null
    try {
      const body: RollCallEntryPatchRequest = {
        userId,
        status,
        lateArrivalMinutes: lateMinutes,
        absenceReason,
      }
      await api.patchEntry(teamId.value, eventId.value, userId, body)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      submitting.value = false
    }
  }

  return {
    candidates,
    sessionIds,
    loading,
    submitting,
    error,
    offlineQueued,
    loadCandidates,
    submit,
    loadSessions,
    patchEntry,
  }
}

// ============================================================
// 内部ヘルパ
// ============================================================

/**
 * UUID v4 を生成する。
 *
 * <p>{@code crypto.randomUUID} があればそれを使い、
 * 無い環境（古い iOS Safari 等）はフォールバックを返す。</p>
 */
function generateSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // フォールバック: 簡易 UUID v4 (RFC4122 準拠ではないが冪等キーとしては十分)
  const rnd = () => Math.floor(Math.random() * 0xffff).toString(16).padStart(4, '0')
  return `${rnd()}${rnd()}-${rnd()}-4${rnd().slice(1)}-${((Math.random() * 0x4) | 0x8).toString(16)}${rnd().slice(1)}-${rnd()}${rnd()}${rnd()}`
}

/**
 * ネットワークオンライン判定。
 *
 * <p>SSR 中（{@code navigator} 未定義）はオンラインとみなす。
 * ブラウザでは {@code navigator.onLine} を信頼する（F11.1 / F13.1.2 と整合）。</p>
 */
function isOnline(): boolean {
  if (typeof navigator === 'undefined') return true
  return navigator.onLine
}

/**
 * Toast を出す。{@code $toast} プラグインが無い環境では何もしない。
 */
function showToast(opts: {
  severity: 'success' | 'info' | 'warn' | 'error'
  summary: string
  detail: string
  life?: number
}): void {
  try {
    const nuxt = useNuxtApp()
    const toast = nuxt.$toast as
      | { add: (opts: Record<string, unknown>) => void }
      | undefined
    toast?.add({ ...opts })
  } catch {
    // SSR / テスト環境などで useNuxtApp が呼べない場合は黙ってスキップ
  }
}
