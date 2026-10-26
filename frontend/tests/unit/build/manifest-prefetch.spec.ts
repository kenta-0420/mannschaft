import { createRendererContext, getRequestDependencies } from 'vue-bundle-renderer/runtime'
import { describe, expect, it, vi } from 'vitest'

type ManifestEntry = {
  file: string
  isEntry?: boolean
  module?: boolean
  preload?: boolean
  prefetch?: boolean
  sideEffects?: boolean
  imports?: string[]
  dynamicImports?: string[]
}

describe('本番 SSR の依存ヒント', () => {
  it('遅延資産の prefetch だけを抑え、必須資産と依存関係を保つ', async () => {
    vi.stubGlobal('defineNuxtConfig', (config: unknown) => config)
    try {
      const config = (await import('../../../nuxt.config')).default
      const hook = config.hooks?.['build:manifest'] as
        | ((manifest: Record<string, Record<string, unknown>>) => void)
        | undefined
      expect(hook).toBeTypeOf('function')

      const original: Record<string, ManifestEntry> = {
        entry: {
          file: 'entry.js',
          isEntry: true,
          module: true,
          preload: true,
          imports: ['shared'],
          dynamicImports: ['lazy'],
        },
        shared: { file: 'shared.js', module: true, preload: true, prefetch: true },
        lazy: {
          file: 'lazy.js',
          module: true,
          sideEffects: true,
          prefetch: true,
          imports: ['lazy-shared'],
        },
        'lazy-shared': { file: 'lazy-shared.js', module: true, preload: true, prefetch: true },
      }
      const before = structuredClone(original)
      const after = structuredClone(original)
      const dependencies = (manifest: Record<string, ManifestEntry>) =>
        getRequestDependencies({}, createRendererContext({ manifest }))

      const oldDependencies = dependencies(before)
      expect(Object.keys(oldDependencies.prefetch)).toContain('lazy')
      hook?.(after)
      const newDependencies = dependencies(after)

      expect(newDependencies.prefetch).toEqual({})
      expect(Object.keys(newDependencies.preload)).toEqual(Object.keys(oldDependencies.preload))
      expect(Object.keys(newDependencies.scripts)).toEqual(Object.keys(oldDependencies.scripts))
      for (const id of Object.keys(oldDependencies.preload)) {
        expect(newDependencies.preload[id].file).toBe(oldDependencies.preload[id].file)
      }
      for (const id of Object.keys(oldDependencies.scripts)) {
        expect(newDependencies.scripts[id].file).toBe(oldDependencies.scripts[id].file)
      }
      for (const id of Object.keys(original)) {
        expect(after[id].file).toBe(before[id].file)
        expect(after[id].imports).toEqual(before[id].imports)
        expect(after[id].dynamicImports).toEqual(before[id].dynamicImports)
      }
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
