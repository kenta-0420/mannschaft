import { describe, it, expect, beforeEach, vi } from 'vitest'
import { effectScope } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.10 採点競技 useMatchScoredComponents ユニットテスト
 * （SCORED・審判別/種目別採点内訳・§4B / §8）。
 *
 * 検証観点:
 *   SCALE-001: toScaledPoints — 小数→整数スケール×1000（四捨五入・null/負値ガード）
 *   SCALE-002: fromScaledPoints — 整数スケール→小数復元
 *   PAYLOAD-001: buildScoredComponentsPayload — side/componentType/apparatus/judgeLabel/pointsScaled の全置換整形
 *   PAYLOAD-002: apparatus=null / judgeLabel 空は payload から落とす（任意項目）
 *   SUM-001: sumScaledBySide — side 別の符号付き集計（DEDUCTION 減算）
 *   CAT-001: 競技別カタログ（フィギュア=TES/PCS/DEDUCTION・SP/FS / 体操=D_SCORE/E_SCORE・FLOOR…）
 *   LINE-001: addLine/updateLine/removeLine/clearLines のローカル draft 操作
 *   PREVIEW-001: homeTotal/awayTotal の合計プレビュー（DEDUCTION 減算・側別）
 *   SUBMIT-001: canSubmit は 1 件以上＋全行点数入力済み＋非送信中のときのみ true
 *   STALE-001: hasComponents は内訳 1 件以上で true（内訳が正本の目印）
 *   API-001: save は PUT .../scored-components に全置換 payload を渡し data を返す
 *   API-002: load は GET 内訳を取得し draft へ復元する
 *   API-003: save 失敗時は notification.error を呼び再 throw する
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
  useMatchScoredComponents,
  toScaledPoints,
  fromScaledPoints,
  buildScoredComponentsPayload,
  sumScaledBySide,
  SCORED_COMPONENT_TYPES,
  SCORED_APPARATUSES,
  type ScoredComponentDraft,
} from '~/composables/match/useMatchScoredComponents'

const ORG = 7
const MATCH = 'm-uuid-scored-comp-1'

function draft(p: Partial<ScoredComponentDraft>): ScoredComponentDraft {
  return {
    key: p.key ?? 'k1',
    side: p.side ?? 'HOME',
    componentType: p.componentType ?? 'TES',
    apparatus: p.apparatus ?? null,
    judgeLabel: p.judgeLabel ?? null,
    points: p.points ?? null,
  }
}

describe('toScaledPoints / fromScaledPoints（整数スケール×1000・§4.1）', () => {
  it('SCALE-001a: 88.43 → 88430', () => {
    expect(toScaledPoints(88.43)).toBe(88430)
  })
  it('SCALE-001b: null/負値/NaN は null', () => {
    expect(toScaledPoints(null)).toBeNull()
    expect(toScaledPoints(undefined)).toBeNull()
    expect(toScaledPoints(-1)).toBeNull()
    expect(toScaledPoints(Number.NaN)).toBeNull()
  })
  it('SCALE-002: 88430 → 88.43（復元）', () => {
    expect(fromScaledPoints(88430)).toBeCloseTo(88.43, 5)
    expect(fromScaledPoints(null)).toBeNull()
  })
})

describe('buildScoredComponentsPayload（全置換整形・§4B）', () => {
  it('PAYLOAD-001: side/componentType/apparatus/judgeLabel/pointsScaled を整形（×1000）', () => {
    const payload = buildScoredComponentsPayload([
      draft({ key: 'a', side: 'HOME', componentType: 'TES', apparatus: 'SP', judgeLabel: 'J1', points: 88.43 }),
      draft({ key: 'b', side: 'AWAY', componentType: 'PCS', apparatus: 'FS', points: 70.2 }),
    ])
    expect(payload.components).toEqual([
      { competitorSide: 'HOME', componentType: 'TES', pointsScaled: 88430, apparatus: 'SP', judgeLabel: 'J1' },
      { competitorSide: 'AWAY', componentType: 'PCS', pointsScaled: 70200, apparatus: 'FS' },
    ])
  })
  it('PAYLOAD-002: apparatus=null / judgeLabel 空白は payload から落とす（任意項目）', () => {
    const payload = buildScoredComponentsPayload([
      draft({ key: 'a', side: 'HOME', componentType: 'D_SCORE', apparatus: null, judgeLabel: '  ', points: 6.5 }),
    ])
    expect(payload.components).toEqual([
      { competitorSide: 'HOME', componentType: 'D_SCORE', pointsScaled: 6500 },
    ])
    const line = payload.components?.[0]
    expect(line && 'apparatus' in line).toBe(false)
    expect(line && 'judgeLabel' in line).toBe(false)
  })
  it('PAYLOAD-003: 未入力点数（null）は 0 として送る', () => {
    const payload = buildScoredComponentsPayload([
      draft({ key: 'a', side: 'HOME', componentType: 'E_SCORE', points: null }),
    ])
    expect(payload.components?.[0]?.pointsScaled).toBe(0)
  })
})

describe('sumScaledBySide（符号付き集計・DEDUCTION 減算・§4B.2）', () => {
  it('SUM-001: side 別に加算・DEDUCTION は減算', () => {
    const drafts = [
      draft({ key: 'a', side: 'HOME', componentType: 'TES', points: 88.43 }),
      draft({ key: 'b', side: 'HOME', componentType: 'PCS', points: 70.2 }),
      draft({ key: 'c', side: 'HOME', componentType: 'DEDUCTION', points: 1.0 }),
      draft({ key: 'd', side: 'AWAY', componentType: 'TES', points: 80.0 }),
    ]
    // HOME = 88430 + 70200 - 1000 = 157630
    expect(sumScaledBySide(drafts, 'HOME')).toBe(157630)
    expect(sumScaledBySide(drafts, 'AWAY')).toBe(80000)
  })
})

describe('競技別カタログ（BE ScoredComponentCatalog ミラー・§10）', () => {
  it('CAT-001: フィギュア=TES/PCS/DEDUCTION・SP/FS / 体操=D_SCORE/E_SCORE・FLOOR…', () => {
    expect(SCORED_COMPONENT_TYPES.FIGURE_SKATING).toEqual(['TES', 'PCS', 'DEDUCTION'])
    expect(SCORED_COMPONENT_TYPES.GYMNASTICS).toEqual(['D_SCORE', 'E_SCORE'])
    expect(SCORED_APPARATUSES.FIGURE_SKATING).toEqual(['SP', 'FS'])
    expect(SCORED_APPARATUSES.GYMNASTICS).toContain('FLOOR')
    expect(SCORED_APPARATUSES.GYMNASTICS).toContain('BALANCE_BEAM')
  })
})

describe('useMatchScoredComponents（ローカル draft 操作）', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
  })

  it('LINE-001: addLine/updateLine/removeLine/clearLines', () => {
    const scope = effectScope()
    scope.run(() => {
      const c = useMatchScoredComponents({ sport: 'FIGURE_SKATING' })
      expect(c.drafts.value).toEqual([])
      const added = c.addLine('HOME')
      expect(c.drafts.value.length).toBe(1)
      // 既定項目はカタログ先頭（TES）
      expect(added.componentType).toBe('TES')
      expect(added.side).toBe('HOME')
      c.updateLine(added.key, { componentType: 'PCS', points: 70.2 })
      expect(c.drafts.value[0]?.componentType).toBe('PCS')
      expect(c.drafts.value[0]?.points).toBe(70.2)
      c.addLine('AWAY')
      expect(c.drafts.value.length).toBe(2)
      c.removeLine(added.key)
      expect(c.drafts.value.length).toBe(1)
      c.clearLines()
      expect(c.drafts.value).toEqual([])
    })
    scope.stop()
  })

  it('PREVIEW-001: homeTotal/awayTotal の合計プレビュー（DEDUCTION 減算）', () => {
    const scope = effectScope()
    scope.run(() => {
      const c = useMatchScoredComponents({ sport: 'FIGURE_SKATING' })
      const a = c.addLine('HOME')
      c.updateLine(a.key, { componentType: 'TES', points: 88.43 })
      const b = c.addLine('HOME')
      c.updateLine(b.key, { componentType: 'DEDUCTION', points: 1.0 })
      const d = c.addLine('AWAY')
      c.updateLine(d.key, { componentType: 'TES', points: 80.0 })
      // HOME = 88.43 - 1.0 = 87.43 / AWAY = 80.0
      expect(c.homeTotal.value).toBeCloseTo(87.43, 5)
      expect(c.awayTotal.value).toBeCloseTo(80.0, 5)
      expect(c.homeDrafts.value.length).toBe(2)
      expect(c.awayDrafts.value.length).toBe(1)
    })
    scope.stop()
  })

  it('SUBMIT-001: canSubmit は 1 件以上＋全行点数入力済みのときのみ true', () => {
    const scope = effectScope()
    scope.run(() => {
      const c = useMatchScoredComponents({ sport: 'GYMNASTICS' })
      expect(c.canSubmit.value).toBe(false) // 空
      const a = c.addLine('HOME')
      expect(c.canSubmit.value).toBe(false) // 点数未入力
      c.updateLine(a.key, { points: 6.5 })
      expect(c.canSubmit.value).toBe(true)
      const b = c.addLine('AWAY')
      expect(c.canSubmit.value).toBe(false) // b が未入力
      c.updateLine(b.key, { points: 6.0 })
      expect(c.canSubmit.value).toBe(true)
    })
    scope.stop()
  })

  it('STALE-001: hasComponents は内訳 1 件以上で true（内訳が正本の目印・§4B.2）', () => {
    const scope = effectScope()
    scope.run(() => {
      const c = useMatchScoredComponents({ sport: 'FIGURE_SKATING' })
      expect(c.hasComponents.value).toBe(false)
      c.addLine('HOME')
      expect(c.hasComponents.value).toBe(true)
      c.clearLines()
      expect(c.hasComponents.value).toBe(false)
    })
    scope.stop()
  })
})

describe('useMatchScoredComponents（API）', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
  })

  it('API-001: save は PUT .../scored-components に全置換 payload を渡し data を返す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: MATCH, homeScore: 157630, awayScore: 80000 } })
    const scope = effectScope()
    await scope.run(async () => {
      const c = useMatchScoredComponents({ sport: 'FIGURE_SKATING' })
      const a = c.addLine('HOME')
      c.updateLine(a.key, { componentType: 'TES', apparatus: 'SP', points: 88.43 })
      const res = await c.save(ORG, MATCH)
      expect(mockFetch).toHaveBeenCalledWith(
        `/api/v1/organizations/${ORG}/matches/${MATCH}/scored-components`,
        {
          method: 'PUT',
          body: { components: [{ competitorSide: 'HOME', componentType: 'TES', pointsScaled: 88430, apparatus: 'SP' }] },
        },
      )
      expect(res.homeScore).toBe(157630)
    })
    scope.stop()
  })

  it('API-002: load は GET 内訳を取得し draft へ復元する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [
        { id: 'c1', matchId: MATCH, competitorSide: 'HOME', componentType: 'TES', apparatus: 'SP', judgeLabel: 'J1', pointsScaled: 88430 },
        { id: 'c2', matchId: MATCH, competitorSide: 'AWAY', componentType: 'PCS', pointsScaled: 70200 },
      ],
    })
    const scope = effectScope()
    await scope.run(async () => {
      const c = useMatchScoredComponents({ sport: 'FIGURE_SKATING' })
      const list = await c.load(ORG, MATCH)
      expect(mockFetch).toHaveBeenCalledWith(
        `/api/v1/organizations/${ORG}/matches/${MATCH}/scored-components`,
      )
      expect(list.length).toBe(2)
      expect(c.drafts.value.length).toBe(2)
      expect(c.drafts.value[0]?.side).toBe('HOME')
      expect(c.drafts.value[0]?.componentType).toBe('TES')
      expect(c.drafts.value[0]?.apparatus).toBe('SP')
      expect(c.drafts.value[0]?.points).toBeCloseTo(88.43, 5)
      expect(c.hasComponents.value).toBe(true)
    })
    scope.stop()
  })

  it('API-003: save 失敗時は notification.error を呼び再 throw する', async () => {
    mockFetch.mockRejectedValueOnce(new Error('boom'))
    const scope = effectScope()
    await scope.run(async () => {
      const c = useMatchScoredComponents({ sport: 'GYMNASTICS' })
      const a = c.addLine('HOME')
      c.updateLine(a.key, { points: 6.5 })
      await expect(c.save(ORG, MATCH)).rejects.toThrow('boom')
      expect(mockError).toHaveBeenCalledWith('match.scored.components.error.save_failed')
      // saving フラグは finally で false に戻る
      expect(c.saving.value).toBe(false)
    })
    scope.stop()
  })
})
