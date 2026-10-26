import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ABSENCE_REASONS } from '~/constants/absenceReason'
import { readBeAbsenceReasons } from '../helpers/beAbsenceReasons'

/**
 * CMP-260930-1532: 欠席理由 enum の FE/BE 契約テスト。
 * BE の正本（school/entity/AbsenceReason.java）を直接読み、FE の ABSENCE_REASONS と比較する。
 * FE がこれと食い違うと提出が必ず 400（COMMON_001）になる。
 */
const BE_ABSENCE_REASONS = readBeAbsenceReasons()

const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de']

describe('ABSENCE_REASONS', () => {
  it('BE の AbsenceReason.java の enum 定数を1件以上抜き出せる', () => {
    expect(BE_ABSENCE_REASONS.length).toBeGreaterThan(0)
  })

  it('BE の AbsenceReason.java の enum 定数と完全一致する', () => {
    expect([...ABSENCE_REASONS].sort()).toEqual([...BE_ABSENCE_REASONS].sort())
  })

  it.each(LOCALES)('%s の school.json に BE 全値のラベルがある', (locale) => {
    const json = JSON.parse(
      readFileSync(resolve(__dirname, `../../../app/locales/${locale}/school.json`), 'utf-8'),
    )
    const labels = json.school.attendance.absenceReason as Record<string, string>
    const noticeLabels = json.school.familyNotice.reason as Record<string, string>
    for (const reason of BE_ABSENCE_REASONS) {
      expect(labels[reason], `${locale}:absenceReason.${reason}`).toBeTruthy()
      expect(noticeLabels[reason], `${locale}:familyNotice.reason.${reason}`).toBeTruthy()
    }
  })
})
