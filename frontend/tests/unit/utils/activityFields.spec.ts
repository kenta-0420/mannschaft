import { describe, it, expect } from 'vitest'
import type { ActivityTemplateField } from '~/types/activity'
import {
  buildActivityFieldValues,
  canSubmitActivity,
  isActivityFieldFilled,
  parseSelectOptions,
  toYmd,
} from '~/utils/activityFields'

/**
 * activityFields ユニットテスト（活動記録作成の必須検証・値整形ロジック）
 *
 * 検証観点:
 *   ACT-FLD-001: 必須テキスト未入力なら canSubmit=false / 入力で true
 *   ACT-FLD-002: テンプレ未選択・タイトル空・活動日未選なら canSubmit=false
 *   ACT-FLD-003: buildActivityFieldValues は fieldKey をキーに型別整形する
 *   ACT-FLD-004: parseSelectOptions は JSON 配列を {label,value} に展開し不正は空配列
 */

function field(partial: Partial<ActivityTemplateField> & { fieldKey: string; fieldType: ActivityTemplateField['fieldType'] }): ActivityTemplateField {
  return {
    id: 1,
    fieldLabel: partial.fieldKey,
    isRequired: false,
    optionsJson: null,
    placeholder: null,
    unit: null,
    isAggregatable: false,
    sortOrder: 0,
    ...partial,
  }
}

describe('activityFields', () => {
  it('ACT-FLD-001: 必須テキストフィールドの入力有無で判定が変わる', () => {
    const f = field({ fieldKey: 'memo', fieldType: 'TEXT', isRequired: true })
    expect(isActivityFieldFilled(f, '')).toBe(false)
    expect(isActivityFieldFilled(f, '   ')).toBe(false)
    expect(isActivityFieldFilled(f, 'ok')).toBe(true)

    const base = {
      templateId: 5,
      title: 'タイトル',
      activityDate: new Date('2026-07-01'),
      fields: [f],
    }
    expect(canSubmitActivity({ ...base, inputs: { memo: '' } })).toBe(false)
    expect(canSubmitActivity({ ...base, inputs: { memo: 'done' } })).toBe(true)
  })

  it('ACT-FLD-002: テンプレ未選択・タイトル空・活動日未選はいずれも false', () => {
    const fields: ActivityTemplateField[] = []
    expect(canSubmitActivity({ templateId: null, title: 'a', activityDate: new Date(), fields, inputs: {} })).toBe(false)
    expect(canSubmitActivity({ templateId: 1, title: '  ', activityDate: new Date(), fields, inputs: {} })).toBe(false)
    expect(canSubmitActivity({ templateId: 1, title: 'a', activityDate: null, fields, inputs: {} })).toBe(false)
    expect(canSubmitActivity({ templateId: 1, title: 'a', activityDate: new Date(), fields, inputs: {} })).toBe(true)
  })

  it('ACT-FLD-003: buildActivityFieldValues は型別に整形し未入力は送らない', () => {
    const fields = [
      field({ fieldKey: 'text', fieldType: 'TEXT' }),
      field({ fieldKey: 'num', fieldType: 'NUMBER' }),
      field({ fieldKey: 'date', fieldType: 'DATE' }),
      field({ fieldKey: 'flag', fieldType: 'CHECKBOX' }),
      field({ fieldKey: 'empty', fieldType: 'TEXT' }),
    ]
    const inputs = {
      text: '  hello  ',
      num: 12,
      date: new Date(2026, 6, 1), // 2026-07-01 ローカル
      flag: true,
      empty: '   ',
    }
    const out = buildActivityFieldValues(fields, inputs)
    expect(out).toEqual({
      text: 'hello',
      num: 12,
      date: '2026-07-01',
      flag: true,
    })
    expect('empty' in out).toBe(false)
  })

  it('ACT-FLD-004: parseSelectOptions は配列を展開し不正 JSON は空配列', () => {
    expect(parseSelectOptions('["A","B"]')).toEqual([
      { label: 'A', value: 'A' },
      { label: 'B', value: 'B' },
    ])
    expect(parseSelectOptions(null)).toEqual([])
    expect(parseSelectOptions('not-json')).toEqual([])
    expect(parseSelectOptions('{"a":1}')).toEqual([])
  })

  it('toYmd はローカル日付で YYYY-MM-DD を返す', () => {
    expect(toYmd(new Date(2026, 0, 5))).toBe('2026-01-05')
    expect(toYmd(new Date(2026, 11, 31))).toBe('2026-12-31')
  })
})
