import { describe, it, expect, beforeEach, vi } from 'vitest'
import { effectScope } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.10 採点競技 useMatchScoreEntries ユニットテスト（SCORED・多人数順位制の出場者エントリ）。
 *
 * 検証観点:
 *   SCALE-001: toScaledTotal — 小数→整数スケール×1000（四捨五入・null/負値ガード）
 *   SCALE-002: fromScaledTotal — 整数スケール→小数復元
 *   PAYLOAD-001: buildScoreEntriesPayload — N 行・total を×1000 整数へ・順位（rank）非送信
 *   PAYLOAD-002: competitor 識別（user/team/name）の振り分けと空名の除外
 *   NAME-001: resolveCompetitorDisplayName — name 優先・user/team フォールバック
 *   ENTRY-001: addEntry/updateEntry/removeEntry/clearEntries（不変更新）
 *   ENTRY-002: canSubmit は 2 行以上・全行 total 入力済み・全行 competitor 識別済みのときのみ true
 *   API-001: save は PUT .../score-entries に N 行 payload（rank 非送信）を渡し ranked を更新する
 *   API-002: load は GET .../score-entries を呼び draft/ranked へ復元する
 *   API-003: save 失敗時は notification.error を呼び再 throw する
 *   RANK-001: 受信した順位（rankPosition）をそのまま表示用に保持する（サーバー算出）
 *   RESTORE-001: restore は整数スケールから小数へ復元する
 */

const mockFetch = vi.fn()
const mockError = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ error: mockError, success: vi.fn(), info: vi.fn(), warn: vi.fn() }),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

// eslint-disable-next-line import/first
import {
  useMatchScoreEntries,
  toScaledTotal,
  fromScaledTotal,
  buildScoreEntriesPayload,
  resolveCompetitorDisplayName,
  SCORE_ENTRY_SCALE_FACTOR,
  type ScoreEntryDraft,
} from '~/composables/match/useMatchScoreEntries'

const ORG = 7
const MATCH = 'm-uuid-scored-ranking-1'

function draft(partial: Partial<ScoreEntryDraft>): ScoreEntryDraft {
  return {
    key: partial.key ?? 'k1',
    competitorUserId: partial.competitorUserId ?? null,
    competitorName: partial.competitorName ?? null,
    competitorTeamId: partial.competitorTeamId ?? null,
    total: partial.total ?? null,
  }
}

describe('toScaledTotal / fromScaledTotal（整数スケール×1000・§4.1）', () => {
  it('SCALE-001a: 198.45 → 198450（フィギュア例）', () => {
    expect(toScaledTotal(198.45)).toBe(198450)
  })
  it('SCALE-001b: 85.332 → 85332（体操例・四捨五入で誤差吸収）', () => {
    expect(toScaledTotal(85.332)).toBe(85332)
  })
  it('SCALE-001c: null/負値/NaN は null', () => {
    expect(toScaledTotal(null)).toBeNull()
    expect(toScaledTotal(undefined)).toBeNull()
    expect(toScaledTotal(-1)).toBeNull()
    expect(toScaledTotal(Number.NaN)).toBeNull()
  })
  it('SCALE-002a: 198450 → 198.45（復元）', () => {
    expect(fromScaledTotal(198450)).toBeCloseTo(198.45, 5)
  })
  it('SCALE-002b: null は null', () => {
    expect(fromScaledTotal(null)).toBeNull()
  })
  it('SCALE-000: スケール係数は 1000', () => {
    expect(SCORE_ENTRY_SCALE_FACTOR).toBe(1000)
  })
})

describe('buildScoreEntriesPayload（送出ペイロード整形・順位非送信）', () => {
  it('PAYLOAD-001: N 行を×1000 整数へ変換し rank を含めない', () => {
    const drafts: ScoreEntryDraft[] = [
      draft({ key: 'a', competitorUserId: 11, total: 198.45 }),
      draft({ key: 'b', competitorUserId: 22, total: 195.3 }),
      draft({ key: 'c', competitorName: '山田 花子', total: 190.0 }),
    ]
    const payload = buildScoreEntriesPayload(drafts)
    expect(payload.entries).toHaveLength(3)
    expect(payload.entries?.[0]).toEqual({ totalScaled: 198450, competitorUserId: 11 })
    expect(payload.entries?.[1]).toEqual({ totalScaled: 195300, competitorUserId: 22 })
    expect(payload.entries?.[2]).toEqual({ totalScaled: 190000, competitorName: '山田 花子' })
    // 順位（rankPosition）はどの行にも含まれない（サーバー算出・マスアサインメント防止）
    for (const e of payload.entries ?? []) {
      expect(e).not.toHaveProperty('rankPosition')
    }
  })
  it('PAYLOAD-002a: 未入力（null total）は 0 として送る', () => {
    const payload = buildScoreEntriesPayload([draft({ competitorUserId: 1, total: null })])
    expect(payload.entries?.[0]?.totalScaled).toBe(0)
  })
  it('PAYLOAD-002b: 空白のみの名前は competitorName を送らない', () => {
    const payload = buildScoreEntriesPayload([
      draft({ competitorTeamId: 100, competitorName: '   ', total: 50 }),
    ])
    expect(payload.entries?.[0]).toEqual({ totalScaled: 50000, competitorTeamId: 100 })
  })
})

describe('resolveCompetitorDisplayName（表示名解決）', () => {
  it('NAME-001a: competitorName を最優先する', () => {
    expect(
      resolveCompetitorDisplayName(
        { competitorName: '佐藤', competitorUserId: 5 },
        (id) => `user-${id}`,
        (id) => `team-${id}`,
      ),
    ).toBe('佐藤')
  })
  it('NAME-001b: 名前無しは user フォールバック', () => {
    expect(
      resolveCompetitorDisplayName(
        { competitorUserId: 5 },
        (id) => `user-${id}`,
        (id) => `team-${id}`,
      ),
    ).toBe('user-5')
  })
  it('NAME-001c: user 無しは team フォールバック', () => {
    expect(
      resolveCompetitorDisplayName(
        { competitorTeamId: 9 },
        (id) => `user-${id}`,
        (id) => `team-${id}`,
      ),
    ).toBe('team-9')
  })
})

describe('useMatchScoreEntries（ローカル draft 操作・canSubmit）', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
  })

  it('ENTRY-001: addEntry/updateEntry/removeEntry/clearEntries（不変更新）', () => {
    const scope = effectScope()
    scope.run(() => {
      const e = useMatchScoreEntries()
      const a = e.addEntry()
      const b = e.addEntry()
      expect(e.drafts.value).toHaveLength(2)
      e.updateEntry(a.key, { competitorUserId: 11, total: 198.45 })
      expect(e.drafts.value.find((d) => d.key === a.key)?.total).toBe(198.45)
      e.removeEntry(b.key)
      expect(e.drafts.value).toHaveLength(1)
      e.clearEntries()
      expect(e.drafts.value).toHaveLength(0)
      expect(e.ranked.value).toHaveLength(0)
    })
    scope.stop()
  })

  it('ENTRY-002: canSubmit は 2 行以上・全行 total かつ competitor 識別済みのときのみ true', () => {
    const scope = effectScope()
    scope.run(() => {
      const e = useMatchScoreEntries()
      const a = e.addEntry()
      // 1 行のみ: false（順位が成立しない）
      e.updateEntry(a.key, { competitorUserId: 1, total: 100 })
      expect(e.canSubmit.value).toBe(false)
      const b = e.addEntry()
      // 2 行目 total 未入力: false
      e.updateEntry(b.key, { competitorUserId: 2 })
      expect(e.canSubmit.value).toBe(false)
      // 2 行目 total 入力: true
      e.updateEntry(b.key, { total: 90 })
      expect(e.canSubmit.value).toBe(true)
      // competitor 識別が無い行があると false
      const c = e.addEntry()
      e.updateEntry(c.key, { total: 80 })
      expect(e.canSubmit.value).toBe(false)
      // 名前を入れれば true
      e.updateEntry(c.key, { competitorName: '山田' })
      expect(e.canSubmit.value).toBe(true)
    })
    scope.stop()
  })

  it('hasEntries: draft か ranked が 1 件以上で true（stale 整合の正本目印）', () => {
    const scope = effectScope()
    scope.run(() => {
      const e = useMatchScoreEntries()
      expect(e.hasEntries.value).toBe(false)
      e.addEntry()
      expect(e.hasEntries.value).toBe(true)
    })
    scope.stop()
  })
})

describe('useMatchScoreEntries（API）', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
  })

  it('API-001 / RANK-001: save は PUT .../score-entries に N 行 payload（rank 非送信）を渡し ranked を順位算出済みで更新する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [
        { id: 'e1', competitorUserId: 11, totalScaled: 198450, rankPosition: 1 },
        { id: 'e2', competitorUserId: 22, totalScaled: 195300, rankPosition: 2 },
      ],
    })
    const scope = effectScope()
    await scope.run(async () => {
      const e = useMatchScoreEntries()
      const a = e.addEntry()
      const b = e.addEntry()
      e.updateEntry(a.key, { competitorUserId: 11, total: 198.45 })
      e.updateEntry(b.key, { competitorUserId: 22, total: 195.3 })
      const res = await e.save(ORG, MATCH)
      expect(mockFetch).toHaveBeenCalledWith(
        `/api/v1/organizations/${ORG}/matches/${MATCH}/score-entries`,
        {
          method: 'PUT',
          body: {
            entries: [
              { totalScaled: 198450, competitorUserId: 11 },
              { totalScaled: 195300, competitorUserId: 22 },
            ],
          },
        },
      )
      // 受信した順位をそのまま表示用に保持（サーバー算出）
      expect(res).toHaveLength(2)
      expect(e.ranked.value[0]?.rankPosition).toBe(1)
      expect(e.ranked.value[1]?.rankPosition).toBe(2)
    })
    scope.stop()
  })

  it('API-002 / RESTORE-001: load は GET を呼び draft/ranked へ復元し小数へ戻す', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [
        { id: 'e1', competitorName: '山田', totalScaled: 198450, rankPosition: 1 },
        { id: 'e2', competitorName: '佐藤', totalScaled: 195300, rankPosition: 2 },
      ],
    })
    const scope = effectScope()
    await scope.run(async () => {
      const e = useMatchScoreEntries()
      await e.load(ORG, MATCH)
      expect(mockFetch).toHaveBeenCalledWith(
        `/api/v1/organizations/${ORG}/matches/${MATCH}/score-entries`,
      )
      expect(e.drafts.value).toHaveLength(2)
      expect(e.drafts.value[0]?.total).toBeCloseTo(198.45, 5)
      expect(e.ranked.value).toHaveLength(2)
    })
    scope.stop()
  })

  it('API-003: save 失敗時は notification.error を呼び再 throw する', async () => {
    mockFetch.mockRejectedValueOnce(new Error('boom'))
    const scope = effectScope()
    await scope.run(async () => {
      const e = useMatchScoreEntries()
      const a = e.addEntry()
      const b = e.addEntry()
      e.updateEntry(a.key, { competitorUserId: 1, total: 10 })
      e.updateEntry(b.key, { competitorUserId: 2, total: 9 })
      await expect(e.save(ORG, MATCH)).rejects.toThrow('boom')
      expect(mockError).toHaveBeenCalledWith('match.scored.ranking.error.save_failed')
      expect(e.saving.value).toBe(false)
    })
    scope.stop()
  })

  it('ENTRY-004: 競技ラベルの出し分け（FIGURE_SKATING / GYMNASTICS）', () => {
    const scope = effectScope()
    scope.run(() => {
      const fig = useMatchScoreEntries({ sport: 'FIGURE_SKATING' })
      expect(fig.isFigureSkating.value).toBe(true)
      const gym = useMatchScoreEntries({ sport: 'GYMNASTICS' })
      expect(gym.isGymnastics.value).toBe(true)
    })
    scope.stop()
  })
})
