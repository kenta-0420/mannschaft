import { describe, expect, it } from 'vitest'
import { activityDisplayFields, activityFieldValues, activityFileIds } from './activityDetail'
import type { ActivityTemplateField } from '~/types/activity'

const field: ActivityTemplateField = {
  id: 1,
  fieldKey: 'score',
  fieldLabel: '得点',
  fieldType: 'NUMBER',
  isRequired: false,
  optionsJson: null,
  placeholder: null,
  unit: null,
  isAggregatable: false,
  sortOrder: 0,
}
describe('活動記録の詳細表示', () => {
  it('0・false・空文字を欠落させず、失われた定義の旧キーも表示する', () => {
    const fields = activityDisplayFields({
      fieldValues: '{"score":0,"oldKey":false,"empty":""}',
      templateFields: [field],
    })
    expect(fields.map(({ label, value }) => ({ label, value }))).toEqual([
      { label: '得点', value: '0' },
      { label: 'oldKey', value: 'false' },
      { label: 'empty', value: '' },
    ])
  })
  it('未知の配列やobjectはJSONとして安全に表示し編集入力に渡さない', () => {
    const record = {
      fieldValues: '{"old":["<script>"],"nested":{"count":2},"score":0}',
      templateFields: [field],
    }
    expect(activityDisplayFields(record).find((item) => item.key === 'old')?.value).toBe(
      '["<script>"]',
    )
    expect(activityFieldValues(record)).toEqual({ score: 0 })
  })
  it('テンプレートなし・項目未設定でも空の詳細として読める', () => {
    expect(activityDisplayFields({ fieldValues: null, templateFields: [] })).toEqual([])
  })
  it('添付JSONから既存storage APIへ渡すIDを読み取る', () => {
    expect(activityFileIds('{"file_ids":[2,5]}')).toEqual([2, 5])
    expect(activityFileIds(null)).toEqual([])
    expect(() => activityFileIds('{"file_ids":["2"]}')).toThrow()
  })
})
