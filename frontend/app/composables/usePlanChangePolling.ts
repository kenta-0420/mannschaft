/**
 * Billing Center PR6b-1 AC-135: プラン変更の状態確認ポーリング。
 *
 * <p>`GET .../payment-action` は都度 Stripe を叩く（AC-54/147）ため、FE 側のポーリングに
 * 間隔・回数上限が無いと無制限に Stripe を叩き続ける事故になる。本 composable は
 * 「間隔（ミリ秒）」と「最大試行回数」を既定値として持ち、上限に達したら必ず打ち切る。</p>
 *
 * <p>確定判定（poll コールバックの `done`）自体は呼び出し側の責務とする（本 composable は
 * 汎用のポーリング枠だけを提供し、Stripe/BE 呼び出しの中身には関与しない）。</p>
 */

/** ポーリング1回の結果。`done=true` で打ち切る。 */
export interface PlanChangePollResult {
  done: boolean
}

export interface PlanChangePollingOptions {
  /** ポーリング間隔（ミリ秒）。既定 3000ms。 */
  intervalMs?: number
  /** 最大試行回数。既定 20 回（間隔3秒 × 20 = 最大1分）。 */
  maxAttempts?: number
}

/** 既定のポーリング間隔（ミリ秒）。 */
const DEFAULT_INTERVAL_MS = 3000
/** 既定の最大試行回数。無限ポーリング防止のため必ず有限値にする。 */
const DEFAULT_MAX_ATTEMPTS = 20

export function usePlanChangePolling(options: PlanChangePollingOptions = {}) {
  const intervalMs = options.intervalMs ?? DEFAULT_INTERVAL_MS
  const maxAttempts = options.maxAttempts ?? DEFAULT_MAX_ATTEMPTS

  /**
   * `poll` を最大 `maxAttempts` 回まで呼び出す。`poll` が `{done:true}` を返すか
   * 上限に達すると停止する（無限ポーリング防止）。
   */
  async function start(poll: () => Promise<PlanChangePollResult>): Promise<void> {
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      const result = await poll()
      if (result.done) return
      const isLastAttempt = attempt === maxAttempts - 1
      if (!isLastAttempt && intervalMs > 0) {
        await new Promise<void>(resolve => setTimeout(resolve, intervalMs))
      }
    }
  }

  return {
    intervalMs,
    maxAttempts,
    start,
  }
}
