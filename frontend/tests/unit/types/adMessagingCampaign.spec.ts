import { describe, it, expect } from 'vitest'
import type {
  AdChannelType,
  AdMessagingCampaignModerationStatus,
  AdMessagingCampaignStatus,
  AdSegmentInclusionMode,
  AdSegmentType,
  EstimatedReachRange,
} from '~/types/adMessagingCampaign'
import type { AdReportReason } from '~/types/adPreferences'
// ロケール JSON は **静的 import** で読む。
// テスト本体で動的 import すると Vite の JSON トランスフォーム時間がテスト時間に算入され、
// 6 ファイル分で既定 5s のテストタイムアウトを超える（タイムアウト値を緩める代わりに、
// 読み込みをモジュール解決フェーズへ移して根治する）。
import adJa from '~/locales/ja/advertising.json'
import adEn from '~/locales/en/advertising.json'
import adZh from '~/locales/zh/advertising.json'
import adKo from '~/locales/ko/advertising.json'
import adDe from '~/locales/de/advertising.json'
import adEs from '~/locales/es/advertising.json'

/**
 * F09.17 型定義スモークテスト。
 *
 * 型は実行時には消えるため、代入できることをコンパイル時に確かめるのが主目的。
 * 値の集合数も合わせて検証し、Backend enum の変更時に検出する。
 */
describe('F09.17 型定義スモーク', () => {
  it('AdMessagingCampaignStatus: 9 種の文字列リテラルを許容する', () => {
    const all: AdMessagingCampaignStatus[] = [
      'DRAFT',
      'REVIEW',
      'APPROVED',
      'SCHEDULED',
      'DELIVERING',
      'PAUSED',
      'COMPLETED',
      'BLOCKED',
      'CANCELLED',
    ]
    expect(all).toHaveLength(9)
    expect(new Set(all).size).toBe(9)
  })

  it('AdMessagingCampaignModerationStatus: 5 種', () => {
    const all: AdMessagingCampaignModerationStatus[] = [
      'PENDING',
      'AUTO_PASSED',
      'AUTO_FLAGGED',
      'APPROVED',
      'BLOCKED',
    ]
    expect(all).toHaveLength(5)
  })

  it('AdChannelType: 4 種', () => {
    const all: AdChannelType[] = ['ANNOUNCEMENT', 'EMAIL', 'PUSH', 'BANNER']
    expect(all).toHaveLength(4)
  })

  it('AdSegmentType: 8 種', () => {
    const all: AdSegmentType[] = [
      'AGE_RANGE',
      'GENDER',
      'REGION_PREFECTURE',
      'REGION_CITY',
      'INTEREST_TAG',
      'ORG_TYPE',
      'LOCALE',
      'DEVICE',
    ]
    expect(all).toHaveLength(8)
  })

  it('AdSegmentInclusionMode: 2 種', () => {
    const all: AdSegmentInclusionMode[] = ['INCLUDE', 'EXCLUDE']
    expect(all).toHaveLength(2)
  })

  it('EstimatedReachRange: 8 種', () => {
    const all: EstimatedReachRange[] = [
      'UNDER_100',
      'RANGE_100_500',
      'RANGE_500_1K',
      'RANGE_1K_5K',
      'RANGE_5K_10K',
      'RANGE_10K_50K',
      'RANGE_50K_100K',
      'OVER_100K',
    ]
    expect(all).toHaveLength(8)
  })

  it('AdReportReason: 5 種（backend AdReportReasonCode と一致）', () => {
    const all: AdReportReason[] = ['OFFENSIVE', 'MISLEADING', 'SPAM', 'IRRELEVANT', 'OTHER']
    expect(all).toHaveLength(5)
  })
})

describe('F09.17 advertising.json ロケール整合性', () => {
  it('6 言語すべてで同一キー数', () => {
    const messages: Record<string, Record<string, unknown>> = {
      ja: adJa,
      en: adEn,
      zh: adZh,
      ko: adKo,
      de: adDe,
      es: adEs,
    }
    const locales = ['ja', 'en', 'zh', 'ko', 'de', 'es'] as const
    const allKeys: Record<string, string[]> = {}

    for (const lang of locales) {
      allKeys[lang] = collectKeys(messages[lang])
    }

    const jaKeys = allKeys.ja ?? []
    expect(jaKeys.length).toBeGreaterThan(50)
    for (const lang of locales) {
      expect(allKeys[lang] ?? [], `locale ${lang} should have same key set as ja`).toEqual(jaKeys)
    }
  })
})

function collectKeys(obj: unknown, prefix = ''): string[] {
  if (obj === null || typeof obj !== 'object') return []
  const keys: string[] = []
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${k}` : k
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
      keys.push(...collectKeys(v, path))
    } else {
      keys.push(path)
    }
  }
  return keys.sort()
}
