import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useBlogApi } from '~/composables/useBlogApi'

const api = vi.fn()

mockNuxtImport('useApi', () => () => api)

describe('useBlogApi.patchPublicVisible', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue(undefined)
  })

  it('正準エンドポイントへcamelCaseの公開設定をPATCHする', async () => {
    await useBlogApi().patchPublicVisible(42, false)
    expect(api).toHaveBeenCalledWith('/api/v1/blog/posts/42/public-visible', {
      method: 'PATCH',
      body: { publicVisible: false },
    })
  })
})
