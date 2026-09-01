import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useOrganizationNumericId } from '~/composables/useOrganizationNumericId'

const getOrganization = vi.fn()

vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({ getOrganization }),
}))

describe('useOrganizationNumericId', () => {
  beforeEach(() => {
    getOrganization.mockReset()
  })

  it('slug で取得した組織詳細の numericId を文字列で返す', async () => {
    getOrganization.mockResolvedValue({ data: { id: 'my-org', numericId: 42 } })

    const { resolveOrganizationNumericId } = useOrganizationNumericId()

    await expect(resolveOrganizationNumericId('my-org')).resolves.toBe('42')
    expect(getOrganization).toHaveBeenCalledWith('my-org')
  })

  it.each([undefined, null, 0, -1, 1.5])(
    '有効な numericId が無い場合は拒否する (%s)',
    async (numericId) => {
      getOrganization.mockResolvedValue({ data: { id: 'my-org', numericId } })

      const { resolveOrganizationNumericId } = useOrganizationNumericId()

      await expect(resolveOrganizationNumericId('my-org')).rejects.toThrow(
        'Organization numeric ID is unavailable: my-org',
      )
    },
  )
})
