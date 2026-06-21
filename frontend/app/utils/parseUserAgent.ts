/**
 * User-Agent 文字列を「ブラウザ」「OS」のラベルへ整形する純関数ユーティリティ。
 *
 * アクティブセッション一覧で `Mozilla/5.0 (Windows NT 10.0; ...)` のような生 UA を
 * そのまま表示すると読みにくいため、ここで OS / ブラウザ名を抽出する。
 *
 * 判定はあくまで簡易（主要ブラウザ・OS のカバー）であり、判定不能な場合は空文字を返す。
 * 呼び出し側（コンポーネント）は空文字の扱い（元 UA へのフォールバック等）を担う。
 */

export interface ParsedUserAgent {
  /** ブラウザ名（判定不能なら空文字） */
  browser: string
  /** OS 名（判定不能なら空文字） */
  os: string
}

/**
 * OS を判定する。
 * 順序が重要: iOS 系（iPhone/iPad/iPod）は "Mac OS X" を含むことがあるため先に判定し、
 * Android は "Linux" を含むため Linux より先に判定する。
 */
function detectOs(ua: string): string {
  if (/iPhone|iPad|iPod/.test(ua)) return 'iOS'
  if (ua.includes('Android')) return 'Android'
  if (ua.includes('Windows NT')) return 'Windows'
  if (ua.includes('Mac OS X')) return 'macOS'
  if (ua.includes('Linux')) return 'Linux'
  return ''
}

/**
 * ブラウザを判定する。
 * 順序が重要: Edge(Edg) / Opera(OPR) は UA に "Chrome" を含むため Chrome より先に判定し、
 * Chrome は "Safari" を含むため Safari より先に判定する。
 */
function detectBrowser(ua: string): string {
  if (ua.includes('Edg')) return 'Edge'
  if (ua.includes('OPR') || ua.includes('Opera')) return 'Opera'
  if (ua.includes('Chrome')) return 'Chrome'
  if (ua.includes('Firefox')) return 'Firefox'
  if (ua.includes('Safari')) return 'Safari'
  return ''
}

export function parseUserAgent(ua: string | null | undefined): ParsedUserAgent {
  if (!ua) return { browser: '', os: '' }
  return {
    browser: detectBrowser(ua),
    os: detectOs(ua),
  }
}
