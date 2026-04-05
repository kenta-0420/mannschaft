import DOMPurify from 'dompurify'

/**
 * v-html に渡す HTML 文字列を DOMPurify でサニタイズする。
 * SVG を許可する場合は `allowSvg: true` を指定する。
 */
export function sanitizeHtml(
  dirty: string,
  options?: { allowSvg?: boolean },
): string {
  if (options?.allowSvg) {
    return DOMPurify.sanitize(dirty, { USE_PROFILES: { html: true, svg: true } })
  }
  return DOMPurify.sanitize(dirty)
}
