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
 *
 * サーバーサイドページング化（村作成申請 運営画面のページング不具合修正）に伴う追加観点:
 *   VILLAGE-MEMBERSHIP-API-005: listAdminCreationRequests は page/size をクエリに送信する（page=0 も落とさない）
 *   VILLAGE-MEMBERSHIP-API-006: listAdminCreationRequests は 21件超（2ページ目）で totalElements ベースの
 *     真の総件数と、入れ替わった content（切り出しではなく BE から取得した該当ページの実データ）を返す
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

  it('VILLAGE-MEMBERSHIP-API-005: listAdminCreationRequests は page/size をクエリに送信する（page=0 も落とさない）', async () => {
    mockFetch.mockResolvedValueOnce({ data: buildSpringPage([]) })

    const api = useVillageMembershipApi()
    await api.listAdminCreationRequests({ status: 'PENDING', page: 0, size: 20 })

    // page=0 は falsy だが省略されず送信されること（qs() の抜け穴回避）
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/admin/village-creation-requests?status=PENDING&page=0&size=20',
    )
  })

  it('VILLAGE-MEMBERSHIP-API-006: listAdminCreationRequests は2ページ目で totalElements ベースの総件数と入れ替わった content を返す', async () => {
    // 21件中の2ページ目（page=1, size=20）＝1件のみ。BE の真の Page 応答形状（pageable/sort込み）をモックする。
    const page2Item = {
      id: 'cr-21',
      requesterUserId: 21,
      name: '村21',
      slug: 'village-21',
      category: null,
      purpose: null,
      status: 'PENDING' as const,
      reviewedBy: null,
      reviewedAt: null,
      reviewComment: null,
      createdVillageId: null,
      createdAt: '2026-07-15T00:00:00Z',
    }
    mockFetch.mockResolvedValueOnce({
      data: {
        ...buildSpringPage([page2Item]),
        pageable: {
          pageNumber: 1,
          pageSize: 20,
          sort: { empty: false, sorted: false, unsorted: true },
          offset: 20,
          paged: true,
          unpaged: false,
        },
        totalElements: 21,
        totalPages: 2,
        last: true,
        first: false,
        number: 1,
      },
    })

    const api = useVillageMembershipApi()
    const res = await api.listAdminCreationRequests({ status: 'PENDING', page: 1, size: 20 })

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/admin/village-creation-requests?status=PENDING&page=1&size=20',
    )
    // クライアント側での切り出しではなく、BE から取得した該当ページの実データがそのまま入れ替わっていること
    expect(res.content).toEqual([page2Item])
    // 総件数表示はクライアント側の配列長（1）ではなく totalElements（21）に基づくこと
    expect(res.totalElements).toBe(21)
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
