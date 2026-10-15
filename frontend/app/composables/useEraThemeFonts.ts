/**
 * テーマ別Googleフォントのホワイトリスト。
 * ここに載っていないURLは絶対に読み込まない（セキュリティ）。
 */
const ERA_FONT_WHITELIST: Readonly<Partial<Record<string, string>>> = {
  fc: 'https://fonts.googleapis.com/css2?family=Press+Start+2P&display=swap',
  sfc: 'https://fonts.googleapis.com/css2?family=DotGothic16&display=swap',
} as const

/**
 * テーマ用Googleフォントを動的に読み込むcomposable。
 *
 * セキュリティ要件（設計書 §6.3）:
 * - ERA_FONT_WHITELISTに載っているURLのみ読み込む
 * - 重複読み込みを防止する
 * - referrerpolicy="no-referrer" と crossorigin="anonymous" を必ず付与
 */
export function useEraThemeFonts() {
  function loadFont(theme: string): void {
    // SSR中は実行しない
    if (import.meta.server) return

    const url = ERA_FONT_WHITELIST[theme]
    if (!url) return

    // 重複防止: 既に同じURLのlinkが存在すれば何もしない
    if (document.querySelector(`link[href="${url}"]`)) return

    // Google Fontsへのpreconnect（パフォーマンス最適化）
    const preconnect1 = document.createElement('link')
    preconnect1.rel = 'preconnect'
    preconnect1.href = 'https://fonts.googleapis.com'
    document.head.appendChild(preconnect1)

    const preconnect2 = document.createElement('link')
    preconnect2.rel = 'preconnect'
    preconnect2.href = 'https://fonts.gstatic.com'
    preconnect2.crossOrigin = 'anonymous'
    document.head.appendChild(preconnect2)

    // フォントCSSの読み込み（セキュリティ属性付き）
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.href = url
    link.setAttribute('referrerpolicy', 'no-referrer')
    link.crossOrigin = 'anonymous'
    document.head.appendChild(link)
  }

  return { loadFont }
}
