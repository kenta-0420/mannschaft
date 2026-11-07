import { describe, expect, it } from 'vitest'
import { createLatestGuard } from '~/utils/latestRequest'

/**
 * 最新の要求の応答だけを反映する世代ガード。
 *   LR-01 新しい要求を始めると、古い要求の判定は false になる
 *   LR-02 最新の要求の判定は true のまま
 */
describe('createLatestGuard', () => {
  it('LR-01: 新しい要求を始めると、古い要求の判定は false になる', () => {
    const guard = createLatestGuard()
    const first = guard.next()
    expect(first()).toBe(true)
    guard.next()
    expect(first()).toBe(false)
  })

  it('LR-02: 最新の要求の判定は true のまま', () => {
    const guard = createLatestGuard()
    guard.next()
    const second = guard.next()
    expect(second()).toBe(true)
  })
})
