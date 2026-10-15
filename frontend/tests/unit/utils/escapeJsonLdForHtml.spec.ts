import { describe, it, expect } from 'vitest'
import { escapeJsonLdForHtml } from '~/utils/escapeJsonLdForHtml'

/**
 * F21.1 GEO / セキュリティ — escapeJsonLdForHtml のユニットテスト。
 *
 * JSON-LD を <script type="application/ld+json"> に埋め込む際の
 * スクリプトブレイクアウト（XSS）を防ぐエスケープを検証する。
 */
describe('escapeJsonLdForHtml', () => {
  it('閉じスクリプトタグを含む JSON で生の閉じタグが残らない', () => {
    const ld = {
      '@context': 'https://schema.org',
      '@type': 'Organization',
      name: 'evil</script><script>alert(1)</script>',
    }
    const json = JSON.stringify(ld)
    const escaped = escapeJsonLdForHtml(json)

    // 生の閉じスクリプトタグ（小なり記号 + /script）が出力に残らないこと
    expect(escaped).not.toContain('</script>')
    expect(escaped).not.toContain('</')
    // 小なり記号自体が一切残っていないこと
    expect(escaped).not.toContain('<')
  })

  it('小なり記号を Unicode エスケープ \\u003C に置換する', () => {
    expect(escapeJsonLdForHtml('a<b')).toBe('a\\u003Cb')
  })

  it('小なり記号を含まない文字列は変化しない', () => {
    const json = JSON.stringify({ name: 'FC サンプル', url: 'https://example.com' })
    expect(escapeJsonLdForHtml(json)).toBe(json)
  })

  it('エスケープ後も JSON.parse 可能で意味が保たれる', () => {
    const ld = {
      '@context': 'https://schema.org',
      name: '<<not a tag>>',
      description: 'a < b かつ c </script> d',
    }
    const json = JSON.stringify(ld)
    const escaped = escapeJsonLdForHtml(json)

    // 結果が依然として妥当な JSON であること
    const parsed = JSON.parse(escaped) as Record<string, unknown>
    // 小なり記号の Unicode エスケープは JSON パース後に元の文字へ復元される
    expect(parsed).toEqual(ld)
  })

  it('複数の小なり記号をすべて置換する（グローバル置換）', () => {
    const result = escapeJsonLdForHtml('<<<')
    expect(result).toBe('\\u003C\\u003C\\u003C')
  })

  it('空文字列はそのまま返す', () => {
    expect(escapeJsonLdForHtml('')).toBe('')
  })
})
