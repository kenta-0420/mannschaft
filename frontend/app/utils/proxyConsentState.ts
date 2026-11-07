import type { ProxyInputConsent } from '~/types/proxy-input'

/** 期限はBEと同じ組合運用の暦日で比較する。有効終了日は当日を含む。 */
export function proxyConsentState(consent: ProxyInputConsent, today: string) {
  if (consent.revokedAt || consent.status === 'REVOKED') return 'revoked'
  if (consent.effectiveUntil < today) return 'expired'
  if (!consent.approvedAt || consent.status === 'PENDING_APPROVAL') return 'pending'
  if (consent.effectiveFrom > today) return 'scheduled'
  return 'active'
}
