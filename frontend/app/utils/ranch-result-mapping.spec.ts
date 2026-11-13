import { describe, expect, it } from 'vitest'
import type { DiagnosisResult } from '~/types/ranch'
import { resultHasSavedMapping, savedMappingDescription } from './ranch-result-mapping'

const result: DiagnosisResult = { id: 'saved', method: 'DIAGNOSIS', completedAt: '2026-11-08T00:00:00Z',
  resultSchemaVersion: '2', mappingVersion: 'ranch-mapping-20261108-v1', speciesKey: 'allosaurus',
  variantKey: 'V1', catalogVersion: '20261108', descriptionSnapshot: { ja: '診断' },
  mappingDescriptionSnapshot: { ja: '当時のアロサウルス V1', en: 'Saved Allosaurus V1' } }
describe('保存済み対応snapshot', () => {
  it('閲覧localeで当時の対応説明を選び、現masterを読み直さない', () => {
    expect(resultHasSavedMapping(result)).toBe(true)
    expect(savedMappingDescription(result, 'en')).toBe('Saved Allosaurus V1')
    expect(savedMappingDescription(result, 'de')).toBe('当時のアロサウルス V1')
  })
  it('legacyの欠損identityは割当不可とし既存説明自体を変更しない', () => {
    for (const field of ['mappingVersion', 'speciesKey', 'variantKey', 'catalogVersion', 'mappingDescriptionSnapshot'] as const) {
      const legacy = { ...result, [field]: null }
      expect(resultHasSavedMapping(legacy)).toBe(false)
      expect(savedMappingDescription(legacy, 'ja')).toBeNull()
      expect(legacy.descriptionSnapshot.ja).toBe('診断')
    }
    expect(resultHasSavedMapping(null)).toBe(false)
  })
})
