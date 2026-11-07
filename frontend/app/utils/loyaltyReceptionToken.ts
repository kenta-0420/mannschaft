let receptionToken: string | null = null
const receptionListeners = new Set<() => void>()

/** 署名値は渡さず、新しい受付入口だけを生存中のページへ通知する。 */
export function subscribeLoyaltyReceptionToken(listener: () => void) {
  receptionListeners.add(listener)
  return () => {
    receptionListeners.delete(listener)
  }
}

/** 固定受付署名はメモリだけに置く。URL履歴へ復元しない。 */
export function captureLoyaltyReceptionToken(
  location: Pick<Location, 'pathname' | 'search' | 'hash'>,
  history: Pick<History, 'replaceState' | 'state'>,
) {
  if (location.pathname !== '/my/team-points/receive') return
  const token = new URLSearchParams(location.hash.replace(/^#/, '')).get('locationToken')
  if (token) receptionToken = token
  if (location.hash)
    history.replaceState(history.state, '', `${location.pathname}${location.search}`)
  if (token) for (const listener of receptionListeners) listener()
}
export function takeLoyaltyReceptionToken() {
  const token = receptionToken
  receptionToken = null
  return token
}
export function safeLoyaltyReportUrl(url: string) {
  return url.replace(/(\/my\/team-points\/receive)(?:\?[^#]*)?#.*$/, '$1')
}
