import { describe, expect, it } from 'vitest'
import { proxyConsentState } from './proxyConsentState'
import type { ProxyInputConsent } from '~/types/proxy-input'

const consent: ProxyInputConsent = {
  id: 1,
  organizationId: 10,
  subjectUserId: 2,
  proxyUserId: 3,
  consentMethod: 'PAPER_SIGNED',
  status: 'APPROVED',
  approvedAt: '2026-01-01T10:00:00',
  approvedByUserId: 4,
  revokedAt: null,
  revokeMethod: null,
  revokeReason: null,
  effectiveFrom: '2026-01-01',
  effectiveUntil: '2026-12-31',
  scopes: ['SURVEY'],
}

describe('proxyConsentState', () => {
  it('有効期間の両端を含み開始前と終了後を区別する', () => {
    expect(proxyConsentState(consent, '2025-12-31')).toBe('scheduled')
    expect(proxyConsentState(consent, '2026-01-01')).toBe('active')
    expect(proxyConsentState(consent, '2026-12-31')).toBe('active')
    expect(proxyConsentState(consent, '2027-01-01')).toBe('expired')
  })
  it('撤回を期限より優先し未承認を有効と表示しない', () => {
    expect(proxyConsentState({ ...consent, status: 'REVOKED' }, '2027-01-01')).toBe('revoked')
    expect(
      proxyConsentState({ ...consent, status: 'PENDING_APPROVAL', approvedAt: null }, '2026-02-01'),
    ).toBe('pending')
  })
})
