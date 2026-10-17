// @vitest-environment node
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { loadNuxtConfig } from '@nuxt/kit'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { describe, expect, it } from 'vitest'

const require = createRequire(import.meta.url)
const browserBundle = require.resolve('vue-i18n/dist/vue-i18n.mjs')
type Alias = Record<string, string> | { find: string; replacement: string }[]
type ViteConfig = { resolve: { alias: Alias } }
type ConfigHook = (config: ViteConfig, context: { isServer: boolean; isClient: boolean }) => void
const devtools = globalThis as typeof globalThis & { __VUE_DEVTOOLS_PLUGINS__?: unknown[] }

async function resolveBundle(
  environment: string,
  isServer: boolean,
  arrayAlias: boolean,
  original = browserBundle,
) {
  const nuxt = await loadNuxtConfig({
    cwd: process.cwd(),
    envName: environment,
    dotenv: false,
    rcFile: false,
    globalRc: false,
  })
  const config: ViteConfig = {
    resolve: {
      alias: arrayAlias ? [{ find: 'vue-i18n', replacement: original }] : { 'vue-i18n': original },
    },
  }
  const hook = nuxt.hooks?.['vite:extendConfig'] as ConfigHook | undefined
  await hook?.(config, { isServer, isClient: !isServer })
  return Array.isArray(config.resolve.alias)
    ? config.resolve.alias.find((alias) => alias.find === 'vue-i18n')!.replacement
    : config.resolve.alias['vue-i18n']!
}

describe('開発SSRのvue-i18n解決', () => {
  it.each([false, true])(
    '実SSRを繰り返してもdevtoolsがappを保持しない（alias配列=%s）',
    async (arrayAlias) => {
      const resolved = await resolveBundle('development', true, arrayAlias)
      const { createI18n } = (await import(
        /* @vite-ignore */ pathToFileURL(resolved).href
      )) as typeof import('vue-i18n')
      const previous = devtools.__VUE_DEVTOOLS_PLUGINS__
      const before = previous?.length ?? 0
      try {
        for (let index = 0; index < 3; index++) {
          const i18n = createI18n({
            legacy: false,
            locale: 'ja',
            messages: { ja: { greeting: '翻訳済み' } },
          })
          const app = createSSRApp({ render: () => h('p', i18n.global.t('greeting')) })
          app.use(i18n)
          expect(await renderToString(app)).toContain('翻訳済み')
          i18n.dispose()
        }
        expect((devtools.__VUE_DEVTOOLS_PLUGINS__?.length ?? 0) - before).toBe(0)
      } finally {
        // この試験が追加した記録だけを戻し、他の試験のグローバル状態を保持する。
        if (previous) previous.splice(before)
        else delete devtools.__VUE_DEVTOOLS_PLUGINS__
      }
    },
  )

  it.each([false, true])('ブラウザのbundleを変更しない（alias配列=%s）', async (arrayAlias) => {
    expect(await resolveBundle('development', false, arrayAlias)).toBe(browserBundle)
  })

  it.each(['vue-i18n.mjs', 'vue-i18n.runtime.mjs'])('本番の%sを変更しない', async (file) => {
    const original = require.resolve(`vue-i18n/dist/${file}`)
    expect(await resolveBundle('production', true, false, original)).toBe(original)
  })
})
