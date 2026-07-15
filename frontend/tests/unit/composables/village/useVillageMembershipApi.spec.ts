import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F17.1 useVillageMembershipApi ユニットテスト
 *
 * 背景（村ドメイン FE/BE 契約不一致 No.6 / No.7）:
 *   `GET /villages/{villageId}/join-requests` と
 *   `GET /admin/village-creation-requests` は Spring の `Page` を
 *   そのまま JSON 露出する（`content` の配下に実データを持つ）。
 *   以前の FE 型は `{ data: X[] }` と誤宣言しており、`.content` を
 *   経由せずに Page オブジェクト自体を配列扱いしてしまうバグがあった。
 *
 *   本テストは BE の**真の Page 応答形状**（pageable/sort 等を含む）を
 *   モックし、composable が `.content` を含む Page オブジェクトを
 *   そのまま返すこと（＝呼び出し元が `.content` で取り出せること）を保証する。
 *
 * 検証観点:
 *   VILLAGE-MEMBERSHIP-API-001: listJoinRequests は Page 形状のまま data を返す（content 経由必須）
 *   VILLAGE-MEMBERSHIP-API-002: listJoinRequests は 0 件時も content: [] の Page を返す（配列直返しではない）
 *   VILLAGE-MEMBERSHIP-API-003: listAdminCreationRequests は Page 形状のまま data を返す（content 経由必須）
 *   VILLAGE-MEMBERSHIP-API-004: listMyCreationRequests は素の配列のまま data を返す（listAdminCreationRequests との非対称）
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useVillageMembershipApi } from '~/composables/village/useVillageMembershipApi'

const VILLAGE_ID = 'v-uuid-1'

/** BE の真の Spring Page 応答形状（pageable/sort を含む）。 */
function buildSpringPage<T>(content: T[]) {
  return {
    content,
    pageable: {
      pageNumber: 0,
      pageSize: 20,
      sort: { empty: content.length === 0, sorted: false, unsorted: true },
      offset: 0,
      paged: true,
      unpaged: false,
    },
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    last: true,
    size: 20,
    number: 0,
    sort: { empty: content.length === 0, sorted: false, unsorted: true },
    numberOfElements: content.length,
    first: true,
    empty: content.length === 0,
  }
}

describe('useVillageMembershipApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('VILLAGE-MEMBERSHIP-API-001: listJoinRequests は Page 形状のまま data を返す（content 経由必須）', async () => {
    const joinRequest = {
      id: 'jr-1',
      villageId: VILLAGE_ID,
      subjectType: 'USER' as const,
      subjectId: 42,
      message: null,
      status: 'PENDING' as const,
      reviewedBy: null,
      reviewedAt: null,
      reviewComment: null,
      createdAt: '2026-07-15T00:00:00Z',
    }
    mockFetch.mockResolvedValueOnce({ data: buildSpringPage([joinRequest]) })

    const api = useVillageMembershipApi()
    const res = await api.listJoinRequests(VILLAGE_ID, 'PENDING')

    expect(mockFetch).toHaveBeenCalledWith(`/api/v1/villages/${VILLAGE_ID}/join-requests?status=PENDING`)
    // Page オブジェクトそのものが返る（配列ではない）。呼び出し元は .content で取り出す。
    expect(Array.isArray(res)).toBe(false)
    expect(res.content).toEqual([joinRequest])
    expect(res.totalElements).toBe(1)
  })

  it('VILLAGE-MEMBERSHIP-API-002: listJoinRequests は 0 件時も content: [] の Page を返す（配列直返しではない）', async () => {
    mockFetch.mockResolvedValueOnce({ data: buildSpringPage([]) })

    const api = useVillageMembershipApi()
    const res = await api.listJoinRequests(VILLAGE_ID)

    expect(res.content).toEqual([])
    expect(res.totalElements).toBe(0)
    expect(res.pageable).toBeDefined()
  })

  it('VILLAGE-MEMBERSHIP-API-003: listAdminCreationRequests は Page 形状のまま data を返す（content 経由必須）', async () => {
    const creationRequest = {
      id: 'cr-1',
      requesterUserId: 1,
      name: '村A',
      slug: 'village-a',
      category: null,
      purpose: null,
      status: 'PENDING' as const,
      reviewedBy: null,
      reviewedAt: null,
      reviewComment: null,
      createdVillageId: null,
      createdAt: '2026-07-15T00:00:00Z',
    }
    mockFetch.mockResolvedValueOnce({ data: buildSpringPage([creationRequest]) })

    const api = useVillageMembershipApi()
    const res = await api.listAdminCreationRequests({ status: 'PENDING' })

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/admin/village-creation-requests?status=PENDING')
    expect(Array.isArray(res)).toBe(false)
    expect(res.content).toEqual([creationRequest])
  })

  it('VILLAGE-MEMBERSHIP-API-004: listMyCreationRequests は素の配列のまま data を返す（listAdminCreationRequests との非対称）', async () => {
    const creationRequest = {
      id: 'cr-2',
      requesterUserId: 1,
      name: '村B',
      slug: 'village-b',
      category: null,
      purpose: null,
      status: 'PENDING' as const,
      reviewedBy: null,
      reviewedAt: null,
      reviewComment: null,
      createdVillageId: null,
      createdAt: '2026-07-15T00:00:00Z',
    }
    // BE: ApiResponse<List<...>> — Page ではなく素の配列
    mockFetch.mockResolvedValueOnce({ data: [creationRequest] })

    const api = useVillageMembershipApi()
    const res = await api.listMyCreationRequests()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/village-creation-requests')
    expect(Array.isArray(res)).toBe(true)
    expect(res).toEqual([creationRequest])
  })
})
