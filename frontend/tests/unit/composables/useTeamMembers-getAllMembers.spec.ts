import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useTeamMembers.getAllMembers ユニットテスト（CMP-260910-1555 / 検分指摘1 の是正）。
 *
 * 是正前は時給設定画面が `getMembers(slug, { size: 200 })` で先頭ページだけを読み
 * `meta.totalPages` を無視していたため、201 名以上のチームでは 201 人目以降が
 * 画面に現れず時給を登録できなかった。設定されなかったメンバーはシフト公開のたびに
 * 予算消化がスキップされ続けるため、この PR が直そうとしている欠陥がそのまま残っていた。
 *
 * 検証観点:
 *   TM-ALL-001: totalPages が 1 なら追加リクエストを出さない
 *   TM-ALL-002: 複数ページある場合は全ページを取得して連結する（1 人も落とさない）
 *   TM-ALL-003: 各ページ要求に page 番号と size が正しく載る
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useTeamMembers } from '~/composables/team/useTeamMembers'

interface Member { userId: number }

function page(members: Member[], pageNo: number, totalPages: number, size = 200) {
  return {
    data: members,
    meta: { page: pageNo, size, totalElements: totalPages * size, totalPages },
  }
}

function membersOf(from: number, count: number): Member[] {
  return Array.from({ length: count }, (_, i) => ({ userId: from + i }))
}

describe('useTeamMembers.getAllMembers', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('TM-ALL-001: 1 ページしか無ければ追加リクエストを出さない', async () => {
    mockFetch.mockResolvedValueOnce(page(membersOf(1, 3), 0, 1))

    const result = await useTeamMembers().getAllMembers('team-alpha')

    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(result).toHaveLength(3)
  })

  it('TM-ALL-002: 201 名のチームでも全員が返る（先頭ページで打ち切らない）', async () => {
    mockFetch
      .mockResolvedValueOnce(page(membersOf(1, 200), 0, 2))
      .mockResolvedValueOnce(page(membersOf(201, 1), 1, 2))

    const result = await useTeamMembers().getAllMembers('team-alpha')

    expect(mockFetch).toHaveBeenCalledTimes(2)
    expect(result).toHaveLength(201)
    // 201 人目（是正前は画面に現れなかったメンバー）が確実に含まれる
    expect(result.at(-1)!.userId).toBe(201)
  })

  it('TM-ALL-003: 3 ページある場合も全ページを順に要求し、page/size がクエリに載る', async () => {
    mockFetch
      .mockResolvedValueOnce(page(membersOf(1, 200), 0, 3))
      .mockResolvedValueOnce(page(membersOf(201, 200), 1, 3))
      .mockResolvedValueOnce(page(membersOf(401, 5), 2, 3))

    const result = await useTeamMembers().getAllMembers('team-alpha')

    expect(result).toHaveLength(405)
    const urls = mockFetch.mock.calls.map(call => String(call[0]))
    expect(urls).toEqual([
      '/api/v1/teams/team-alpha/members?page=0&size=200',
      '/api/v1/teams/team-alpha/members?page=1&size=200',
      '/api/v1/teams/team-alpha/members?page=2&size=200',
    ])
  })
})
