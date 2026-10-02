import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { usePostalCodeValidation } from '~/composables/usePostalCodeValidation'

const api = vi.fn()

mockNuxtImport('useApi', () => () => api)

describe('usePostalCodeValidation', () => {
  it('公開ポリシーを読み込み、国コードを正規化して形式を検証する', async () => {
    api.mockResolvedValueOnce({
      data: [
        { countryCode: 'JP', pattern: '^\\d{3}-?\\d{4}$', example: '123-4567' },
      ],
    })

    const postalCodeValidation = usePostalCodeValidation()

    expect(postalCodeValidation.isLoaded.value).toBe(false)
    await postalCodeValidation.ensureLoaded()

    expect(api).toHaveBeenCalledWith('/api/v1/postal-code/policies')
    expect(postalCodeValidation.isLoaded.value).toBe(true)
    expect(postalCodeValidation.isSupported('jp')).toBe(true)
    expect(postalCodeValidation.isSupported('FR')).toBe(false)
    expect(postalCodeValidation.validateFormat('JP', '123-4567')).toBe(true)
    expect(postalCodeValidation.validateFormat('JP', '1234567')).toBe(true)
    expect(postalCodeValidation.validateFormat('JP', '123-456')).toBe(false)
  })
})
