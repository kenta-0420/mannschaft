// @vitest-environment node
import { describe, expect, it, vi } from 'vitest'
import { captureLoyaltyReceptionToken, takeLoyaltyReceptionToken, safeLoyaltyReportUrl } from './loyaltyReceptionToken'

describe('受付QRの秘密移送（AC17/20）', () => {
  it('fragmentを即座に履歴から消し、メモリから一度だけ取得する', () => {
    const replaceState = vi.fn()
    captureLoyaltyReceptionToken({ pathname: '/my/team-points/receive', search: '', hash: '#locationToken=signed%2Bsecret' }, { state: { position: 1 }, replaceState })
    expect(replaceState).toHaveBeenCalledWith({ position: 1 }, '', '/my/team-points/receive')
    expect(takeLoyaltyReceptionToken()).toBe('signed+secret')
    expect(takeLoyaltyReceptionToken()).toBeNull()
  })
  it('起動中の他API障害でも報告URLにfragmentを含めない', () => {
    expect(safeLoyaltyReportUrl('https://app.test/my/team-points/receive#locationToken=secret')).toBe('https://app.test/my/team-points/receive')
    expect(safeLoyaltyReportUrl('https://app.test/wallet')).toBe('https://app.test/wallet')
  })
})
