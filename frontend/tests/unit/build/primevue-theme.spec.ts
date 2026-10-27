import Aura from '@primeuix/themes/aura'
import { describe, expect, it, vi } from 'vitest'

describe('PrimeVue テーマのモジュール設定', () => {
  it('runtimeConfigへpresetを複製せずSSRとCSRに同一テーマを渡す', async () => {
    vi.stubGlobal('defineNuxtConfig', (config: unknown) => config)
    try {
      const config = (await import('../../../nuxt.config')).default
      expect(config.primevue?.options).toEqual({ ripple: true, inputVariant: 'filled' })
      expect(config.primevue?.importTheme).toEqual({
        as: 'MannschaftTheme', from: '~/themes/mannschaft',
      })
      expect(config.primevue?.autoImport).toBe(true)
      expect(config.primevue?.components?.prefix).toBe('')
      const themeModule = '../../../app/themes/mannschaft.ts'
      const { default: theme } = await import(/* @vite-ignore */ themeModule)
      expect(theme.preset).toBe(Aura)
      expect(theme.options).toEqual({ prefix: 'p', darkModeSelector: '.p-dark', cssLayer: false })
    } finally {
      vi.unstubAllGlobals()
    }
  })
})