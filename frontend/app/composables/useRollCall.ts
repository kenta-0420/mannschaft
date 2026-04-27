import type { Ref } from 'vue'
import type {
  RollCallCandidate,
  RollCallEntry,
  RollCallEntryPatchRequest,
  RollCallSessionResponse,
} from '~/types/care'

/**
 * F03.12 §14 主催者点呼の上位 composable（骨格）。
 *
 * <p>足軽 A はシグネチャと state を確定するのみ。
 * 個々のメソッドの中身は足軽 B が UI と合わせて実装する。</p>
 *
 * <p>使い方（想定）:</p>
 * <pre>
 *   const teamId = ref(123)
 *   const eventId = ref(456)
 *   const { candidates, loading, error, loadCandidates, submit, patch } =
 *     useRollCall(teamId, eventId)
 *   await loadCandidates()
 *   const result = await submit([{ userId: 1, status: 'PRESENT' }], true)
 * </pre>
 */
export function useRollCall(teamId: Ref<number>, eventId: Ref<number>) {
  const candidates = ref<RollCallCandidate[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 点呼候補者を読み込み {@link candidates} を更新する。中身は足軽 B が実装。 */
  async function loadCandidates(): Promise<void> {
    void teamId
    void eventId
    /* TODO 足軽B: useRollCallApi().getCandidates() を呼び candidates / loading / error を更新する */
  }

  /**
   * 点呼結果を一括送信する。中身は足軽 B が実装。
   *
   * @param entries           送信エントリ
   * @param notifyImmediately ケア対象 PRESENT 時に保護者通知するか
   * @returns 成功時はレスポンス、失敗時は null
   */
  async function submit(
    entries: RollCallEntry[],
    notifyImmediately: boolean,
  ): Promise<RollCallSessionResponse | null> {
    void entries
    void notifyImmediately
    /* TODO 足軽B: 冪等キー(UUID)を生成し submitRollCall を呼び出す。
       オフライン時は useOfflineCareQueue.enqueueCareJob を使うこと */
    return null
  }

  /** 点呼結果を個別修正する。中身は足軽 B が実装。 */
  async function patch(userId: number, body: RollCallEntryPatchRequest): Promise<void> {
    void userId
    void body
    /* TODO 足軽B: useRollCallApi().patchEntry() を呼ぶ */
  }

  return {
    candidates,
    loading,
    error,
    loadCandidates,
    submit,
    patch,
  }
}
