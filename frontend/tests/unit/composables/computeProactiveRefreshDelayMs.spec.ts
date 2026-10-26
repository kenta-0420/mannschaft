import { describe, it, expect } from 'vitest'

/**
 * computeProactiveRefreshDelayMs（useApi.ts）のユニットテスト。
 *
 * access_token（15分）が失効してから反応する現状（リアクティブ）だと、背景の4ポーラー
 * （通知unread-count/chat channels/mentions/inbox summary）が失効直後に必ず1回401を出し、
 * コンソールが401ノイズで汚れる。失効の少し前（バッファ）に先回りリフレッシュを発火させるための
 * 「あと何 ms で発火すべきか」を計算する純粋関数の境界値テスト。
 *
 * AC-4: 武装時に tokenExpiresAt が既に過去/バッファ以内なら、負の遅延にせず即時（遅延0）でリフレッシュする。
 */
const { computeProactiveRefreshDelayMs } = await import('~/composables/useApi')

describe('computeProactiveRefreshDelayMs', () => {
  it('失効時刻がバッファより十分先の場合、失効時刻からバッファを引いた遅延を返す', () => {
    const nowMs = 1_000_000
    const bufferMs = 60_000
    const expiresAtMs = nowMs + 15 * 60_000 // 15分後に失効

    const delay = computeProactiveRefreshDelayMs(expiresAtMs, nowMs, bufferMs)

    expect(delay).toBe(15 * 60_000 - bufferMs)
  })

  it('失効時刻がちょうどバッファ分先の場合、遅延0（即時）を返す', () => {
    const nowMs = 1_000_000
    const bufferMs = 60_000
    const expiresAtMs = nowMs + bufferMs

    const delay = computeProactiveRefreshDelayMs(expiresAtMs, nowMs, bufferMs)

    expect(delay).toBe(0)
  })

  it('AC-4: 失効時刻が既に過去の場合、負の遅延にせず0（即時）を返す', () => {
    const nowMs = 1_000_000
    const bufferMs = 60_000
    const expiresAtMs = nowMs - 10_000 // 10秒前に失効済み

    const delay = computeProactiveRefreshDelayMs(expiresAtMs, nowMs, bufferMs)

    expect(delay).toBe(0)
  })

  it('AC-4: 失効時刻がバッファ以内（間もなく失効）の場合、負の遅延にせず0（即時）を返す', () => {
    const nowMs = 1_000_000
    const bufferMs = 60_000
    const expiresAtMs = nowMs + 10_000 // あと10秒で失効（バッファ60秒以内）

    const delay = computeProactiveRefreshDelayMs(expiresAtMs, nowMs, bufferMs)

    expect(delay).toBe(0)
  })

  it('expiresAtMs が 0（期限不明）の場合、負の遅延にせず0（即時）を返す', () => {
    const nowMs = 1_000_000
    const bufferMs = 60_000

    const delay = computeProactiveRefreshDelayMs(0, nowMs, bufferMs)

    expect(delay).toBe(0)
  })
})
