let receptionToken: string | null = null

/** 固定受付署名はメモリだけに置く。URL履歴へ復元しない。 */
export function captureLoyaltyReceptionToken(location: Pick<Location, 'pathname' | 'search' | 'hash'>, history: Pick<History, 'replaceState' | 'state'>) {
  if (location.pathname !== '/my/team-points/receive') return
  const token = new URLSearchParams(location.hash.replace(/^#/, '')).get('locationToken')
  if (token) receptionToken = token
  if (location.hash) history.replaceState(history.state, '', `${location.pathname}${location.search}`)
}
export function takeLoyaltyReceptionToken() {
  const token = receptionToken
  receptionToken = null
  return token
}
export function safeLoyaltyReportUrl(url: string) {
  return url.replace(/(\/my\/team-points\/receive)(?:\?[^#]*)?#.*$/, '$1')
}
