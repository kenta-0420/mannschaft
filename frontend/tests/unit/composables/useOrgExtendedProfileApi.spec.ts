import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F01.2 useOrgExtendedProfileApi のユニットテスト。
 *
 * テストケース:
 * 1. getProfile: GET /api/v1/organizations/{id}/profile が呼ばれること
 * 2. updateProfile: PATCH /api/v1/organizations/{id}/profile が呼ばれること
 * 3. getOfficers: GET /api/v1/organizations/{id}/officers が呼ばれること（visibilityPreview なし/あり）
 * 4. createOfficer: POST /api/v1/organizations/{id}/officers が呼ばれること
 * 5. updateOfficer: PATCH /api/v1/organizations/{id}/officers/{officerId} が呼ばれること
 * 6. deleteOfficer: DELETE /api/v1/organizations/{id}/officers/{officerId} が呼ばれること
 * 7. reorderOfficers: PUT /api/v1/organizations/{id}/officers/reorder が呼ばれること
 * 8. getCustomFields: GET /api/v1/organizations/{id}/custom-fields が呼ばれること（visibilityPreview なし/あり）
 * 9. createCustomField: POST /api/v1/organizations/{id}/custom-fields が呼ばれること
 * 10. updateCustomField: PATCH /api/v1/organizations/{id}/custom-fields/{fieldId} が呼ばれること
 * 11. deleteCustomField: DELETE /api/v1/organizations/{id}/custom-fields/{fieldId} が呼ばれること
 * 12. reorderCustomFields: PUT /api/v1/organizations/{id}/custom-fields/reorder が呼ばれること
 */

// useApi のモック（モック設定は import より先に書く）
const mockApiFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApiFetch,
}))

// テスト対象を動的 import（モック設定後に import する必要がある）
const { useOrgExtendedProfileApi } = await import('~/composables/useOrgExtendedProfileApi')

describe('useOrgExtendedProfileApi', () => {
  beforeEach(() => {
    mockApiFetch.mockReset()
  })

  // ===== getProfile テスト =====

  describe('getProfile', () => {
    it('GET /api/v1/organizations/{id}/profile を呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: { homepage_url: 'https://example.com' } })

      const api = useOrgExtendedProfileApi()
      await api.getProfile(1)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      expect(mockApiFetch.mock.calls[0][0]).toBe('/api/v1/organizations/1/profile')
      expect(mockApiFetch.mock.calls[0][1]).toBeUndefined()
    })

    it('orgId がURLに正しく反映されること', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: {} })

      const api = useOrgExtendedProfileApi()
      await api.getProfile(42)

      expect(mockApiFetch.mock.calls[0][0]).toBe('/api/v1/organizations/42/profile')
    })
  })

  // ===== updateProfile テスト =====

  describe('updateProfile', () => {
    it('PATCH /api/v1/organizations/{id}/profile を正しい body で呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: {} })

      const api = useOrgExtendedProfileApi()
      const body = { homepage_url: 'https://updated.example.com', description: 'テスト組織' }
      await api.updateProfile(1, body)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/organizations/1/profile')
      expect(options.method).toBe('PATCH')
      expect(options.body).toEqual(body)
    })
  })

  // ===== getOfficers テスト =====

  describe('getOfficers', () => {
    it('visibilityPreview=false（デフォルト）の場合、クエリパラメータなしのURLで呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: [] })

      const api = useOrgExtendedProfileApi()
      await api.getOfficers(1)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      expect(mockApiFetch.mock.calls[0][0]).toBe('/api/v1/organizations/1/officers')
      expect(mockApiFetch.mock.calls[0][1]).toBeUndefined()
    })

    it('visibilityPreview=true の場合、?visibilityPreview=true 付きURLで呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: [] })

      const api = useOrgExtendedProfileApi()
      await api.getOfficers(1, true)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      expect(mockApiFetch.mock.calls[0][0]).toBe('/api/v1/organizations/1/officers?visibilityPreview=true')
    })

    it('visibilityPreview=false を明示的に渡した場合、クエリパラメータなしのURLで呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: [] })

      const api = useOrgExtendedProfileApi()
      await api.getOfficers(1, false)

      expect(mockApiFetch.mock.calls[0][0]).toBe('/api/v1/organizations/1/officers')
    })
  })

  // ===== createOfficer テスト =====

  describe('createOfficer', () => {
    it('POST /api/v1/organizations/{id}/officers を正しい body で呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: {} })

      const api = useOrgExtendedProfileApi()
      const body = { name: '代表', title: '代表取締役', display_order: 1 }
      await api.createOfficer(1, body)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/organizations/1/officers')
      expect(options.method).toBe('POST')
      expect(options.body).toEqual(body)
    })
  })

  // ===== updateOfficer テスト =====

  describe('updateOfficer', () => {
    it('PATCH /api/v1/organizations/{id}/officers/{officerId} を正しい body で呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: {} })

      const api = useOrgExtendedProfileApi()
      const body = { name: '更新された役員名', title: '会長' }
      await api.updateOfficer(1, 10, body)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/organizations/1/officers/10')
      expect(options.method).toBe('PATCH')
      expect(options.body).toEqual(body)
    })
  })

  // ===== deleteOfficer テスト =====

  describe('deleteOfficer', () => {
    it('DELETE /api/v1/organizations/{id}/officers/{officerId} を呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce(undefined)

      const api = useOrgExtendedProfileApi()
      await api.deleteOfficer(1, 10)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/organizations/1/officers/10')
      expect(options.method).toBe('DELETE')
    })
  })

  // ===== reorderOfficers テスト =====

  describe('reorderOfficers', () => {
    it('PUT /api/v1/organizations/{id}/officers/reorder を { ids } body で呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce(undefined)

      const api = useOrgExtendedProfileApi()
      const body = { ids: [3, 1, 2] }
      await api.reorderOfficers(1, body)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/organizations/1/officers/reorder')
      expect(options.method).toBe('PUT')
      expect(options.body).toEqual({ ids: [3, 1, 2] })
    })
  })

  // ===== getCustomFields テスト =====

  describe('getCustomFields', () => {
    it('visibilityPreview=false（デフォルト）の場合、クエリパラメータなしのURLで呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: [] })

      const api = useOrgExtendedProfileApi()
      await api.getCustomFields(1)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      expect(mockApiFetch.mock.calls[0][0]).toBe('/api/v1/organizations/1/custom-fields')
      expect(mockApiFetch.mock.calls[0][1]).toBeUndefined()
    })

    it('visibilityPreview=true の場合、?visibilityPreview=true 付きURLで呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: [] })

      const api = useOrgExtendedProfileApi()
      await api.getCustomFields(1, true)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      expect(mockApiFetch.mock.calls[0][0]).toBe(
        '/api/v1/organizations/1/custom-fields?visibilityPreview=true',
      )
    })

    it('visibilityPreview=false を明示的に渡した場合、クエリパラメータなしのURLで呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: [] })

      const api = useOrgExtendedProfileApi()
      await api.getCustomFields(1, false)

      expect(mockApiFetch.mock.calls[0][0]).toBe('/api/v1/organizations/1/custom-fields')
    })
  })

  // ===== createCustomField テスト =====

  describe('createCustomField', () => {
    it('POST /api/v1/organizations/{id}/custom-fields を正しい body で呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: {} })

      const api = useOrgExtendedProfileApi()
      const body = { label: '設立年', field_type: 'TEXT', display_order: 1 }
      await api.createCustomField(1, body)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/organizations/1/custom-fields')
      expect(options.method).toBe('POST')
      expect(options.body).toEqual(body)
    })
  })

  // ===== updateCustomField テスト =====

  describe('updateCustomField', () => {
    it('PATCH /api/v1/organizations/{id}/custom-fields/{fieldId} を正しい body で呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce({ data: {} })

      const api = useOrgExtendedProfileApi()
      const body = { label: '更新されたラベル', value: '2020年' }
      await api.updateCustomField(1, 5, body)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/organizations/1/custom-fields/5')
      expect(options.method).toBe('PATCH')
      expect(options.body).toEqual(body)
    })
  })

  // ===== deleteCustomField テスト =====

  describe('deleteCustomField', () => {
    it('DELETE /api/v1/organizations/{id}/custom-fields/{fieldId} を呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce(undefined)

      const api = useOrgExtendedProfileApi()
      await api.deleteCustomField(1, 5)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/organizations/1/custom-fields/5')
      expect(options.method).toBe('DELETE')
    })
  })

  // ===== reorderCustomFields テスト =====

  describe('reorderCustomFields', () => {
    it('PUT /api/v1/organizations/{id}/custom-fields/reorder を { ids } body で呼ぶこと', async () => {
      mockApiFetch.mockResolvedValueOnce(undefined)

      const api = useOrgExtendedProfileApi()
      const body = { ids: [2, 5, 1, 3] }
      await api.reorderCustomFields(1, body)

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/organizations/1/custom-fields/reorder')
      expect(options.method).toBe('PUT')
      expect(options.body).toEqual({ ids: [2, 5, 1, 3] })
    })
  })
})
