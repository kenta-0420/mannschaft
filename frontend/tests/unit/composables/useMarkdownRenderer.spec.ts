import { describe, it, expect, vi } from 'vitest'

/**
 * useMarkdownRenderer のユニットテスト。
 *
 * テストケース:
 * 1. renderMarkdown — `**bold**` が `<strong>bold</strong>` に変換されること
 * 2. renderMarkdown — `<script>alert('xss')</script>` がサニタイズされること（XSSテスト）
 * 3. renderMarkdownWithHeadings — `## About` から `{ id: 'about', level: 2, text: 'About' }` が抽出されること
 * 4. renderMarkdownWithHeadings — 複数見出しが順序通りに返ること
 */

// DOMPurify のモック（jsdom 環境では DOMPurify が動作しないため）
vi.mock('dompurify', () => ({
  default: {
    sanitize: (dirty: string) => {
      // XSSテスト用: <script> タグを除去するシンプルなモック
      return dirty.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
    },
  },
}))

// テスト対象を動的 import（モック設定後に import する必要がある）
const { useMarkdownRenderer, toHeadingSlug } = await import('~/composables/useMarkdownRenderer')

describe('useMarkdownRenderer', () => {
  describe('toHeadingSlug', () => {
    it('英字は小文字に変換される', () => {
      expect(toHeadingSlug('About')).toBe('about')
    })

    it('日本語はそのまま保持される', () => {
      expect(toHeadingSlug('はじめに')).toBe('はじめに')
    })

    it('スペースはハイフンに変換される', () => {
      expect(toHeadingSlug('Hello World')).toBe('hello-world')
    })

    it('特殊文字は除去される', () => {
      expect(toHeadingSlug('Hello! World?')).toBe('hello-world')
    })

    it('先頭・末尾のハイフンは除去される', () => {
      expect(toHeadingSlug('  Hello  ')).toBe('hello')
    })
  })

  describe('renderMarkdown', () => {
    it('**bold** が <strong>bold</strong> に変換されること', () => {
      const { renderMarkdown } = useMarkdownRenderer()
      const result = renderMarkdown('**bold**')
      expect(result).toContain('<strong>bold</strong>')
    })

    it('空文字列を渡すと空文字列が返ること', () => {
      const { renderMarkdown } = useMarkdownRenderer()
      expect(renderMarkdown('')).toBe('')
    })

    it('<script>alert("xss")</script> がサニタイズされること（XSSテスト）', () => {
      const { renderMarkdown } = useMarkdownRenderer()
      const input = `<script>alert('xss')</script>`
      const result = renderMarkdown(input)
      expect(result).not.toContain('<script>')
      expect(result).not.toContain('alert(')
    })

    it('見出しにid属性が付与されること', () => {
      const { renderMarkdown } = useMarkdownRenderer()
      const result = renderMarkdown('## About')
      expect(result).toContain('id="about"')
    })
  })

  describe('renderMarkdownWithHeadings', () => {
    it('`## About` から { id: "about", level: 2, text: "About" } が抽出されること', () => {
      const { renderMarkdownWithHeadings } = useMarkdownRenderer()
      const { html, headings } = renderMarkdownWithHeadings('## About')

      expect(headings).toHaveLength(1)
      expect(headings[0]).toEqual({ id: 'about', level: 2, text: 'About' })
      expect(html).toContain('id="about"')
    })

    it('空文字列を渡すと空のhtmlと空の見出し配列が返ること', () => {
      const { renderMarkdownWithHeadings } = useMarkdownRenderer()
      const { html, headings } = renderMarkdownWithHeadings('')
      expect(html).toBe('')
      expect(headings).toHaveLength(0)
    })

    it('複数見出しが順序通りに返ること', () => {
      const { renderMarkdownWithHeadings } = useMarkdownRenderer()
      const markdown = `# はじめに\n\n## About\n\n### 詳細`
      const { headings } = renderMarkdownWithHeadings(markdown)

      expect(headings).toHaveLength(3)
      expect(headings[0]).toEqual({ id: 'はじめに', level: 1, text: 'はじめに' })
      expect(headings[1]).toEqual({ id: 'about', level: 2, text: 'About' })
      expect(headings[2]).toEqual({ id: '詳細', level: 3, text: '詳細' })
    })

    it('h1〜h3のみ抽出され、h4以降は含まれないこと', () => {
      const { renderMarkdownWithHeadings } = useMarkdownRenderer()
      const markdown = `# H1\n\n## H2\n\n### H3\n\n#### H4\n\n##### H5`
      const { headings } = renderMarkdownWithHeadings(markdown)

      // h1〜h3 のみ（h4, h5 は除外）
      expect(headings).toHaveLength(3)
      expect(headings.map((h) => h.level)).toEqual([1, 2, 3])
    })

    it('スペースを含む見出しのidはハイフン区切りになること', () => {
      const { renderMarkdownWithHeadings } = useMarkdownRenderer()
      const { headings } = renderMarkdownWithHeadings('## Hello World')

      expect(headings).toHaveLength(1)
      expect(headings[0]).toEqual({ id: 'hello-world', level: 2, text: 'Hello World' })
    })
  })
})
