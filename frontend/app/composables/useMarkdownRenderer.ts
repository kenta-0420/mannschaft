import { marked } from 'marked'
import { sanitizeHtml } from '~/utils/sanitizeHtml'

/**
 * 見出し情報（TOC生成用）
 */
export interface Heading {
  /** slug化したID（例: "はじめに" → "はじめに"、"About" → "about"） */
  id: string
  /** 見出しレベル（1〜6） */
  level: number
  /** 見出しテキスト */
  text: string
}

/**
 * 見出しテキストをID（slug）に変換する。
 * - 英数字は小文字に変換
 * - 日本語文字はそのまま保持
 * - スペースはハイフンに変換
 * - それ以外の特殊文字は除去
 * - 先頭・末尾のハイフンは除去
 */
export function toHeadingSlug(text: string): string {
  return text
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^\w\u3040-\u9fff-]/g, '')
    .replace(/^-+|-+$/g, '')
}

/**
 * HTML文字列に含まれるh1〜h3タグにid属性を付与する。
 * 例: `<h2>About</h2>` → `<h2 id="about">About</h2>`
 */
function addHeadingIds(html: string): string {
  return html.replace(/<(h[1-3])>(.+?)<\/h[1-3]>/gi, (_, tag: string, inner: string) => {
    const plain = inner.replace(/<[^>]+>/g, '')
    const id = toHeadingSlug(plain)
    return `<${tag} id="${id}">${inner}</${tag}>`
  })
}

/**
 * HTML文字列からh1〜h3タグを抽出してHeading配列を返す。
 */
function extractHeadings(html: string): Heading[] {
  const headings: Heading[] = []
  const headingRe = /<h([1-3])[^>]*>(.+?)<\/h[1-3]>/gi
  let match: RegExpExecArray | null

  while ((match = headingRe.exec(html)) !== null) {
    const level = parseInt(match[1]!, 10)
    const inner = match[2]!
    const text = inner.replace(/<[^>]+>/g, '').trim()
    const id = toHeadingSlug(text)
    headings.push({ id, level, text })
  }

  return headings
}

/**
 * Markdown文字列のレンダリングユーティリティ。
 * - `renderMarkdown`: XSS対策済みHTML文字列に変換
 * - `renderMarkdownWithHeadings`: HTML + TOC用見出しリストに変換
 */
export function useMarkdownRenderer() {
  /**
   * Markdown文字列をサニタイズ済みHTML文字列に変換する。
   * h1〜h3見出しにはid属性が付与される。
   */
  function renderMarkdown(markdown: string): string {
    if (!markdown) return ''
    const rawHtml = marked(markdown) as string
    return sanitizeHtml(addHeadingIds(rawHtml))
  }

  /**
   * Markdown文字列をHTML + 見出しリストに変換する（TOC生成用）。
   * 返却されるhtmlはサニタイズ済み。
   * 各見出しには id属性が付与される（例: `<h2 id="about">About</h2>`）。
   */
  function renderMarkdownWithHeadings(markdown: string): { html: string; headings: Heading[] } {
    if (!markdown) return { html: '', headings: [] }

    const rawHtml = marked(markdown) as string
    const htmlWithIds = addHeadingIds(rawHtml)
    const headings = extractHeadings(htmlWithIds)
    const html = sanitizeHtml(htmlWithIds)

    return { html, headings }
  }

  return { renderMarkdown, renderMarkdownWithHeadings }
}
