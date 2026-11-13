import type { DiagnosisResult } from '~/types/ranch'

// 過去の結果は表示できるが、identityが保存されていない結果から新割当はしない。
export function resultHasSavedMapping(result: DiagnosisResult | null): boolean {
  return !!(result?.mappingVersion && result.speciesKey && result.variantKey && result.catalogVersion &&
    result.mappingDescriptionSnapshot && Object.values(result.mappingDescriptionSnapshot).some(value => value.trim().length > 0))
}

export function savedMappingDescription(result: DiagnosisResult | null, locale: string): string | null {
  if (!resultHasSavedMapping(result)) return null
  return result?.mappingDescriptionSnapshot?.[locale] ?? result?.mappingDescriptionSnapshot?.ja ?? null
}
