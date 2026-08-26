import { describe, it, expect, beforeEach, vi } from 'vitest'
import { effectScope } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.10 採点競技 useMatchScoreEntry ユニットテスト（SCORED・合計点入力 API 配線）。
 *
 * 検証観点:
 *   SCALE-001: toScaledScore — 小数→整数スケール×1000（四捨五入・null/負値ガード）
 *   SCALE-002: fromScaledScore — 整数スケール→小数復元
 *   PAYLOAD-001: buildScoredResultPayload — home/away を ×1000 整数へ・未入力は 0
 *   ENTRY-001: 初期状態 WAITING・start で IN_PROGRESS
 *   ENTRY-002: canSubmit は両合計点入力済み（0 以上）かつ IN_PROGRESS のときのみ true
 *   ENTRY-003: setHomeScore/setAwayScore は負値を null へ丸める
 *   ENTRY-004: 競技ラベル（FIGURE_SKATING/GYMNASTICS）の出し分け
 *   API-001: recordScore は PUT .../scored-result に ×1000 整数 payload を渡す（勝敗は送らない）
 *   API-002: submit 成功で COMPLETED へ遷移し data を返す
 *   API-003: canSubmit 未充足時 submit は null を返し API を呼ばない
 *   API-004: recordScore 失敗時は notification.error を呼び再 throw する
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
  useMatchScoreEntry,
  toScaledScore,
  fromScaledScore,
  buildScoredResultPayload,
  SCORE_SCALE_FACTOR,
} from '~/composables/match/useMatchScoreEntry'

const ORG = 7
const MATCH = 'm-uuid-scored-1'

describe('toScaledScore / fromScaledScore（整数スケール×1000・§4.1）', () => {
  it('SCALE-001a: 198.45 → 198450（フィギュア例）', () => {
    expect(toScaledScore(198.45)).toBe(198450)
  })
  it('SCALE-001b: 85.332 → 85332（体操例・浮動小数誤差を四捨五入で吸収）', () => {
    expect(toScaledScore(85.332)).toBe(85332)
  })
  it('SCALE-001c: null/負値/NaN は null', () => {
    expect(toScaledScore(null)).toBeNull()
    expect(toScaledScore(undefined)).toBeNull()
    expect(toScaledScore(-1)).toBeNull()
    expect(toScaledScore(Number.NaN)).toBeNull()
  })
  it('SCALE-001d: 0 は 0（未入力ではなく明示 0 点）', () => {
    expect(toScaledScore(0)).toBe(0)
  })
  it('SCALE-002a: 198450 → 198.45（復元）', () => {
    expect(fromScaledScore(198450)).toBeCloseTo(198.45, 5)
  })
  it('SCALE-002b: null は null', () => {
    expect(fromScaledScore(null)).toBeNull()
    expect(fromScaledScore(undefined)).toBeNull()
  })
  it('SCALE-000: スケール係数は 1000', () => {
    expect(SCORE_SCALE_FACTOR).toBe(1000)
  })
})

describe('buildScoredResultPayload（送出ペイロード整形）', () => {
  it('PAYLOAD-001a: home/away を ×1000 整数へ変換する', () => {
    expect(buildScoredResultPayload(198.45, 195.3)).toEqual({
      homeScoreScaled: 198450,
      awayScoreScaled: 195300,
    })
  })
  it('PAYLOAD-001b: 未入力（null）は 0 として送る', () => {
    expect(buildScoredResultPayload(null, null)).toEqual({
      homeScoreScaled: 0,
      awayScoreScaled: 0,
    })
  })
})

describe('useMatchScoreEntry（状態・遷移）', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
  })

  it('ENTRY-001: 初期 WAITING・start で IN_PROGRESS', () => {
    const scope = effectScope()
    scope.run(() => {
      const e = useMatchScoreEntry()
      expect(e.entryState.value).toBe('WAITING')
      expect(e.isCompleted.value).toBe(false)
      e.start()
      expect(e.entryState.value).toBe('IN_PROGRESS')
    })
    scope.stop()
  })

  it('ENTRY-002: canSubmit は両合計点入力済み（0以上）かつ IN_PROGRESS のときのみ true', () => {
    const scope = effectScope()
    scope.run(() => {
      const e = useMatchScoreEntry()
      // WAITING: false
      expect(e.canSubmit.value).toBe(false)
      e.start()
      // 片側のみ: false
      e.setHomeScore(198.45)
      expect(e.canSubmit.value).toBe(false)
      // 両側: true
      e.setAwayScore(195.3)
      expect(e.canSubmit.value).toBe(true)
      // 0 点も有効（明示 0）
      e.setAwayScore(0)
      expect(e.canSubmit.value).toBe(true)
    })
    scope.stop()
  })

  it('ENTRY-003: setHomeScore/setAwayScore は負値を null へ丸める', () => {
    const scope = effectScope()
    scope.run(() => {
      const e = useMatchScoreEntry()
      e.start()
      e.setHomeScore(-5)
      expect(e.homeScore.value).toBeNull()
      e.setAwayScore(10)
      expect(e.awayScore.value).toBe(10)
    })
    scope.stop()
  })

  it('ENTRY-004: 競技ラベルの出し分け（FIGURE_SKATING / GYMNASTICS）', () => {
    const scope = effectScope()
    scope.run(() => {
      const fig = useMatchScoreEntry({ sport: 'FIGURE_SKATING' })
      expect(fig.isFigureSkating.value).toBe(true)
      expect(fig.isGymnastics.value).toBe(false)
      const gym = useMatchScoreEntry({ sport: 'GYMNASTICS' })
      expect(gym.isFigureSkating.value).toBe(false)
      expect(gym.isGymnastics.value).toBe(true)
    })
    scope.stop()
  })
})

describe('useMatchScoreEntry（API）', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
  })

  it('API-001: recordScore は PUT .../scored-result に ×1000 整数 payload を渡す（勝敗は送らない）', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: MATCH, homeScore: 198450, awayScore: 195300 } })
    const scope = effectScope()
    await scope.run(async () => {
      const e = useMatchScoreEntry()
      e.start()
      e.setHomeScore(198.45)
      e.setAwayScore(195.3)
      const res = await e.recordScore(ORG, MATCH)
      expect(mockFetch).toHaveBeenCalledWith(
        `/api/v1/organizations/${ORG}/matches/${MATCH}/scored-result`,
        { method: 'PUT', body: { homeScoreScaled: 198450, awayScoreScaled: 195300 } },
      )
      expect(res.homeScore).toBe(198450)
    })
    scope.stop()
  })

  it('API-002: submit 成功で COMPLETED へ遷移し data を返す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: MATCH } })
    const scope = effectScope()
    await scope.run(async () => {
      const e = useMatchScoreEntry()
      e.start()
      e.setHomeScore(100)
      e.setAwayScore(90)
      const res = await e.submit(ORG, MATCH)
      expect(res).not.toBeNull()
      expect(e.entryState.value).toBe('COMPLETED')
      expect(e.isCompleted.value).toBe(true)
    })
    scope.stop()
  })

  it('API-003: canSubmit 未充足時 submit は null を返し API を呼ばない', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const e = useMatchScoreEntry()
      e.start()
      e.setHomeScore(100) // away 未入力
      const res = await e.submit(ORG, MATCH)
      expect(res).toBeNull()
      expect(mockFetch).not.toHaveBeenCalled()
      expect(e.entryState.value).toBe('IN_PROGRESS')
    })
    scope.stop()
  })

  it('API-004: recordScore 失敗時は notification.error を呼び再 throw する', async () => {
    mockFetch.mockRejectedValueOnce(new Error('boom'))
    const scope = effectScope()
    await scope.run(async () => {
      const e = useMatchScoreEntry()
      e.start()
      e.setHomeScore(100)
      e.setAwayScore(90)
      await expect(e.recordScore(ORG, MATCH)).rejects.toThrow('boom')
      expect(mockError).toHaveBeenCalledWith('match.scored.error.record_failed')
    })
    scope.stop()
  })

  it('RESTORE-001: restore は整数スケールから小数へ復元する', () => {
    const scope = effectScope()
    scope.run(() => {
      const e = useMatchScoreEntry()
      e.restore({ state: 'COMPLETED', homeScoreScaled: 198450, awayScoreScaled: 195300 })
      expect(e.entryState.value).toBe('COMPLETED')
      expect(e.homeScore.value).toBeCloseTo(198.45, 5)
      expect(e.awayScore.value).toBeCloseTo(195.3, 5)
    })
    scope.stop()
  })
})
