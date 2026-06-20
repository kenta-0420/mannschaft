import { describe, it, expect } from 'vitest'
import { useReflectionStructuredContent, type JsonNode } from '~/composables/useReflectionStructuredContent'
import { emptyStructuredContent } from '~/types/reflection'

/**
 * F06.5 structured_content ↔ JsonNode 境界変換 UT。
 *
 * 観点:
 *   SC-001: toStructured が JsonNode を UI 構造型へ正規化する（main_theme/sections/free_note）
 *   SC-002: 欠損フィールド・型不正は空文字/空配列で安全に補完する（表示防御・fail-safe）
 *   SC-003: null/非オブジェクト入力は空 structured_content を返す
 *   SC-004: toJsonNode → toStructured のラウンドトリップで内容が保たれる
 */
describe('useReflectionStructuredContent', () => {
  const { toStructured, toJsonNode } = useReflectionStructuredContent()

  it('SC-001: JsonNode を UI 構造型へ正規化する', () => {
    const node: JsonNode = {
      main_theme: '二次関数の最大最小',
      sections: [
        {
          heading: '平方完成',
          subsections: [
            { sub_heading: '基本形への変形', detail: 'y=a(x-p)^2+q', supplement: 'a>0 で下に凸' },
          ],
        },
      ],
      free_note: '所感メモ',
    }
    const result = toStructured(node)
    expect(result.main_theme).toBe('二次関数の最大最小')
    expect(result.sections).toHaveLength(1)
    expect(result.sections[0]!.heading).toBe('平方完成')
    expect(result.sections[0]!.subsections[0]!.detail).toBe('y=a(x-p)^2+q')
    expect(result.free_note).toBe('所感メモ')
  })

  it('SC-002: 欠損・型不正フィールドを空で補完する', () => {
    const node: JsonNode = {
      // main_theme 欠落
      sections: [
        { heading: 123 /* 不正型 */, subsections: 'oops' /* 不正型 */ },
      ],
      // free_note 欠落
    }
    const result = toStructured(node)
    expect(result.main_theme).toBe('')
    expect(result.sections).toHaveLength(1)
    expect(result.sections[0]!.heading).toBe('') // 数値→空文字
    expect(result.sections[0]!.subsections).toEqual([]) // 非配列→空配列
    expect(result.free_note).toBe('')
  })

  it('SC-003: null/非オブジェクトは空 structured_content', () => {
    expect(toStructured(null)).toEqual(emptyStructuredContent())
    expect(toStructured(undefined)).toEqual(emptyStructuredContent())
  })

  it('SC-004: toJsonNode → toStructured のラウンドトリップで保たれる', () => {
    const original = {
      main_theme: 'KPT 振り返り',
      sections: [
        {
          heading: 'Keep',
          subsections: [
            { sub_heading: '良かった点', detail: 'レビューが早い', supplement: '' },
          ],
        },
      ],
      free_note: '来週も継続',
    }
    const roundTripped = toStructured(toJsonNode(original))
    expect(roundTripped).toEqual(original)
  })
})
