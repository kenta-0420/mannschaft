import DOMPurify from 'dompurify'

/**
 * v-html に渡す HTML 文字列をサニタイズする。
 * SSR（サーバー側）では DOM が存在しないため正規表現でフォールバックする。
 * クライアント側では DOMPurify でサニタイズする。
 * SVG を許可する場合は  を指定する。
 */
export function sanitizeHtml(dirty: string, options?: { allowSvg?: boolean }): string {
  // サーバーサイド（SSR）では DOM が存在しないため正規表現でサニタイズ
  if (import.meta.server) {
    return dirty
      .replace(/<script[^<]*(?:(?!</script>)<[^<]*)*</script>/gi, '')
      .replace(/onw+s*=s*"[^"]*"/gi, '')
      .replace(/onw+s*=s*'[^']*'/gi, '')
      .replace(/javascript:/gi, '')
  }
  // クライアントサイドでは DOMPurify を使用
  if (options?.allowSvg) {
    return DOMPurify.sanitize(dirty, { USE_PROFILES: { html: true, svg: true } })
  }
  return DOMPurify.sanitize(dirty)
}
