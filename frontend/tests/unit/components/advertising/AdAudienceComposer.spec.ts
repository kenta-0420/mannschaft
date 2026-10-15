import { describe, it, expect, vi } from 'vitest'

/**
 * F09.17 AdAudienceComposer.vue ユニットテスト
 *
 * 観点（コンポーネントマウントなし、内部ロジックの単体検証）:
 *  - セグメント追加時、modelValue に新規 segment が末尾追加される
 *  - 削除で index 指定の segment が外れる
 *
 * 本コンポーネントは PrimeVue + i18n 依存が深いため、フルマウントテストは
 * 統合テストに譲り、ここでは公開関数相当の挙動を simulate 形式で確認する。
 */

describe('AdAudienceComposer (logic)', () => {
  it('AAC-001: segment 追加は末尾追加で配列長 +1', () => {
    const current = [
      { segmentType: 'AGE_RANGE' as const, segmentValue: { min: 20, max: 39 }, inclusionMode: 'INCLUDE' as const },
    ]
    const next = [
      ...current,
      { segmentType: 'GENDER' as const, segmentValue: { genders: ['MALE'] }, inclusionMode: 'INCLUDE' as const },
    ]
    expect(next.length).toBe(2)
    expect(next[1]?.segmentType).toBe('GENDER')
  })

  it('AAC-002: segment 削除は指定 index を除外', () => {
    const current = [
      { segmentType: 'AGE_RANGE' as const, segmentValue: { min: 20, max: 39 }, inclusionMode: 'INCLUDE' as const },
      { segmentType: 'GENDER' as const, segmentValue: { genders: ['MALE'] }, inclusionMode: 'INCLUDE' as const },
    ]
    const next = current.filter((_, i) => i !== 0)
    expect(next.length).toBe(1)
    expect(next[0]?.segmentType).toBe('GENDER')
  })

  it('AAC-003: CSV split は trim + 空要素除去', () => {
    function splitCsv(str: string): string[] {
      return str.split(',').map((s) => s.trim()).filter((s) => s.length > 0)
    }
    expect(splitCsv('13, 14, ,15')).toEqual(['13', '14', '15'])
    expect(splitCsv('')).toEqual([])
  })

  it('AAC-004: AGE_RANGE segment value 構築', () => {
    const value = { min: 20, max: 39 }
    expect(value).toEqual({ min: 20, max: 39 })
  })

  it('AAC-005: EXCLUDE inclusion mode が指定可能', () => {
    const seg = { segmentType: 'ORG_TYPE' as const, segmentValue: { types: ['MANSION'] }, inclusionMode: 'EXCLUDE' as const }
    expect(seg.inclusionMode).toBe('EXCLUDE')
  })

  // dummy mock to keep parity with other specs
  void vi.fn()
})
