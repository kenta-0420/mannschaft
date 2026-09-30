import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ABSENCE_REASONS } from '~/constants/absenceReason'

/**
 * CMP-260930-1532: 朝の点呼の欠席理由 enum を BE の AbsenceReason に揃える。
 * BE（school/entity/AbsenceReason.java）の 8 値が正。FE がこれと食い違うと
 * 提出が必ず 400（COMMON_001）になる。
 */
const BE_ABSENCE_REASONS = [
  'SICK',
  'INJURY',
  'FAMILY_REASON',
  'BEREAVEMENT',
  'INFECTIOUS_DISEASE',
  'MENTAL_HEALTH',
  'OFFICIAL_BUSINESS',
  'OTHER',
]

const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de']

describe('ABSENCE_REASONS', () => {
  it('BE の AbsenceReason 8値と完全一致する', () => {
    expect([...ABSENCE_REASONS].sort()).toEqual([...BE_ABSENCE_REASONS].sort())
    expect(ABSENCE_REASONS).toHaveLength(8)
  })

  it.each(LOCALES)('%s の school.json に全8値のラベルがある', (locale) => {
    const json = JSON.parse(
      readFileSync(resolve(__dirname, `../../../app/locales/${locale}/school.json`), 'utf-8'),
    )
    const labels = json.school.attendance.absenceReason as Record<string, string>
    for (const reason of BE_ABSENCE_REASONS) {
      expect(labels[reason], `${locale}:${reason}`).toBeTruthy()
    }
  })
})
