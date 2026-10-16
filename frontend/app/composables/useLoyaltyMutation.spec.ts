// @vitest-environment node
import { describe, expect, it, vi } from 'vitest'
import { ref, shallowRef } from 'vue'
import { useLoyaltyMutation } from './useLoyaltyMutation'
vi.stubGlobal('ref', ref)
vi.stubGlobal('shallowRef', shallowRef)

describe('ポイント確定再送（AC07/12/27）', () => {
  it('結果不明では同じbody/keyを保持し、新しい読取から別操作を開始しない', async () => {
    const operation = vi.fn().mockRejectedValueOnce({ statusCode: 504 }).mockResolvedValueOnce({ id: 'receipt' })
    const mutation = useLoyaltyMutation<{ id: string }>()
    const body = { qrToken: 'original', expectedRuleVersion: 1 }
    await expect(mutation.start(body, operation)).rejects.toEqual({ statusCode: 504 })
    body.qrToken = 'rescan'
    expect(mutation.uncertain.value).toBe(true)
    await mutation.start(body, operation)
    expect(operation).toHaveBeenCalledTimes(1)
    await mutation.retry()
    expect(operation.mock.calls[1]).toEqual(operation.mock.calls[0])
    expect(operation.mock.calls[1]?.[0]).toEqual({ qrToken: 'original', expectedRuleVersion: 1 })
    expect(mutation.result.value).toEqual({ id: 'receipt' })
  })
  it('確定中の連打を抑止し、成功後のretryは追加処理を送らない', async () => {
    let resolve!: (result: string) => void
    const operation = vi.fn(() => new Promise<string>(done => { resolve = done }))
    const mutation = useLoyaltyMutation<string>()
    const first = mutation.start({ ruleId: 'daily' }, operation)
    await mutation.start({ ruleId: 'daily' }, operation)
    expect(operation).toHaveBeenCalledTimes(1)
    resolve('ok'); await first; await mutation.retry()
    expect(operation).toHaveBeenCalledTimes(1)
  })
})
