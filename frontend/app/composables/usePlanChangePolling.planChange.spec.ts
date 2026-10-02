import { describe, expect, it, vi } from 'vitest'

/**
 * 試練D（第5隊）AC-135: FE のポーリングに間隔と回数上限があることを固定する。
 *
 * `useBillingApi.planChange.spec.ts`（AC-133/134）から分離した単独ファイル。
 * `usePlanChangePolling` は PR6b-1 実装前は存在しないため、本ファイル全体が
 * import 解決失敗で red になる（意図した red。他 AC を巻き添えにしない）。
 */

describe('AC-135: FE のポーリングに間隔と回数の上限がある（payment-action は Stripe を都度叩くため）', () => {
  it('usePlanChangePolling が既定の間隔・最大試行回数を公開する', async () => {
    const mod = await import('./usePlanChangePolling')
    expect(typeof mod.usePlanChangePolling).toBe('function')

    const polling = mod.usePlanChangePolling()
    expect(polling.intervalMs).toBeGreaterThan(0)
    expect(polling.maxAttempts).toBeGreaterThan(0)
    expect(Number.isFinite(polling.maxAttempts)).toBe(true)
  })

  it('最大試行回数に到達するとポーリングを打ち切る（無限ポーリング防止）', async () => {
    const mod = await import('./usePlanChangePolling')
    const poll = vi.fn().mockResolvedValue({ done: false })
    const polling = mod.usePlanChangePolling({ intervalMs: 0, maxAttempts: 3 })

    await polling.start(poll)

    expect(poll.mock.calls.length).toBeLessThanOrEqual(3)
    expect(poll.mock.calls.length).toBeGreaterThan(0)
  })
})
