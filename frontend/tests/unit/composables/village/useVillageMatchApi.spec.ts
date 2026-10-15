import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F17.1 useVillageMatchApi 契約テスト
 *
 * 目的:
 *   FE の `api<{ data: X }>(...)` は**型アサーション**であり、X が BE の実契約と
 *   食い違っていても TypeScript は信じてしまう。実際に本 composable では
 *     - 一覧が配列だと誤宣言され（実体は `{items, page, size, total}` エンベロープ）、
 *       画面がエンベロープを v-for して `category.undefined` のゴミバッジを描画した
 *     - 応募審査が存在しない `/accept` `/reject` を叩き 404 COMMON_005 になっていた
 *   という 2 件の不一致が実機で露見した。そこで**モックには BE の真の応答形状**を置き、
 *   URL・HTTP メソッド・body の契約を機械的に固定する。
 *
 * 権威:
 *   backend/src/main/java/com/mannschaft/app/village/controller/VillageMatchRecruitController.java
 *   backend/src/main/java/com/mannschaft/app/village/dto/MatchRecruitListResponse.java
 *   backend/src/main/java/com/mannschaft/app/village/dto/MatchApplicationReviewRequest.java
 *
 * 検証観点:
 *   VMATCH-API-001: listMatchRecruits は `{items, page, size, total}` エンベロープを返す（配列ではない）
 *   VMATCH-API-002: listMatchRecruits はフィルタをクエリ文字列に載せる
 *   VMATCH-API-003: reviewApplication は POST .../review に `{status:'ACCEPTED'}` を送る
 *   VMATCH-API-004: reviewApplication は却下時に `{status:'REJECTED'}` を送る
 *   VMATCH-API-005: reviewApplication は reviewComment を送れる
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useVillageMatchApi } from '~/composables/village/useVillageMatchApi'

const VILLAGE = 'v-uuid-1'
const RECRUIT = 'r-uuid-1'
const APPLICATION = 'a-uuid-1'

/** BE `MatchRecruitListResponse` の実形状（Spring の Page 形状ではない独自エンベロープ） */
function listEnvelope(items: unknown[] = []) {
  return { data: { items, page: 0, size: 20, total: items.length } }
}

describe('useVillageMatchApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('VMATCH-API-001: listMatchRecruits は items/page/size/total のエンベロープを返す', async () => {
    const recruit = { id: RECRUIT, category: 'PRACTICE_MATCH', status: 'OPEN' }
    mockFetch.mockResolvedValueOnce(listEnvelope([recruit]))

    const api = useVillageMatchApi()
    const res = await api.listMatchRecruits(VILLAGE)

    expect(mockFetch).toHaveBeenCalledWith(`/api/v1/villages/${VILLAGE}/match-recruits`)
    // 配列ではなくエンベロープ。画面は res.items を走査しなければならない
    expect(Array.isArray(res)).toBe(false)
    expect(res.items).toEqual([recruit])
    expect(res.total).toBe(1)
  })

  it('VMATCH-API-002: listMatchRecruits はフィルタをクエリ文字列に載せる', async () => {
    mockFetch.mockResolvedValueOnce(listEnvelope())

    const api = useVillageMatchApi()
    await api.listMatchRecruits(VILLAGE, { category: 'REFEREE', status: 'OPEN', page: 0, size: 50 })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/villages/${VILLAGE}/match-recruits?category=REFEREE&status=OPEN&page=0&size=50`,
    )
  })

  it('VMATCH-API-003: reviewApplication は POST .../review に status=ACCEPTED を送る', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: APPLICATION, status: 'ACCEPTED' } })

    const api = useVillageMatchApi()
    const res = await api.reviewApplication(VILLAGE, RECRUIT, APPLICATION, { status: 'ACCEPTED' })

    // `/accept` ではなく `/review`。BE に accept/reject パスは存在しない（404 COMMON_005）
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/villages/${VILLAGE}/match-recruits/${RECRUIT}/applications/${APPLICATION}/review`,
      { method: 'POST', body: { status: 'ACCEPTED' } },
    )
    expect(res.status).toBe('ACCEPTED')
  })

  it('VMATCH-API-004: reviewApplication は却下時に status=REJECTED を送る', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: APPLICATION, status: 'REJECTED' } })

    const api = useVillageMatchApi()
    await api.reviewApplication(VILLAGE, RECRUIT, APPLICATION, { status: 'REJECTED' })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/villages/${VILLAGE}/match-recruits/${RECRUIT}/applications/${APPLICATION}/review`,
      { method: 'POST', body: { status: 'REJECTED' } },
    )
  })

  it('VMATCH-API-005: reviewApplication は reviewComment を送れる', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: APPLICATION, status: 'REJECTED' } })

    const api = useVillageMatchApi()
    await api.reviewApplication(VILLAGE, RECRUIT, APPLICATION, {
      status: 'REJECTED',
      reviewComment: '日程が合わないため',
    })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/villages/${VILLAGE}/match-recruits/${RECRUIT}/applications/${APPLICATION}/review`,
      { method: 'POST', body: { status: 'REJECTED', reviewComment: '日程が合わないため' } },
    )
  })
})
