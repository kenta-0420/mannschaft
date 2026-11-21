import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useSignageApi } from './useSignageApi'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => mocks.api)
beforeEach(() => mocks.api.mockReset())

describe('サイネージ数値scopeIdのAPI契約（初回greenのcharacterization）', () => {
  it('一覧queryは解決済みの数値IDを使う', async () => {
    await useSignageApi().getScreens('ORGANIZATION', 42)
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/signage/screens?scopeType=ORGANIZATION&scopeId=42')
  })

  it('作成bodyもLongに対応する数値IDを保つ', async () => {
    await useSignageApi().createScreen({
      scopeType: 'ORGANIZATION', scopeId: 42, name: 'screen',
      layout: 'FULLSCREEN', defaultSlideDuration: 10, transitionEffect: 'FADE',
    })
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/signage/screens', {
      method: 'POST',
      body: {
        scopeType: 'ORGANIZATION', scopeId: 42, name: 'screen',
        layout: 'FULLSCREEN', defaultSlideDuration: 10, transitionEffect: 'FADE',
      },
    })
  })
})
