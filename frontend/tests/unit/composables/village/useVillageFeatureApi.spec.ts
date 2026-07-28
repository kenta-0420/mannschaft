import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F17.1 useVillageFeatureApi 契約テスト（代表委任）
 *
 * 目的:
 *   No.9「代表委任の取消」は FE が `POST /representatives/{id}/revoke` を叩いていたが
 *   BE に該当経路は無く実測 404 COMMON_005 だった。BE の実体は
 *   `DELETE /representatives/{representativeId}`（実測 404 VILLAGE_052 = 経路は存在し
 *   ダミー ID で弾かれただけ。COMMON_005 との違いが経路存在の証拠）。
 *   同種の不一致（FE が動詞パスを発明する）を機械的に固定する。
 *
 * 権威:
 *   backend/src/main/java/com/mannschaft/app/village/controller/VillageRepresentativeController.java
 *   backend/src/main/java/com/mannschaft/app/village/dto/RepresentativeRevokeRequest.java
 *
 * 検証観点:
 *   VREP-API-001: listRepresentatives → GET /representatives
 *   VREP-API-002: grantRepresentative → POST /representatives に body
 *   VREP-API-003: revokeRepresentative → DELETE /representatives/{id}（body 無し）
 *   VREP-API-004: revokeRepresentative は note 指定時のみ body を載せる
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useVillageFeatureApi } from '~/composables/village/useVillageFeatureApi'

const VILLAGE = 'v-uuid-1'
const REPRESENTATIVE = 'rep-uuid-1'

describe('useVillageFeatureApi — 代表委任', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('VREP-API-001: listRepresentatives は GET /representatives', async () => {
    mockFetch.mockResolvedValueOnce({ data: [{ id: REPRESENTATIVE }] })

    const api = useVillageFeatureApi()
    const res = await api.listRepresentatives(VILLAGE)

    expect(mockFetch).toHaveBeenCalledWith(`/api/v1/villages/${VILLAGE}/representatives`)
    expect(res).toEqual([{ id: REPRESENTATIVE }])
  })

  it('VREP-API-002: grantRepresentative は POST /representatives に body', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: REPRESENTATIVE } })

    const api = useVillageFeatureApi()
    await api.grantRepresentative(VILLAGE, {
      membershipId: 'm-uuid-1',
      representativeUserId: 42,
    })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/villages/${VILLAGE}/representatives`,
      { method: 'POST', body: { membershipId: 'm-uuid-1', representativeUserId: 42 } },
    )
  })

  it('VREP-API-003: revokeRepresentative は DELETE /representatives/{id}（body 無し）', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: REPRESENTATIVE, revokedAt: '2026-07-15T00:00:00' } })

    const api = useVillageFeatureApi()
    await api.revokeRepresentative(VILLAGE, REPRESENTATIVE)

    // `POST .../{id}/revoke` は BE に存在しない（404 COMMON_005）
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/villages/${VILLAGE}/representatives/${REPRESENTATIVE}`,
      { method: 'DELETE' },
    )
  })

  it('VREP-API-004: revokeRepresentative は note 指定時のみ body を載せる', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: REPRESENTATIVE } })

    const api = useVillageFeatureApi()
    await api.revokeRepresentative(VILLAGE, REPRESENTATIVE, { note: '代表交代のため' })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/villages/${VILLAGE}/representatives/${REPRESENTATIVE}`,
      { method: 'DELETE', body: { note: '代表交代のため' } },
    )
  })
})
