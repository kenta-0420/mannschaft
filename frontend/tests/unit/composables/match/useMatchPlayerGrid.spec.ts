import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'
import type { PlayerAppearanceResponse } from '~/types/match'
import type { GridPlayer } from '~/composables/match/useMatchPlayerGrid'

/**
 * F08.10 useMatchPlayerGrid ユニットテスト（3 段フォールバック・全員先発・前回先発コピー）。
 *
 * 観点:
 *   GRID-001: 段 1 roster 優先（roster があればメンバー API を叩かない）
 *   GRID-002: 段 2 メンバー一覧（roster 無し）
 *   GRID-003: 段 3 手入力追加
 *   GRID-004: 全員先発ワンタップ / クリア
 *   GRID-005: 前回先発コピー（userId 一致＋未登録は player_name 一致）
 *   GRID-006: normalizePosition（細分→大分類）
 */

const getMembers = vi.fn()
// useTeamMembers は明示 import（サブディレクトリで auto-import 非対象）なのでモジュールをモックする。
vi.mock('~/composables/team/useTeamMembers', () => ({
  useTeamMembers: () => ({ getMembers }),
}))

// eslint-disable-next-line import/first
import { useMatchPlayerGrid, normalizePosition } from '~/composables/match/useMatchPlayerGrid'

beforeEach(() => {
  getMembers.mockReset()
})

describe('normalizePosition', () => {
  it('GRID-006: 細分を大分類へ正規化する', () => {
    expect(normalizePosition('GK')).toBe('GK')
    expect(normalizePosition('CB')).toBe('DF')
    expect(normalizePosition('DMF')).toBe('MF')
    expect(normalizePosition('CF')).toBe('FW')
    expect(normalizePosition('WG')).toBe('FW')
    expect(normalizePosition('??')).toBeNull()
  })
})

describe('useMatchPlayerGrid', () => {
  it('GRID-001: roster があればメンバー API を叩かず roster を使う', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const grid = useMatchPlayerGrid()
      const roster: GridPlayer[] = [
        { userId: 1, name: 'A', jerseyNumber: 1, position: 'GK', starter: true },
      ]
      await grid.loadPlayers('10', roster)
      expect(getMembers).not.toHaveBeenCalled()
      expect(grid.source.value).toBe('roster')
      expect(grid.players.value.length).toBe(1)
    })
    scope.stop()
  })

  it('GRID-002: roster 無しならメンバー一覧をロードする', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      getMembers.mockResolvedValueOnce({
        data: [
          { userId: 11, displayName: '田中' },
          { userId: 12, displayName: '佐藤' },
        ],
      })
      const grid = useMatchPlayerGrid()
      await grid.loadPlayers('10')
      expect(getMembers).toHaveBeenCalledWith('10', { page: 0, size: 100 })
      expect(grid.source.value).toBe('members')
      expect(grid.players.value.map((p) => p.name)).toEqual(['田中', '佐藤'])
      expect(grid.players.value.every((p) => p.starter === false)).toBe(true)
    })
    scope.stop()
  })

  it('GRID-003: 手入力追加', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      getMembers.mockResolvedValueOnce({ data: [] })
      const grid = useMatchPlayerGrid()
      await grid.loadPlayers('10')
      expect(grid.source.value).toBe('empty')
      const added = grid.addManualPlayer('ゲスト', 99)
      expect(added).not.toBeNull()
      expect(grid.players.value.length).toBe(1)
      expect(grid.players.value[0]).toMatchObject({ userId: null, name: 'ゲスト', jerseyNumber: 99 })
      // 空名は追加しない
      expect(grid.addManualPlayer('   ')).toBeNull()
      expect(grid.players.value.length).toBe(1)
    })
    scope.stop()
  })

  it('GRID-004: 全員先発 / クリア', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      getMembers.mockResolvedValueOnce({
        data: [{ userId: 1, displayName: 'A' }, { userId: 2, displayName: 'B' }],
      })
      const grid = useMatchPlayerGrid()
      await grid.loadPlayers('10')
      grid.setAllStarters()
      expect(grid.starters.value.length).toBe(2)
      expect(grid.bench.value.length).toBe(0)
      grid.clearStarters()
      expect(grid.starters.value.length).toBe(0)
    })
    scope.stop()
  })

  it('GRID-005: 前回先発コピー（userId 一致＋未登録 player_name 一致）', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      getMembers.mockResolvedValueOnce({
        data: [{ userId: 1, displayName: 'A' }, { userId: 2, displayName: 'B' }],
      })
      const grid = useMatchPlayerGrid()
      await grid.loadPlayers('10')
      // 手入力の未登録選手も追加。
      grid.addManualPlayer('ゲストX')

      const prev: PlayerAppearanceResponse[] = [
        { playerUserId: 1, starter: true, position: 'CB', jerseyNumber: 4 } as PlayerAppearanceResponse,
        { playerUserId: null as unknown as number, playerName: 'ゲストX', starter: true } as PlayerAppearanceResponse,
        { playerUserId: 99, playerName: '前回限り', starter: true } as PlayerAppearanceResponse,
        { playerUserId: 2, starter: false } as PlayerAppearanceResponse, // 控えはコピー対象外
      ]
      grid.copyPreviousStarters(prev)

      const a = grid.players.value.find((p) => p.userId === 1)
      expect(a?.starter).toBe(true)
      expect(a?.position).toBe('DF') // CB→DF 正規化
      expect(a?.jerseyNumber).toBe(4)

      const guest = grid.players.value.find((p) => p.name === 'ゲストX')
      expect(guest?.starter).toBe(true)

      // 候補に居なかった前回限りの選手は手入力選手として追加される。
      const onceOnly = grid.players.value.find((p) => p.userId === 99)
      expect(onceOnly?.starter).toBe(true)

      // 控えは starter にならない。
      const b = grid.players.value.find((p) => p.userId === 2)
      expect(b?.starter).toBe(false)
    })
    scope.stop()
  })
})
