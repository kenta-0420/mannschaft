/**
 * F09.17 Phase 11-c-4 — system-admin/advertising/moderation-queue.vue のユニットテスト。
 *
 * <p>Nuxt ページ全体のマウントは依存が広いため、ここでは
 * 主要 composable と type だけ取り込み、フィルタロジック相当を独立検証する。</p>
 */
import { describe, it, expect } from 'vitest'
import type { AdReviewQueueItem } from '~/types/adModeration'

function makeItem(over: Partial<AdReviewQueueItem> = {}): AdReviewQueueItem {
  return {
    campaignId: '01000000-0000-0000-0000-000000000001',
    organizationId: 1,
    advertiserAccountId: 100,
    name: 'sample',
    status: 'REVIEW',
    moderationStatus: 'PENDING',
    autoFlagReason: null,
    createdAt: '2026-05-10T10:00:00Z',
    ...over,
  }
}

type AutoFlagFilter = 'ALL' | 'AUTO_FLAGGED' | 'AUTO_PASSED'
type SortOrder = 'NEWEST' | 'OLDEST'

/**
 * moderation-queue.vue 内で使われるフィルタ+ソートのロジック。
 * テスト容易性のためここに同等関数を抽出して検証する。
 */
function applyFilter(
  items: AdReviewQueueItem[],
  autoFlag: AutoFlagFilter,
  sort: SortOrder,
): AdReviewQueueItem[] {
  const filtered =
    autoFlag === 'ALL'
      ? items
      : items.filter((i) => i.moderationStatus === autoFlag)
  return [...filtered].sort((a, b) => {
    const ta = new Date(a.createdAt).getTime()
    const tb = new Date(b.createdAt).getTime()
    return sort === 'NEWEST' ? tb - ta : ta - tb
  })
}

describe('moderation-queue: フィルタ/ソートロジック', () => {
  const items: AdReviewQueueItem[] = [
    makeItem({
      campaignId: 'a',
      name: 'A',
      moderationStatus: 'PENDING',
      createdAt: '2026-05-10T10:00:00Z',
    }),
    makeItem({
      campaignId: 'b',
      name: 'B',
      moderationStatus: 'AUTO_FLAGGED',
      createdAt: '2026-05-11T10:00:00Z',
    }),
    makeItem({
      campaignId: 'c',
      name: 'C',
      moderationStatus: 'AUTO_PASSED',
      createdAt: '2026-05-12T10:00:00Z',
    }),
    makeItem({
      campaignId: 'd',
      name: 'D',
      moderationStatus: 'AUTO_FLAGGED',
      createdAt: '2026-05-09T10:00:00Z',
    }),
  ]

  it('MQ-FILTER-001: ALL は全件を返す', () => {
    const result = applyFilter(items, 'ALL', 'OLDEST')
    expect(result.map((i) => i.campaignId)).toEqual(['d', 'a', 'b', 'c'])
  })

  it('MQ-FILTER-002: AUTO_FLAGGED のみ抽出する', () => {
    const result = applyFilter(items, 'AUTO_FLAGGED', 'OLDEST')
    expect(result.map((i) => i.campaignId)).toEqual(['d', 'b'])
  })

  it('MQ-FILTER-003: AUTO_PASSED のみ抽出する', () => {
    const result = applyFilter(items, 'AUTO_PASSED', 'OLDEST')
    expect(result.map((i) => i.campaignId)).toEqual(['c'])
  })

  it('MQ-FILTER-004: NEWEST 並びは新しい順', () => {
    const result = applyFilter(items, 'ALL', 'NEWEST')
    expect(result.map((i) => i.campaignId)).toEqual(['c', 'b', 'a', 'd'])
  })

  it('MQ-FILTER-005: OLDEST 並びは古い順', () => {
    const result = applyFilter(items, 'ALL', 'OLDEST')
    expect(result.map((i) => i.campaignId)).toEqual(['d', 'a', 'b', 'c'])
  })

  it('MQ-FILTER-006: フィルタとソートを組み合わせる (AUTO_FLAGGED + NEWEST)', () => {
    const result = applyFilter(items, 'AUTO_FLAGGED', 'NEWEST')
    expect(result.map((i) => i.campaignId)).toEqual(['b', 'd'])
  })

  it('MQ-FILTER-007: 空配列でもエラーにならない', () => {
    const result = applyFilter([], 'AUTO_FLAGGED', 'OLDEST')
    expect(result).toEqual([])
  })
})
