import type { ApiResponse } from '~/types/api'
import type {
  IssueQrTokenRequest,
  JobCheckInType,
  QrTokenResponse,
} from '~/types/jobmatching'

/**
 * F13.1 QR トークン発行／取得 API クライアント。
 *
 * <p>Backend Controller: {@link com.mannschaft.app.jobmatching.controller.JobQrTokenController}
 * （{@code /api/v1/contracts/{contractId}/qr-tokens} 配下）。</p>
 *
 * <p>Requester 側画面で QR 画像化するための短命トークンを発行・取得する。
 * {@link #startAutoRotation} は expiresAt - 5 秒のタイミングで新規発行を自動ローテーションする
 * 内部制御ヘルパーで、コンポーネント側で {@code onBeforeUnmount} の cleanup と組み合わせて使う。</p>
 */
export function useQrTokenApi() {
  const api = useApi()

  // ============================================================
  // 発行・取得
  // ============================================================

  /**
   * 新規 QR トークンを発行する（Requester 本人のみ）。
   * BE: POST /api/v1/contracts/{contractId}/qr-tokens
   */
  async function issueToken(
    contractId: number,
    type: JobCheckInType,
    ttlSeconds?: number,
  ) {
    const body: IssueQrTokenRequest = { type }
    if (ttlSeconds != null) body.ttlSeconds = ttlSeconds
    return api<ApiResponse<QrTokenResponse>>(
      `/api/v1/contracts/${contractId}/qr-tokens`,
      { method: 'POST', body },
    )
  }

  /**
   * 現在有効な QR トークンを取得する（再表示用）。
   *
   * <p>該当なし時は BE が 204 No Content を返す。その場合 {@code ofetch} は
   * {@code null} を返す挙動のため、本関数もそのまま {@code null} を返す
   * （呼び出し側は {@code null} を「未発行 → 新規 issue が必要」と解釈する）。</p>
   *
   * BE: GET /api/v1/contracts/{contractId}/qr-tokens/current?type=IN|OUT
   */
  async function getCurrentToken(
    contractId: number,
    type: JobCheckInType,
  ): Promise<ApiResponse<QrTokenResponse> | null> {
    const res = await api<ApiResponse<QrTokenResponse> | null>(
      `/api/v1/contracts/${contractId}/qr-tokens/current?type=${encodeURIComponent(type)}`,
    )
    return res ?? null
  }

  // ============================================================
  // ローテーション制御（expiresAt - 5s で再発行）
  // ============================================================

  /** ローテーション用 setTimeout のハンドル。 */
  const rotationTimer = ref<ReturnType<typeof setTimeout> | null>(null)

  /** 最終ローテーションからの経過管理用に、直近発行トークンを保持。 */
  const lastToken = ref<QrTokenResponse | null>(null)

  /**
   * 指定契約の QR トークンを自動ローテーションする。
   *
   * <p>{@code expiresAt - 5s} のタイミングで {@link #issueToken} を呼び、
   * {@code onToken} コールバックを都度発火する。
   * 初回呼び出し時に即時発行してから {@code setTimeout} を仕込む。</p>
   *
   * <p>戻り値の関数は cleanup ハンドル（コンポーネントの {@code onBeforeUnmount}
   * あるいは {@link #stopAutoRotation} で呼ぶ）。</p>
   *
   * @param contractId 対象契約 ID
   * @param type       IN / OUT
   * @param onToken    新トークン取得時の通知コールバック
   * @param onError    エラー発生時の通知コールバック（任意）
   * @param ttlSeconds 発行時 TTL（秒、任意）
   */
  function startAutoRotation(
    contractId: number,
    type: JobCheckInType,
    onToken: (token: QrTokenResponse) => void,
    onError?: (err: unknown) => void,
    ttlSeconds?: number,
  ): () => void {
    let stopped = false

    const rotate = async () => {
      if (stopped) return
      try {
        const res = await issueToken(contractId, type, ttlSeconds)
        if (stopped) return
        lastToken.value = res.data
        onToken(res.data)
        scheduleNext(res.data.expiresAt)
      }
      catch (e) {
        if (onError) onError(e)
        // エラー時も最低限の再試行は行う（expiresAt が無いので 5 秒後にリトライ）。
        if (!stopped) {
          rotationTimer.value = setTimeout(rotate, 5000)
        }
      }
    }

    const scheduleNext = (expiresAt: string) => {
      const expiresMs = new Date(expiresAt).getTime()
      const nowMs = Date.now()
      // expiresAt の 5 秒前に次発行。ただし最低でも 1 秒は待つ。
      const delay = Math.max(1000, expiresMs - nowMs - 5000)
      if (rotationTimer.value !== null) {
        clearTimeout(rotationTimer.value)
      }
      rotationTimer.value = setTimeout(rotate, delay)
    }

    // 初回発行
    void rotate()

    const cleanup = () => {
      stopped = true
      if (rotationTimer.value !== null) {
        clearTimeout(rotationTimer.value)
        rotationTimer.value = null
      }
    }

    // コンポーネント内から呼ばれているなら自動 cleanup（Vue のライフサイクルに紐付く）。
    // 非コンポーネント文脈（テスト等）では getCurrentInstance が null になるため noop。
    if (getCurrentInstance()) {
      onBeforeUnmount(cleanup)
    }

    return cleanup
  }

  /** 明示的にローテーションを停止する。 */
  function stopAutoRotation() {
    if (rotationTimer.value !== null) {
      clearTimeout(rotationTimer.value)
      rotationTimer.value = null
    }
  }

  return {
    issueToken,
    getCurrentToken,
    startAutoRotation,
    stopAutoRotation,
    lastToken: readonly(lastToken),
  }
}
