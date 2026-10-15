import { describe, it, expect } from 'vitest'
import { z } from 'zod'

/**
 * F09.17 AdMessagingCampaignFormFields.vue ユニットテスト
 *
 * 本コンポーネントは PrimeVue + i18n 依存が深いためフルマウントは統合に譲り、
 * 同コンポーネントが期待する Zod 検証スキーマ（new.vue 側に同居）と等価な
 * ルールの境界値テストを実施する。
 *
 * 検証ルール（設計書 §4 POST /campaigns/messaging に準拠）:
 *  - name: 1〜120 文字、必須
 *  - totalBudgetYen: 1,000 以上 100,000,000 以下
 *  - starts_at < ends_at
 *  - frequencyCapOverride: NULL または 1〜30
 */

const schema = z
  .object({
    name: z.string().min(1).max(120),
    totalBudgetYen: z.number().int().min(1000).max(100_000_000),
    startsAt: z.string().min(1),
    endsAt: z.string().min(1),
    scheduledTimezone: z.string().min(1),
    frequencyCapOverride: z.number().int().min(1).max(30).nullable().optional(),
  })
  .refine((d) => d.startsAt < d.endsAt, { path: ['startsAt'], message: 'starts_before_ends' })

const VALID = {
  name: 'test',
  totalBudgetYen: 10000,
  startsAt: '2026-06-01T00:00:00',
  endsAt: '2026-06-30T00:00:00',
  scheduledTimezone: 'Asia/Tokyo',
  frequencyCapOverride: null,
}

describe('AdMessagingCampaignFormFields validation schema', () => {
  it('FORM-001: 正常系は success', () => {
    const r = schema.safeParse(VALID)
    expect(r.success).toBe(true)
  })

  it('FORM-002: name 必須', () => {
    const r = schema.safeParse({ ...VALID, name: '' })
    expect(r.success).toBe(false)
  })

  it('FORM-003: name 120文字上限', () => {
    const r = schema.safeParse({ ...VALID, name: 'x'.repeat(121) })
    expect(r.success).toBe(false)
  })

  it('FORM-004: totalBudgetYen 1000未満エラー', () => {
    const r = schema.safeParse({ ...VALID, totalBudgetYen: 999 })
    expect(r.success).toBe(false)
  })

  it('FORM-005: totalBudgetYen 上限超過エラー', () => {
    const r = schema.safeParse({ ...VALID, totalBudgetYen: 100_000_001 })
    expect(r.success).toBe(false)
  })

  it('FORM-006: starts_at >= ends_at エラー', () => {
    const r = schema.safeParse({ ...VALID, startsAt: '2026-06-30T00:00:00', endsAt: '2026-06-01T00:00:00' })
    expect(r.success).toBe(false)
    if (!r.success) {
      expect(r.error.issues.some((i) => i.path.join('.') === 'startsAt')).toBe(true)
    }
  })

  it('FORM-007: frequencyCapOverride 1〜30 の境界', () => {
    expect(schema.safeParse({ ...VALID, frequencyCapOverride: 0 }).success).toBe(false)
    expect(schema.safeParse({ ...VALID, frequencyCapOverride: 1 }).success).toBe(true)
    expect(schema.safeParse({ ...VALID, frequencyCapOverride: 30 }).success).toBe(true)
    expect(schema.safeParse({ ...VALID, frequencyCapOverride: 31 }).success).toBe(false)
  })

  it('FORM-008: frequencyCapOverride は null 許容', () => {
    expect(schema.safeParse({ ...VALID, frequencyCapOverride: null }).success).toBe(true)
  })
})
